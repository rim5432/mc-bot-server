package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.ToolCatalog;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.EngageOnHostileProximityRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Idle-combat reflex gates: the ENGAGE action spends one tick on the
 * preemption, submits a factory-built defend mission, runs the fight
 * through the mission stage without re-parking, and hands the body
 * back to the parked mission only after the fight's own verdict.
 *
 * <p>Contract: see ReflexAction#ENGAGE and boundaries.md decision
 * ledger 23. The 2026-08-24 night-cave death is the failure this
 * guards: nothing engaged a hostile without an explicit mission.
 */
// `arbiter.current() == mission` asserts seat IDENTITY on purpose: an
// equal-valued clone sitting in the arbiter would be a real bug here.
@SuppressWarnings("PMD.CompareObjectsWithEquals")
class EngageReflexGateTest {

    /** Parkable mission stub counting ticks and park/resume calls. */
    private static final class CountingMission implements BotProcess {
        int tickCalls;
        int lostControlCalls;
        int resumedCalls;
        int invalidatedCalls;
        boolean resumeAnswer = true;

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public Directive onTick(WorldView world) {
            tickCalls++;
            return Directive.of(new GoalBlock(new CellPos(9, 64, 9)));
        }

        @Override
        public void onLostControl(InterruptionContext c) {
            lostControlCalls++;
        }

        @Override
        public boolean resume(InterruptionContext c) {
            resumedCalls++;
            return resumeAnswer;
        }

        @Override
        public void onContextInvalidated() {
            invalidatedCalls++;
        }

        @Override
        public String displayName() {
            return "counting-mission";
        }
    }

    /** Factory-built fight stub: lives until told to end. */
    private static final class StubFight implements BotProcess {
        int tickCalls;
        boolean active = true;

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public int priority() {
            return 150;
        }

        @Override
        public Directive onTick(WorldView world) {
            tickCalls++;
            return Directive.of(new GoalBlock(new CellPos(0, 64, 0)));
        }

        @Override
        public void onLostControl(InterruptionContext c) {}

        @Override
        public boolean resume(InterruptionContext c) {
            return active;
        }

        @Override
        public void onContextInvalidated() {}

        @Override
        public String displayName() {
            return "stub-fight";
        }
    }

    private static final CellPos POS = new CellPos(0, 64, 0);

    /** Sensor stub: threat presence and distance are test inputs. */
    private static final class ThreatInput {
        EntitySnapshot threat;
        double distance = Double.MAX_VALUE;

        void none() {
            threat = null;
            distance = Double.MAX_VALUE;
        }

        void at(double d) {
            threat = new EntitySnapshot("z-1", "minecraft:zombie", new CellPos((int) d, 64, 0), 20f, 20f);
            distance = d;
        }
    }

    private static final class Rig {
        final ThreatInput threat = new ThreatInput();
        /** Controllable health so FREEZE can be scripted against ENGAGE. */
        final float[] health = {20f};

        final TaskArbiter arbiter = new TaskArbiter();
        final CountingMission mission = new CountingMission();
        final StubFight[] fights = new StubFight[1];
        int factoryCalls;
        private final BotController controller;
        private final InMemoryEventQueue events;
        private final RecordingActor recordingActor;

        Rig() {
            this(true);
        }

        Rig(boolean withFactory) {
            SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
                board.nearestThreat = threat.threat;
                board.nearestThreatDistance = threat.distance;
            });
            layer.addRule(new com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule());
            layer.addRule(new EngageOnHostileProximityRule());
            arbiter.register(mission);
            arbiter.requestControl(mission);
            Behavior mover = new PathingBehavior(
                    "mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
            Supplier<BotProcess> factory = withFactory
                    ? () -> {
                        factoryCalls++;
                        return fights[0] = new StubFight();
                    }
                    : null;
            recordingActor = withFactory ? null : new RecordingActor();
            Actor actor = withFactory ? new SinkActor() : recordingActor;
            events = new InMemoryEventQueue(() -> 1L, () -> 0L);
            controller = new BotController(
                    layer,
                    arbiter,
                    List.of(mover),
                    actor,
                    () -> POS,
                    () -> health[0],
                    new BotController.GameClock() {
                        @Override
                        public long day() {
                            return 1L;
                        }

                        @Override
                        public long timeOfDayTicks() {
                            return 6000L;
                        }
                    },
                    events,
                    ctx -> {},
                    ToolCatalog.none(),
                    factory,
                    null,
                    null);
        }

        void tick() {
            controller.onTick(new MockWorldView());
        }

