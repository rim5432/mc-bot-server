package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Limbo-characterization gate (issue 0001 §3 branch 2, §7). Pins
 * the CURRENT behaviour of the progress fuse on a body in
 * continuous vertical motion (waterfall column) so the diff
 * between pre-fix-4 and post-fix-4 is exactly the assertion flip
 * in PR-2.
 *
 * <p>Why this exists: the previous fence (StuckFuseTest) only
 * covered motion < STUCK_EPSILON for STUCK_WINDOW ticks, and
 * fall-and-land scenarios. The waterfall limbo (continuous Y
 * motion, XZ stationary, body never lands) trips neither path
 * pre-fix-4: motion > epsilon, so the 3D fuse keeps resetting;
 * XZ is held at 1.5 from the next waypoint, so 0.8~3.0 limbo
 * never escapes via offPath. The fuse never fires.
 *
 * <p>Form 1 (issue 0001 §7) with K=100 ticks of continuous Y
 * motion at 0.3 m/tick: pre-fix-4, no STUCK; post-fix-4 (plan-
 * progress score), STUCK fires within K of the last plan-
 * progress tick. PR-2 flips the assertion; the test name
 * changes to {@code waterfallFiresStuck} and the
 * {@code assertFalse(noStuck)} becomes {@code assertTrue}.
 */
class LimboCharacterizationGateTest {

    /**
     * PR-2 fix proof: a body driven with continuous Y motion
     * (waterfall column), XZ held at 1.5 from the next waypoint,
     * MUST trip the plan-progress fuse in <= 20 ticks. Pre-fix-4
     * the motion detector saw 3D motion and kept resetting;
     * post-fix-4 the plan-progress score sees zero waypoint
     * advance, zero goal closure, zero current-waypoint
     * approach, and the accumulator fires. PR-1 Characterization
     * (no STUCK) is the inverse of this assertion - the diff
     * between this commit and PR-1 IS the fix.
     *
     * <p>Form 1 from issue 0001 §7: STUCK fires within K ticks
     * of the last plan-progress tick. Here K = 20. The harness
     * gets exactly one STUCK event (status STUCK) before the
     * planner's replan cooldown gate would otherwise gate
     * further progress; FAILED+reason="STUCK" can also fire
     * (PathingBehavior.java:280) if waypoints is empty and
     * the fuse fires post-exhaustion. Both channels count.
     */
    @Test
    void waterfallLimboFiresStuckPostFix4() {
        MockWorldView world = MockWorldView.pavedFloor(60);
        // Start at (0.5, 64, 0.5). Goal at (40, 64, 0). Plan adopted
        // after the first tick; the first remaining waypoint is
        // around (1, 64, 0). The position will sit at XZ=(0.5, 0.5)
        // (distance 0.707 from waypoint (1, 0)) - inside the
        // WAYPOINT_REACH band, so the waypoint is consumed
        // quickly. We hold the position XZ=0.5 and let Y fall.
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted.
        mover.tick(world, directive, actor);

        // Drive 100 ticks of continuous Y motion = 0.3 m/tick,
        // XZ unchanged. Pre-fix-4: motion > epsilon so the
        // motion fuse kept resetting, no STUCK. Post-fix-4:
        // no waypoint advance (XZ held), goal distance grows
        // (Y moves away from y=64.5), current waypoint
        // distance grows; the plan-progress score stays at 0
        // for every tick, the accumulator fires at tick 20
        // of no-progress.
        int stuckTick = -1;
        String stuckChannel = null;
        for (int i = 1; i <= 100; i++) {
            position[0] = new Vec3(position[0].x(), position[0].y() - 0.3, position[0].z());
            ExecutionReport r = mover.tick(world, directive, actor);
            boolean stuck = r.status() == ExecutionReport.Status.STUCK
                    || (r.status() == ExecutionReport.Status.FAILED && "STUCK".equals(r.reason()));
            if (stuck) {
                stuckTick = i;
                stuckChannel = r.status() + (r.reason() == null ? "" : "+" + r.reason());
                break;
            }
        }
        // K=20 from issue 0001 §7 form 1, plus a known +1 offset
        // from the first-observation credit on the outer tick. The
        // fuse fires at i=21 (call#22 overall). PR-1's
        // characterization (inverse assertion, "no STUCK fires")
        // is the diff proof: the same body in pre-fix-4 never
        // sees STUCK at all.
        assertTrue(
                stuckTick > 0 && stuckTick <= 21,
                "plan-progress fuse must fire STUCK within 21 ticks of "
                        + "entering continuous Y motion (limbo); fired at i="
                        + stuckTick + " (" + stuckChannel + "). Form 1 from "
                        + "issue 0001 §7 with K=20 + first-observation +1 offset.");
    }

    /**
     * Sanity counterpart: a body at rest on solid ground (no plan-
     * progress for {@code STUCK_WINDOW} ticks) DOES fire STUCK.
     * Confirms the test rig is wired correctly - if this fails,
     * the rig itself is broken, not the plan-progress fuse.
     *
     * <p>Timing: the first outer tick credits progress (sentinel
     * triggers: criterion 1 fires on any waypointIndex > -1,
     * criterion 2 fires on any goalDist < +inf). Subsequent ticks
     * of no progress increment the accumulator to 1, 2, ..., 20.
     * STUCK fires on the 21st tick (i=20 in the loop, which is
     * the 21st mover.tick call overall).
     */
    @Test
    void restFiresStuckViaPlanProgressFuse() {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted, sentinel-latch init credits
        // progress (counter reset to 0).
        mover.tick(world, directive, actor);

        // Hold position still for 25 ticks. No plan-progress -> fuse
        // accumulates -> STUCK at i=20 in the loop.
        int stuckTick = -1;
        for (int i = 1; i <= 25; i++) {
            ExecutionReport r = mover.tick(world, directive, actor);
            if (r.status() == ExecutionReport.Status.STUCK) {
                stuckTick = i;
                break;
            }
        }
        assertEquals(
                21,
                stuckTick,
                "rig sanity: no plan-progress for STUCK_WINDOW ticks "
                        + "must fire STUCK. The +1 over STUCK_WINDOW is the "
                        + "first-observation credit on the outer tick (issue 0001 "
                        + "§Ruling a, Path A initialization). Same offset as the "
                        + "waterfall test, written the same way for the same reason.");
    }
}
