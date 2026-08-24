package com.mcbot.mcbotserver.combat;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.CombatBehavior;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-2 combat skeleton gate: the planner engages the nearest
 * hostile with an Attack order and decides terminal states from its
 * own scans; the behavior aims and paces swings but never claims MOVE,
 * and directives without a combat order pass through untouched.
 *
 * <p>Contract: see boundaries.md decision 11 and decision 10.
 */
class CombatSkeletonGateTest {

    private static final Set<String> HOSTILES = Set.of(
        "minecraft:zombie");

    private static EntitySnapshot zombie(String id, CellPos pos) {
        return new EntitySnapshot(id, "minecraft:zombie", pos,
            20f, 20f);
    }

    /**
     * A hostile inside the engage radius produces a chase directive
     * carrying GoalNear to its cell plus an Attack order.
     */
    @Test
    void engagementOrdersAttackOnNearestHostile() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z-far",
            new CellPos(6, 64, 0)));
        world.addEntity(zombie("z-near",
            new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);

        Directive directive = defend.onTick(world);

        assertEquals(60, defend.priority());
        assertTrue(defend.isActive());
        assertEquals(new GoalNear(new CellPos(2, 64, 0), 2),
            directive.goal(),
            "chase anchors on the NEAREST hostile at standoff range");
        assertEquals("z-near",
            ((Attack) directive.overrides().combat()).targetId());
    }

    /** No hostile at submission time: the mission completes at once. */
    @Test
    void clearAreaCompletesImmediately() {
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);

        defend.onTick(new MockWorldView());

        assertFalse(defend.isActive());
        assertTrue(defend.missionSucceeded());
        assertNull(defend.failureReasonOrNull());
    }

    /** Target vanishing starts the grace; staying gone ends as ESCAPED. */
    @Test
    void vanishedTargetCountsAsEscapedAfterGrace() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);
        world.removeEntity("z1");

        for (int i = 0; i <= DefendProcess.TARGET_GRACE_TICKS; i++) {
            defend.onTick(world);
        }

        assertFalse(defend.isActive());
        assertFalse(defend.missionSucceeded(),
            "a target absent past grace is ESCAPED, not neutralized");
        assertEquals(DefendProcess.REASON_ESCAPED,
            defend.failureReasonOrNull());
    }

    /** An engaged target beyond the leash escapes: FAILED, not chase. */
    @Test
    void leashedTargetEscapesAsFailure() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);

        // The body did not move but the target teleported away.
        world.removeEntity("z1");
        world.addEntity(zombie("z1",
            new CellPos((int) Math.ceil(
                DefendProcess.LEASH_RADIUS) + 2, 64, 0)));
        defend.onTick(world);

        assertFalse(defend.isActive());
        assertFalse(defend.missionSucceeded());
        assertEquals(DefendProcess.REASON_LOST,
            defend.failureReasonOrNull());
    }

    /**
     * Resume after a freeze must NOT blindly trust the pre-pause
     * target: grace credit is spent at resume, so the very next scan
     * adjudicates - a vanished target ends the fight immediately
     * as TARGET_ESCAPED instead of chasing a ghost until timeout.
     * The bot cannot distinguish death from escape, so the conservative
     * failure verdict is reported (issue 0006).
     */
    @Test
    void resumeSpendsGraceCreditForImmediateAdjudication() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);
        assertTrue(defend.isActive());

        // Frozen mid-fight; the target dies while we are parked.
        defend.onLostControl(null);
        world.removeEntity("z1");

        assertTrue(defend.resume(null),
            "an engaged mission mechanically reattaches");
        defend.onTick(world);

        assertFalse(defend.isActive(),
            "the first post-resume scan must decide");
        assertFalse(defend.missionSucceeded(),
            "a target absent at reattach is TARGET_ESCAPED, not a chase");
        assertEquals(DefendProcess.REASON_ESCAPED,
            defend.failureReasonOrNull());
    }

    /**
     * Edge jitter must not starve the swing cadence: with the target
     * oscillating just past the reach gate, the hold window bridges
     * out-of-range ticks so cooldown expiries still fire; a target
     * PERMANENTLY past the gate earns nothing.
     */
    @Test
    void edgeJitterBridgedByHoldButPermanentEscapeIsNot() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        CombatBehavior combat = new CombatBehavior("combat",
            () -> position[0]);
        RecordingActor actor = new RecordingActor();
        Directive directive = fightOrder(new CellPos(2, 64, 0));

        // Aim point sits at x=2.5, y=65: matching position.y makes reach
        // purely horizontal, so position.x tunes it exactly.
        // in-reach: x = 2.5 - 2.9 = -0.4; out: x = 2.5 - 3.15 = -0.65
        Vec3 inReach = new Vec3(-0.4, 64.0 + 1.0, 0.5);
        Vec3 outReach = new Vec3(-0.65, 64.0 + 1.0, 0.5);

        int swings = 0;
        for (int t = 0; t < 40; t++) {
            position[0] = (t % 10 < 2) ? inReach : outReach;
            int before = actor.submitted.size();
            combat.tick(emptyWorld(), directive, actor);
            boolean pressed = actor.submitted.subList(before,
                actor.submitted.size()).stream()
                .anyMatch(c -> c.channel() == Channel.USE
                    && ((com.mcbot.mcbotserver.api.actor.Intent.Use)
                        c.intent()).pressing());
            if (pressed) {
                swings++;
            }
        }
        assertTrue(swings >= 3,
            "hold window must bridge edge jitter: got " + swings);

        // Control: permanently outside earns nothing, hold expires.
        RecordingActor farActor = new RecordingActor();
        CombatBehavior idle = new CombatBehavior("combat",
            () -> new Vec3(-0.65, 65.0, 0.5));
        for (int t = 0; t < 40; t++) {
            idle.tick(emptyWorld(), directive, farActor);
        }
        assertTrue(farActor.submitted.stream().noneMatch(
                c -> c.channel() == Channel.USE
                    && ((com.mcbot.mcbotserver.api.actor.Intent.Use)
                        c.intent()).pressing()),
            "a permanently escaped target gets no swings");
    }

    /**
     * Mirror case: the target is STILL THERE at reattach. The spent
     * grace credit must be refilled by the scan (seen -> counter
     * reset) - the fight continues normally instead of the first tick
     * unconditionally declaring TARGET_DOWN.
     */
    @Test
    void resumeWithTargetPresentContinuesTheFight() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);
        defend.onLostControl(null);

        assertTrue(defend.resume(null));
        Directive directive = defend.onTick(world);

        assertTrue(defend.isActive(),
            "a still-present target keeps the engagement alive");
        assertEquals("z1",
            ((Attack) directive.overrides().combat()).targetId());
    }

    /**
     * A ranged hostile (kite-and-shoot tactics) is REFUSED at engage
     * time - terminal immediately, zero chase claims, structured
     * attrs carrying the refused type for the harness's meta-strategy.
     */
    @Test
    void rangedHostileIsRefusedInsteadOfChased() {
        MockWorldView world = new MockWorldView();
        EntitySnapshot skeleton = new EntitySnapshot("s1",
            "minecraft:skeleton", new CellPos(4, 64, 0), 20f, 20f);
        world.addEntity(skeleton);
        // The scan must SEE the skeleton as hostile for the refusal
        // branch to fire - refusal is a planner decision on a visible
        // threat, not a scanning blind spot.
        Set<String> hostiles = Set.of("minecraft:zombie",
            "minecraft:skeleton");
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), hostiles,
            Set.of("minecraft:skeleton"));
        RecordingActor actor = new RecordingActor();

        Directive directive = defend.onTick(world);

        assertFalse(defend.isActive(),
            "refusal is immediate - no bleeding chase");
        assertFalse(defend.missionSucceeded());
        assertEquals(DefendProcess.REASON_REFUSED,
            defend.failureReasonOrNull());
        assertEquals("minecraft:skeleton",
            defend.verdictAttrs().get("threatType"));
        assertTrue(actor.submitted.isEmpty(),
            "refusal must not move a muscle");
    }

    /** Melee hostiles engage exactly as before the ranged set landed. */
    @Test
    void meleeEngagementUnaffectedByRangedSet() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES,
            Set.of("minecraft:skeleton"));

        Directive directive = defend.onTick(world);

        assertTrue(defend.isActive());
        assertEquals("z1",
            ((Attack) directive.overrides().combat()).targetId());
    }

    /**
     * Reports never decide the fight: neither a locomotion FAILED nor
     * a chase SUCCESS may flip terminal state - scans, leash, and
     * timeout own the verdict.
     */
    @Test
    void reportsNeverDecideTheFight() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);
        defend.onExecutionReport(
            ExecutionReport.failed("STUCK"));
        defend.onExecutionReport(ExecutionReport.success());

        assertTrue(defend.isActive(),
            "execution weather must not end the engagement");
        assertNull(defend.failureReasonOrNull(),
            "no verdict was reached");
    }

    /**
     * Reaching the chase goal is where the fight starts: a SUCCEEDED
     * locomotion report must never end the mission - victory belongs
     * to the scans alone.
     */
    @Test
    void locomotionSuccessDoesNotEndTheFight() {
        MockWorldView world = new MockWorldView();
        world.addEntity(zombie("z1", new CellPos(2, 64, 0)));
        DefendProcess defend = new DefendProcess("gt-def", 60, 400,
            () -> new CellPos(0, 64, 0), HOSTILES);
        defend.onTick(world);

        defend.onExecutionReport(ExecutionReport.success());

        assertTrue(defend.isActive(),
            "standing next to the enemy is not victory");
        Directive directive = defend.onTick(world);
        assertEquals("z1",
            ((Attack) directive.overrides().combat()).targetId(),
            "the fight continues on the same target");
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

    private static Directive fightOrder(CellPos target) {
        return new Directive(new GoalNear(target, 1),
            new com.mcbot.mcbotserver.api.process.Overrides(
                new Attack("z1")));
    }

    private static WorldView emptyWorld() {
        return new MockWorldView();
    }

    /**
     * In reach, the behavior aims (ROT) every tick and swings (USE)
     * exactly once per cooldown window - one rising edge, never held.
     */
    @Test
    void inReachAimsEveryTickAndSwingsOncePerWindow() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        CombatBehavior combat = new CombatBehavior("combat",
            () -> position[0]);
        RecordingActor actor = new RecordingActor();
        Directive directive = fightOrder(new CellPos(2, 64, 0));

        int swings = 0;
        for (int i = 0; i <= CombatBehavior.ATTACK_COOLDOWN_TICKS; i++) {
            int before = actor.submitted.size();
            combat.tick(emptyWorld(), directive, actor);
            var window = actor.submitted.subList(before,
                actor.submitted.size());
            boolean aimed = window.stream()
                .anyMatch(c -> c.channel() == Channel.ROT);
            boolean useNow = window.stream()
                .anyMatch(c -> c.channel() == Channel.USE
                    && ((com.mcbot.mcbotserver.api.actor.Intent.Use)
                        c.intent()).pressing());
            assertTrue(aimed, "aim must refresh every tick");
            if (useNow) {
                swings++;
            }
        }
        assertEquals(2, swings,
            "one swing at start, one after the cooldown window");

        // And never any movement claim - locomotion is not ours.
        assertTrue(actor.submitted.stream()
            .noneMatch(c -> c.channel() == Channel.MOVE));
    }

    /** Out of reach the behavior keeps aiming but holds fire. */
    @Test
    void outOfReachHoldsFireWhileAiming() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        CombatBehavior combat = new CombatBehavior("combat",
            () -> position[0]);
        RecordingActor actor = new RecordingActor();
        Directive directive = fightOrder(new CellPos(30, 64, 0));

        for (int i = 0; i < 15; i++) {
            combat.tick(emptyWorld(), directive, actor);
        }

        assertTrue(actor.submitted.stream()
            .anyMatch(c -> c.channel() == Channel.ROT));
        assertTrue(actor.submitted.stream()
            .noneMatch(c -> c.channel() == Channel.USE),
            "no blind swinging at unreachable targets");
    }

    /** Locomotive directives pass through without any claims. */
    @Test
    void nonCombatDirectivesAreInvisible() {
        CombatBehavior combat = new CombatBehavior("combat",
            () -> new Vec3(0.5, 64, 0.5));
        RecordingActor actor = new RecordingActor();

        ExecutionReport report = combat.tick(emptyWorld(),
            Directive.of(new GoalNear(new CellPos(4, 64, 4), 1)),
            actor);
        combat.tick(emptyWorld(), null, actor);

        assertEquals(ExecutionReport.Status.RUNNING, report.status());
        assertTrue(actor.submitted.isEmpty(),
            "goto traffic must feel nothing from combat existing");
    }
}