        List<com.mcbot.mcbotserver.api.event.BotEvent> eventsOf(String kind) {
            return events.statusSnapshot(0L).events().stream()
                    .filter(e -> kind.equals(e.kind()))
                    .toList();
        }
    }

    /**
     * The full arc: threat closes -> mission parked, factory-built
     * fight submitted and seated next tick -> fight ticks WITHOUT a
     * second park -> threat gone -> fight retires -> parked mission
     * resumes and the fight's verdict is announced.
     */
    @Test
    void engageArcParksSubmitsFightsAndResumes() {
        Rig rig = new Rig();
        rig.tick(); // quiet tick: the goto mission takes the seat
        assertEquals(1, rig.mission.tickCalls);
        rig.threat.at(5.0);
        rig.tick();
        assertEquals(1, rig.factoryCalls, "one engage submission on the first firing tick");
        assertNotNull(rig.arbiter.paused(), "the running mission must be parked for the fight");
        assertEquals(1, rig.mission.lostControlCalls);
        assertEquals(1, rig.eventsOf(EventKind.TASK_PAUSED).size());

        // Fight runs through the mission stage; no re-park churn.
        for (int i = 0; i < 3; i++) {
            rig.tick();
        }
        assertEquals(1, rig.factoryCalls, "a live reflex fight must not mint missions per tick");
        assertSame(rig.fights[0], rig.arbiter.current(), "the factory-built fight holds the body");
        assertTrue(rig.fights[0].tickCalls >= 3, "the fight ticks through the mission stage");
        assertEquals(1, rig.mission.lostControlCalls, "no further preemption while the fight lives");
        assertNotNull(rig.arbiter.paused(), "parked mission stays parked while the fight runs");

        // Threat gone: the rule holds its decision through the
        // hysteresis window (~20 ticks), then the fight retires and
        // the parked mission resumes. Drive the whole tail.
        rig.threat.none();
        rig.fights[0].active = false;
        boolean resumed = false;
        for (int i = 0; i < 60 && !resumed; i++) {
            rig.tick();
            resumed = rig.arbiter.current() == rig.mission;
        }
        assertTrue(resumed, "the parked mission must resume once the rule quiets");
        assertNull(rig.arbiter.paused());
        assertTrue(rig.mission.resumedCalls >= 1);
        assertTrue(rig.eventsOf(EventKind.TASK_RESUMED).size() >= 1);
        assertEquals(1, rig.factoryCalls, "the engagement tail must not mint a spurious defend");
    }

    /**
     * The one-tick window hazard: on the tick right after submission
     * the fight is not seated yet - the resume path must NOT hand
     * the body back to the parked mission before the fight starts.
     */
    @Test
    void submissionNextTickDoesNotResumeTheParkedMission() {
        Rig rig = new Rig();
        rig.tick(); // quiet first tick seats the goto mission
        assertEquals(1, rig.mission.tickCalls);

        rig.threat.at(5.0);
        rig.tick(); // submission tick
        rig.tick(); // the hazard tick: fight not yet current
        assertSame(rig.fights[0], rig.arbiter.current(), "the fight must win selection, not the parked mission");
        assertTrue(rig.mission.resumedCalls == 0, "resume must stay blocked while any reflex fires");
    }

    /**
     * A threat hovering inside the release band (12-14) with the
     * fight already dead must not mint a mission per tick: the
     * resubmit cooldown owns that window.
     */
    @Test
    void resubmitCooldownAbsorbsReleaseBandChurn() {
        Rig rig = new Rig();
        rig.threat.at(5.0);
        rig.tick();
        assertEquals(1, rig.factoryCalls);
        rig.fights[0].active = false;
        // Threat flees to the release band; rule still fires there.
        rig.threat.at(13.0);
        rig.tick(); // head sweep retires the fight
        for (int i = 0; i < 5; i++) {
            rig.tick();
        }
        assertEquals(1, rig.factoryCalls, "cooldown must block a fresh mission per tick");
    }

    /**
     * Review Bug 1: FREEZE firing inside the submission's one-tick
     * window (creeper-class damage spike the tick after submission,
     * fight still in pending) must not strand the fight - after the
     * freeze holds, the ARBITER selects the fight, the parked mission
     * does not resume over it, and the combat reflex stays armed.
     */
    @Test
    void freezeInSubmissionWindowDoesNotStrandTheFight() {
        Rig rig = new Rig();
        rig.tick(); // quiet: mission seats
        rig.threat.at(5.0);
        rig.tick(); // submission: mission parked, fight pending
        assertEquals(1, rig.factoryCalls);

        rig.health[0] = 3f; // FREEZE (100) outranks ENGAGE (90)
        rig.tick();
        assertNull(rig.arbiter.current(), "freeze with nothing seated parks no one");
        assertNotNull(rig.arbiter.paused(), "the original stays parked through the freeze");

        // Freeze releases; the threat is still there, so ENGAGE
        // keeps firing - the fight must win the seat, not the parked
        // mission (the pre-fix code resumed the mission here and
        // blocked all combat reflex until it finished).
        rig.health[0] = 20f;
        for (int i = 0; i <= com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            rig.tick();
        }
        assertSame(rig.fights[0], rig.arbiter.current(), "the fight must be selected once the freeze lifts");
        assertEquals(1, rig.factoryCalls, "no resubmission while the live fight exists");
        assertTrue(rig.mission.resumedCalls == 0, "the parked mission must not resume over the fight");
    }

    /**
     * The orphan sibling (found in this review pass): FREEZE parking
     * the SEATED fight must not silently orphan the mission parked by
     * the engage submission. The single paused slot evicts the
     * original through revalidate-and-requeue; the freeze release
     * resumes the FIGHT (even while ENGAGE keeps firing - the threat
     * is present); the fight's verdict then hands the body back to
     * the requeued original.
     */
    @Test
    void freezeMidFightRequeuesOriginalAndResumesFight() {
        Rig rig = new Rig();
        rig.tick();
        rig.threat.at(5.0);
        rig.tick(); // submission
        rig.tick(); // fight seats
        assertSame(rig.fights[0], rig.arbiter.current());

        rig.health[0] = 3f;
        rig.tick(); // FREEZE parks the fight
        assertSame(rig.fights[0], rig.arbiter.paused(), "the fight itself is now the parked one");
        assertTrue(rig.mission.resumedCalls >= 1, "the evicted original must revalidate through resume()");
        assertTrue(rig.mission.invalidatedCalls == 0, "a revalidating original requeues, never drops");

        // Freeze lifts but the threat persists: the resume guard must
        // hand the body back to the FIGHT despite ENGAGE firing -
        // gating on decision==null would seat the original and strand
        // the fight in the paused slot while the zombie keeps chewing.
        rig.health[0] = 20f;
        boolean fightResumed = false;
        for (int i = 0; i < 30 && !fightResumed; i++) {
            rig.tick();
            fightResumed = rig.arbiter.current() == rig.fights[0];
        }
        assertTrue(fightResumed, "the fight must resume when the freeze lifts");
        assertTrue(rig.fights[0].tickCalls >= 1);

        // Fight resolves; the requeued original must NOT be orphaned.
        int ticksBefore = rig.mission.tickCalls;
        rig.threat.none();
        rig.fights[0].active = false;
        boolean originalSeated = false;
        for (int i = 0; i < 60 && !originalSeated; i++) {
            rig.tick();
            originalSeated = rig.arbiter.current() == rig.mission;
        }
        assertTrue(originalSeated, "the requeued original must run again after the fight");
        assertTrue(rig.mission.tickCalls > ticksBefore);
    }

    /**
     * An invalidated original (resume refuses) drops honestly: the
     * eviction path emits TASK_DROPPED instead of silently leaking
     * the mission.
     */
    @Test
    void invalidatedOriginalDropsWithEventOnMidFightFreeze() {
        Rig rig = new Rig();
        rig.tick();
        rig.threat.at(5.0);
        rig.tick();
        rig.tick();
        rig.mission.resumeAnswer = false;
        rig.health[0] = 3f;
        rig.tick();
        assertTrue(rig.mission.invalidatedCalls >= 1, "a refusing original must be dropped via onContextInvalidated");
        assertTrue(rig.eventsOf(EventKind.TASK_DROPPED).size() >= 1, "the drop must reach the harness as TASK_DROPPED");
        assertSame(rig.fights[0], rig.arbiter.paused(), "the fight occupies the paused slot after the eviction");
    }

    /**
     * The cooldown gates churn, not recovery: once a fight finished
     * and the cooldown elapsed, a still-present threat legitimately
     * mints the next fight.
     */
    @Test
    void cooldownExpiryAllowsLegitimateResubmission() {
        Rig rig = new Rig();
        rig.threat.at(5.0);
        rig.tick();
        assertEquals(1, rig.factoryCalls);
        rig.fights[0].active = false;
        // Threat stays: rule keeps firing; cooldown counts up from 0.
        for (int i = 0; i < 45; i++) {
            rig.tick();
        }
        assertEquals(2, rig.factoryCalls, "after ENGAGE_RESUBMIT_COOLDOWN a live threat re-engages");
    }

    /**
     * No factory (rigs without combat wiring): ENGAGE degrades to the
     * freeze hold - park the mission, halt the body, never crash and
     * never fight blind.
     */
    @Test
    void engageWithoutFactoryDegradesToFreezeHold() {
        Rig rig = new Rig(false);
        RecordingActor actor = rig.recordingActor;
        rig.tick(); // mission seats
        rig.threat.at(5.0);
        rig.tick();
        assertNotNull(rig.arbiter.paused(), "degraded engage still parks the mission");
        assertNull(rig.arbiter.current());
        assertNull(rig.fights[0]);
        Claim move = lastMoveOf(actor);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"), "the hold claim comes from the reflex");
        assertTrue(
                move.intent() instanceof com.mcbot.mcbotserver.api.actor.Intent.Move hold
                        && !hold.jump()
                        && hold.forward() == 0,
                "the degraded hold is a plain halt");
    }

    private static Claim lastMoveOf(RecordingActor actor) {
        Claim found = null;
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.MOVE) {
                found = c;
            }
        }
        return found;
    }

    /** Claim sink; the gates never assert on claim content. */
    private static final class SinkActor implements Actor {

        @Override
        public void submit(Claim claim) {}

        @Override
        public java.util.Map<Channel, Claim> flush() {
            return java.util.Map.of();
        }

        @Override
        public void clearAllIntents() {}
    }
}
