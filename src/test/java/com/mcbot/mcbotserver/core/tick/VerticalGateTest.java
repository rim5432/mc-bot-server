package com.mcbot.mcbotserver.core.tick;

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

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vertical gate (issue 0001 fix 6): trigger evaluation
 * (plan-progress score, offPath, exhausted, fuse, replan request,
 * STUCK report) runs only while the body is in ground contact.
 * Airborne ticks skip all of it; the landing edge bypasses the
 * replan cooldown.
 *
 * <p>The plan-progress fuse from PR-2 alone would trip on a
 * waterfall body (sustained Y motion, no waypoint advance). The
 * vertical gate suppresses the fuse during the fall so the bot
 * is not declared STUCK while still in the air - the body cannot
 * be "frozen" while it is on a ballistic trajectory. The
 * landing-edge bypass then fires an immediate replan so the
 * post-landing position is the basis for the next plan, not a stale
 * pre-take-off plan.
 */
class VerticalGateTest {

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
     * A body that falls continuously (onGround=false) for 30 ticks
     * must NOT trip the plan-progress fuse: the gate suppresses
     * trigger evaluation while airborne, so the accumulator never
     * increments and STUCK never fires. Pre-fix-6 the same body
     * (default-grounded test rig) would fire STUCK at i=21. The
     * comparison is exposed in
     * {@code LimboCharacterizationGateTest.waterfallLimboFiresStuckPostFix4}
     * (default-grounded) - this test is its airborne counterpart
     * and together they pin the vertical gate.
     */
    @Test
    void airborneTicksDoNotTripFuse() throws ReflectiveOperationException {
        MockWorldView world = floorTo(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        // Always airborne.
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], () -> false, BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: wasOnGround=false, onGround=false. This is
        // NOT a landing edge (landing = onGround && !wasOnGround).
        // The gate stays engaged. PlanProgressFuse.evaluate is NOT called,
        // so ticksSincePlanProgress stays at 0 (ctor default).
        // neverPlanned=true is checked INSIDE the gated replan
        // block, so it cannot fire a replan either. The first
        // tick only increments ticksSincePlan and steers.
        mover.tick(world, directive, actor);

        // 30 ticks of free-fall, XZ held, Y decreasing 0.3 m/tick.
        // Plan-progress score NOT evaluated (gate skips it).
        // Fuse accumulator stays at 0 the whole time.
        int stuckTick = -1;
        String stuckChannel = null;
        for (int i = 1; i <= 30; i++) {
            position[0] = new Vec3(position[0].x(),
                position[0].y() - 0.3, position[0].z());
            ExecutionReport r = mover.tick(world, directive, actor);
            boolean stuck =
                r.status() == ExecutionReport.Status.STUCK
                || (r.status() == ExecutionReport.Status.FAILED
                    && "STUCK".equals(r.reason()));
            if (stuck) {
                stuckTick = i;
                stuckChannel = r.status()
                    + (r.reason() == null ? "" : "+" + r.reason());
                break;
            }
        }
        assertEquals(-1, stuckTick,
            "plan-progress fuse must not fire while the body is "
            + "airborne: trigger evaluation is gated by onGround. "
            + "Fired at i=" + stuckTick + " (" + stuckChannel + ")");

        // ticksSincePlanProgress must not have accumulated while
        // airborne: PlanProgressFuse.evaluate is gated, so the counter
        // stays at the ctor-default 0. The first tick did not
        // credit progress (gate was engaged even on tick 1).
        assertEquals(0, PathingTestAccess.ticksSincePlanProgress(mover),
            "airborne ticks must not increment ticksSincePlanProgress");
    }

