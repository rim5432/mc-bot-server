package com.mcbot.mcbotserver.pathing;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import com.mcbot.mcbotserver.core.world.SnapshotWorldView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-2 async replan gate: searches leave the tick thread, results
 * are adopted only when fresh (goal unchanged, start cell still ours),
 * and a search in flight never masquerades as NO_PATH.
 *
 * <p>Contract: see boundaries.md decision 17b and AGENTS.md section
 * 2.2 - the worker reads snapshots only; adoption is tick-thread work.
 */
class PlanWorkerGateTest {

    private final PlanWorker worker = new PlanWorker();

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    /** Delegating actor that records every submission this tick. */
    private static final class RecordingActor implements Actor {
        private final ChannelArbiter delegate = new ChannelArbiter();
        final List<Claim> submitted = new ArrayList<>();

        @Override
        public void submit(Claim claim) {
            submitted.add(claim);
            delegate.submit(claim);
        }

        @Override
        public java.util.Map<Channel, Claim> flush() {
            return delegate.flush();
        }

        @Override
        public void clearAllIntents() {
            submitted.clear();
            delegate.clearAllIntents();
        }
    }

    private static MockWorldView floorTo(int xLen) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new com.mcbot.mcbotserver.api.world
                    .BlockSnapshot(new CellPos(x, 63, z),
                        "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    /**
     * Frozen body, reachable goal: the first tick requests a plan and
     * must NOT report NO_PATH while it is in flight; once the worker
     * answers, waypoints are adopted and MOVE claims flow - recovery
     * semantics preserved on the async path.
     */
    @Test
    void asyncPlanAdoptsAndSteersWithoutFalseNoPath()
            throws Exception {
        Vec3[] pose = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0], BasicMoves::from, worker);
        RecordingActor actor = new RecordingActor();
        WorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        ExecutionReport first = mover.tick(world, directive, actor);
        assertEquals(ExecutionReport.Status.RUNNING, first.status(),
            "a plan in flight is not an answer; wait for it");

        // The worker is fast but asynchronous: give it a bounded,
        // generous window instead of assuming instant completion.
        for (int i = 0; i < 50 && moverClaimsSince(actor, 0) == 0;
             i++) {
            Thread.sleep(10);
            mover.tick(world, directive, actor);
        }

        assertTrue(moverClaimsSince(actor, 0) > 0,
            "an adopted plan must steer even with a frozen body");
    }

    /**
     * Goal switched while the search runs: the finished result is
     * stale by definition and is discarded whole - no claims toward
     * the abandoned goal may ever be emitted.
     */
    @Test
    void staleResultForAbandonedGoalIsDiscarded() throws Exception {
        Vec3[] pose = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0], BasicMoves::from, worker);
        RecordingActor actor = new RecordingActor();
        WorldView world = floorTo(60);
        Directive oldGoal = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        mover.tick(world, oldGoal, actor);

        // Swap goals BEFORE the answer lands, then wait out the
        // worker so we know the future completed.
        Directive newGoal = Directive.of(
            new GoalBlock(new CellPos(-30, 64, 0)));
        Thread.sleep(200);
        int before = actor.submitted.size();
        ExecutionReport report = mover.tick(world, newGoal, actor);

        assertEquals(ExecutionReport.Status.RUNNING, report.status(),
            "stale discard leaves nothing to steer toward yet");
        assertTrue(moverClaimsSince(actor, before) == 0,
            "no claim may reference the abandoned goal's route");
    }

    /**
     * Snapshot honesty: cells outside the captured box read as
     * unknown, so the planner can never route through terrain it did
     * not see.
     */
    @Test
    void snapshotAnswersNullOutsideCapturedBox() {
        MockWorldView world = floorTo(60);
        world.putBlock(new com.mcbot.mcbotserver.api.world
            .BlockSnapshot(new CellPos(500, 63, 500),
                "minecraft:stone"));

        SnapshotWorldView snap = SnapshotWorldView.capture(world,
            new CellPos(0, 64, 0), new CellPos(20, 64, 0));

        assertNotNull(snap.getBlock(new CellPos(10, 64, 0), null),
            "inside the box the copy is authoritative");
        assertNull(snap.getBlock(new CellPos(500, 63, 500), null),
            "outside the box is unknown, not air");
        assertFalse(snap.isLoaded(new CellPos(500, 63, 500)));
    }

    /**
     * Worker contract: unbounded wall-clock searches are rejected at
     * the door - the shared worker has no room for open-ended work.
     */
    @Test
    void workerRejectsUnboundedWallClock() {
        var snapshot = SnapshotWorldView.capture(floorTo(4),
            new CellPos(0, 64, 0), new CellPos(2, 64, 0));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> worker.submit(snapshot, BasicMoves::from,
                new CellPos(0, 64, 0),
                new GoalBlock(new CellPos(2, 64, 0)),
                Heuristic.euclideanTo(new CellPos(2, 64, 0)),
                100, 0L));
    }

    private int moverClaimsSince(RecordingActor actor, int from) {
        return (int) actor.submitted.subList(from, actor.submitted.size())
            .stream()
            .filter(c -> c.channel() == Channel.MOVE).count();
    }
}
