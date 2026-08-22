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
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private static final class RecordingActor implements Actor {
        final ChannelArbiter delegate = new ChannelArbiter();
        @Override public void submit(Claim claim) { delegate.submit(claim); }
        @Override public Map<Channel, Claim> flush() { return delegate.flush(); }
        @Override public void clearAllIntents() { delegate.clearAllIntents(); }
    }

    /**
     * Characterization: a body driven with continuous Y motion
     * (waterfall column), XZ held at 1.5 from the next waypoint,
     * does NOT trip the progress fuse in 100 ticks. This钉住
     * the pre-fix-4 behaviour so PR-2's diff is exactly the
     * assertion flip ("STUCK fires within K").
     */
    @Test
    void waterfallLimbodoesNotFireStuckPreFix4() {
        MockWorldView world = floorTo(60);
        // Start at (0.5, 64, 0.5). Goal at (40, 64, 0). Plan adopted
        // after the first tick; the first remaining waypoint is
        // around (1, 64, 0). The pose will sit at XZ=(0.5, 0.5)
        // (distance 0.707 from waypoint (1, 0)) - inside the
        // WAYPOINT_REACH band, so the waypoint is consumed
        // quickly. We hold the pose XZ=0.5 and let Y fall.
        Vec3[] pose = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted.
        mover.tick(world, directive, actor);

        // Drive 100 ticks of continuous Y motion = 0.3 m/tick,
        // XZ unchanged. Terminal waterfall speed is ~3.9 m/tick;
        // 0.3 m/tick is conservative and well above STUCK_EPSILON
        // (0.01) so the motion fuse keeps resetting.
        boolean stuckFired = false;
        String stuckReason = null;
        for (int i = 1; i <= 100; i++) {
            pose[0] = new Vec3(pose[0].x(),
                pose[0].y() - 0.3, pose[0].z());
            ExecutionReport r = mover.tick(world, directive, actor);
            if (r.status() == ExecutionReport.Status.STUCK) {
                stuckFired = true;
                stuckReason = "STUCK at tick " + i;
                break;
            }
        }
        assertFalse(stuckFired,
            "limbo characterization: continuous Y motion must NOT fire "
            + "STUCK pre-fix-4. (" + stuckReason + ") PR-2 will flip "
            + "this assertion once the plan-progress fuse replaces "
            + "the motion detector.");
    }

    /**
     * Sanity counterpart: a body at rest on solid ground (motion
     * < STUCK_EPSILON for STUCK_WINDOW ticks) DOES fire STUCK
     * via the existing motion fuse. Confirms the test rig is
     * wired correctly - if this fails, the rig itself is broken,
     * not the limbo scenario.
     *
     * <p>Timing: the first outer tick resets {@code ticksSinceProgress}
     * to 0 (lastProgressPose was null). Subsequent ticks of no motion
     * increment to 1, 2, ..., 20. STUCK fires on the 21st tick (i=20
     * in the loop, which is the 21st mover.tick call overall). The
     * +1 from the initial reset is a known offset; do not "fix" it.
     */
    @Test
    void restFiresStuckViaMotionFuse() {
        MockWorldView world = floorTo(60);
        Vec3[] pose = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted.
        mover.tick(world, directive, actor);

        // Hold pose still for 25 ticks. Motion < epsilon -> fuse
        // accumulates -> STUCK at tick 21 (i=20).
        int stuckTick = -1;
        for (int i = 1; i <= 25; i++) {
            ExecutionReport r = mover.tick(world, directive, actor);
            if (r.status() == ExecutionReport.Status.STUCK) {
                stuckTick = i;
                break;
            }
        }
        assertEquals(20, stuckTick,
            "rig sanity: motion < epsilon for 20 ticks must fire STUCK "
            + "at i=20 in the loop (i.e. the 21st mover.tick overall, "
            + "the +1 is the first-tick reset of ticksSinceProgress)");
    }
}