    /**
     * The landing edge (false -> true) bypasses the replan
     * cooldown. Construct a state where offPath is true and
     * ticksSincePlan is in cooldown, then transition to grounded;
     * the landing tick must request a replan immediately
     * (ticksSincePlan reset to 0) rather than waiting out the
     * cooldown.
     *
     * <p>This is the central fix-6 contribution to branch 3 of
     * issue 0001 §3: a body that falls, lands 1 cell off the
     * active waypoint, and would otherwise have to wait
     * REPLAN_COOLDOWN ticks for the next replan instead gets one
     * on the landing tick.
     */
    @Test
    void landingEdgeBypassesReplanCooldown()
        throws ReflectiveOperationException {
        MockWorldView world = floorTo(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        boolean[] onGround = { true };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], () -> onGround[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Tick 1: landing edge, neverPlanned=true, replan fires.
        // Synchronous plan completes within the tick, so waypoints
        // are populated by end of tick 1 and ticksSincePlan=0.
        mover.tick(world, directive, actor);

        // Walk forward 5 ticks so we are well along the plan and
        // ticksSincePlan has advanced to 5 (still in cooldown of
        // REPLAN_COOLDOWN=10).
        for (int i = 1; i <= 5; i++) {
            position[0] = new Vec3(0.5 + i * 0.2, 64, 0.5);
            mover.tick(world, directive, actor);
        }
        assertEquals(5, PathingTestAccess.ticksSincePlan(mover),
            "5 ticks after first replan: ticksSincePlan should be 5, "
            + "in the REPLAN_COOLDOWN=10 window");

        // Now jump 5m LATERALLY off the plan line. The smoothed plan
        // is one long segment [(0,64,0),(40,64,0)], so an along-track
        // push stays on the segment and is NOT drift; a lateral push
        // puts the body outside REPLAN_DISTANCE of the walked
        // segment and offPath is true. But ticksSincePlan=5 < 10 so
        // a normal-tick replan is blocked by the cooldown.
        position[0] = new Vec3(position[0].x(), 64, position[0].z() + 5);
        mover.tick(world, directive, actor);
        // Verify: no replan fired (ticksSincePlan still 6, not 0).
        assertEquals(6, PathingTestAccess.ticksSincePlan(mover),
            "offPath with ticksSincePlan in cooldown: no replan");

        // Go airborne (jump or fall off edge). wasOnGround was true
        // (steady grounded before jump) so this is NOT a landing
        // edge. The gate suppresses trigger evaluation entirely.
        onGround[0] = false;
        mover.tick(world, directive, actor);
        assertEquals(7, PathingTestAccess.ticksSincePlan(mover),
            "airborne tick: gate skips trigger eval, ticksSincePlan "
            + "increments to 7, no replan");

        // Still airborne.
        mover.tick(world, directive, actor);
        assertEquals(8, PathingTestAccess.ticksSincePlan(mover),
            "still airborne: gate still skips trigger eval");

        // Land. onGround goes false -> true. landing = true. The
        // gate evaluates triggers AND bypasses the cooldown via
        // the landing edge: replan fires immediately,
        // ticksSincePlan reset to 0. The sync-planning path does
        // not populate pendingPlan (it sets waypoints in place),
        // so the cooldown counter reset is the only signal of
        // the request.
        onGround[0] = true;
        mover.tick(world, directive, actor);
        assertEquals(0, PathingTestAccess.ticksSincePlan(mover),
            "landing edge must request replan immediately, "
            + "bypassing the cooldown: ticksSincePlan should be 0");
    }

    /**
     * Steady-state grounded ticks evaluate triggers normally. The
     * gate is invisible to bots that stay on the ground: a body
     * resting for STUCK_WINDOW ticks without plan-progress still
     * fires STUCK, exactly as in the default-grounded tests.
     * Pins the regression boundary: fix 6 must not change
     * ground-only behavior.
     */
    @Test
    void steadyGroundedStillFiresFuseAt20()
        throws ReflectiveOperationException {
        MockWorldView world = floorTo(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        // Always grounded (same default as the 3-arg constructor).
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], () -> true, BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted.
        mover.tick(world, directive, actor);

        // Hold position still for 25 ticks. No plan-progress -> fuse
        // accumulates -> STUCK within the 21-tick bound (K=20
        // + first-observation +1 offset, see issue 0001 §7).
        int stuckTick = -1;
        for (int i = 1; i <= 25; i++) {
            ExecutionReport r = mover.tick(world, directive, actor);
            boolean stuck =
                r.status() == ExecutionReport.Status.STUCK
                || (r.status() == ExecutionReport.Status.FAILED
                    && "STUCK".equals(r.reason()));
            if (stuck) {
                stuckTick = i;
                break;
            }
        }
        assertTrue(stuckTick > 0 && stuckTick <= 21,
            "steady-grounded body must fire STUCK within 21 ticks: "
            + "fix 6 must not change ground-only behavior. "
            + "Fired at i=" + stuckTick);
    }

    /**
     * Sanity: the gate is wired through the constructor, not by
     * accident. A behavior constructed with the always-airborne
     * OnGroundSource must never enter the trigger-evaluation
     * block: no plan is ever adopted, no replan is ever requested,
     * and the plan-progress fuse is never advanced. The
     * distinguishing signal is that {@code ticksSincePlan} keeps
     * incrementing without reset to 0 (which would happen on a
     * successful replan request). The first tick is NOT a landing
     * edge (wasOnGround=false, onGround=false) and the gate stays
     * engaged, so even the {@code neverPlanned=true} short-circuit
     * cannot fire a replan in airborne mode.
     */
    @Test
    void alwaysAirborneIsolatesTriggerEvaluation()
        throws ReflectiveOperationException {
        MockWorldView world = floorTo(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], () -> false, BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // 50 ticks of free-fall. Gate engaged every tick. No
        // trigger evaluation; ticksSincePlanProgress stays 0;
        // no replan request; ticksSincePlan keeps incrementing
        // from its ctor-initial REPLAN_COOLDOWN without ever
        // resetting to 0 (which is what a successful replan
        // request would do).
        for (int i = 0; i < 50; i++) {
            position[0] = new Vec3(position[0].x(),
                position[0].y() - 0.3, position[0].z());
            mover.tick(world, directive, actor);
        }
        assertEquals(0, PathingTestAccess.ticksSincePlanProgress(mover),
            "airborne ticks must not touch ticksSincePlanProgress");

        // ticksSincePlan started at REPLAN_COOLDOWN=10, was
        // incremented 50 times, and was NEVER reset to 0 (no
        // replan was requested because the gate stayed engaged
        // and the neverPlanned short-circuit is also gated).
        // 10 + 50 = 60.
        assertEquals(60, PathingTestAccess.ticksSincePlan(mover),
            "ticksSincePlan should equal 10 (ctor) + 50 (tick "
            + "increments) with no reset to 0: the gate prevented "
            + "any replan from being requested");
    }
}
