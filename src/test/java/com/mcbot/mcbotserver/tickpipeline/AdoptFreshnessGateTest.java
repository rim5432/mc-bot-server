package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freshness-tolerance gate (issue 0001 fix 5 / Ruling (c)):
 * the {@code adoptCompletedPlan} check that a search result is
 * still valid must accept a 1-cell Chebyshev drift of the bot,
 * not strict cell-equality. A 4-5 tick A* run plus walking
 * speed 0.215 b/tick already crosses 1 cell on flat ground;
 * the previous strict-equality check discarded every result, the
 * fuse churned, the bot never made plan-progress. Path A (issue
 * 0001 §Ruling a) makes this even more visible: a stationary
 * bot inside the 0.8~3.0 limbo band would also be re-planning
 * every cooldown, accumulating wasted searches.
 *
 * <p>Contract: see boundaries.md decision 17b's staleness guard
 * - the contract was "start cell still ours" but the cell was
 * interpreted as equality; PR-3 reframes it as the move-graph's
 * tightest tolerance (Chebyshev 1, matching every BasicMoves
 * move).
 */
class AdoptFreshnessGateTest {

    private final PlanWorker worker = new PlanWorker();

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private static MockWorldView floorTo(int xLen) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new BlockSnapshot(
                    new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static final class NullActor implements Actor {
        @Override public void submit(Claim claim) {}
        @Override public Map<Channel, Claim> flush() { return Map.of(); }
        @Override public void clearAllIntents() {}
    }

    /**
     * Bot crosses 1 cell while the search runs. Under the old
     * cell-equality check the result is discarded; under the
     * Chebyshev-1 check the result is adopted and the bot
     * steers toward the new plan.
     */
    @Test
    void oneCellDriftDuringSearchIsAdopted() throws Exception {
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from, worker);
        WorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Tick 1: bot at cell (0, 64, 0). No plan yet; replan
        // requests a search with pendingStart = (0, 64, 0).
        mover.tick(world, directive, new NullActor());

        // The bot drifts 1 cell east while the worker runs the
        // search. This is the scenario the old cell-equality
        // check silently broke: a 0.215 b/tick walk x 5 ticks
        // (typical A* duration) crosses 1 cell.
        position[0] = new Vec3(1.5, 64, 0.5);

        // Wait for the search to complete (PlanWorker is async
        // on a single daemon thread, so the future finishes out
        // of band). A 1-second budget is generous; the search
        // usually finishes within tens of milliseconds.
        for (int i = 0; i < 100; i++) {
            Thread.sleep(10);
            mover.tick(world, directive, new NullActor());
            // Once claims flow, the plan was adopted.
            if (PathingTestAccess.waypoints(mover).size() > 0) {
                break;
            }
        }
        assertNotEquals(0, PathingTestAccess.waypoints(mover).size(),
            "after 1-cell drift during search, the plan must still "
            + "be adopted (Chebyshev-1 freshness, not cell-equality)");
    }

    /**
     * Chebyshev distance (the freshness metric) at the boundary
     * cases. Direct unit test of the helper, since the integration
     * 2-cell-discard test is non-deterministic (a discarded plan
     * immediately triggers a fresh plan that re-populates
     * waypoints, so the only observable difference is the
     * discard's primitive effect, not the eventual state).
     */
    @Test
    void chebyshevBoundaryCases() throws Exception {
        // Reflectively read the static helper.
        var m = PathingBehavior.class.getDeclaredMethod(
            "chebyshevDistance", CellPos.class, CellPos.class);
        m.setAccessible(true);

        // Same cell: 0.
        assertEquals(0, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(5, 64, 0)),
            "same cell has Chebyshev 0");

        // 1-cell offset on each axis independently: 1.
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(6, 64, 0)),
            "+X by 1: Chebyshev 1");
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(4, 64, 0)),
            "-X by 1: Chebyshev 1");
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(5, 65, 0)),
            "+Y by 1: Chebyshev 1");
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(5, 63, 0)),
            "-Y by 1: Chebyshev 1");
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(5, 64, 1)),
            "+Z by 1: Chebyshev 1");

        // Diagonal 1: max of 1, 1, 0 = 1. Still fresh.
        assertEquals(1, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(6, 65, 0)),
            "diagonal 1 (X+Y): Chebyshev 1, still fresh");

        // 2-cell offset: stale.
        assertEquals(2, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(7, 64, 0)),
            "+X by 2: Chebyshev 2, stale");
        assertEquals(2, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(5, 64, 2)),
            "+Z by 2: Chebyshev 2, stale");
        assertEquals(2, (int) m.invoke(null,
            new CellPos(5, 64, 0), new CellPos(6, 66, 0)),
            "X+1 Y+2: Chebyshev 2, stale");
    }

    /**
     * Bot drifts 1 cell in Y (vertical). Chebyshev 1 still
     * accepts. The plan-progress fuse (issue 0001 §Ruling a)
     * sees zero progress on this Y drift and fires STUCK
     * correctly; this test only checks the freshness decision
     * is correct.
     */
    @Test
    void oneCellVerticalDriftDuringSearchIsAdopted() throws Exception {
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from, worker);
        WorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Tick 1: request plan with pendingStart = (0, 64, 0).
        mover.tick(world, directive, new NullActor());

        // Bot jumps 1 cell up. Chebyshev distance = 1 (just the
        // Y axis).
        position[0] = new Vec3(0.5, 65, 0.5);

        for (int i = 0; i < 100; i++) {
            Thread.sleep(10);
            mover.tick(world, directive, new NullActor());
            if (PathingTestAccess.waypoints(mover).size() > 0) {
                break;
            }
        }
        assertNotEquals(0, PathingTestAccess.waypoints(mover).size(),
            "Chebyshev-1 must accept 1-cell drift on ANY axis, "
            + "including Y (ClimbUp / Drop in the move vocabulary "
            + "are max-Chebyshev 1, so this is the tightest "
            + "tolerance that does not over-reject)");
    }

}
