package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.EngageOnHostileProximityRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class EngageReflexGateTest {

    /** Parkable mission stub counting ticks and park/resume calls. */
    private static final class CountingMission implements BotProcess {
        int tickCalls;
        int lostControlCalls;
        int resumedCalls;

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
            return true;
        }

        @Override
        public void onContextInvalidated() {
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
        public void onLostControl(InterruptionContext c) {
        }

        @Override
        public boolean resume(InterruptionContext c) {
            return active;
        }

        @Override
        public void onContextInvalidated() {
        }

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
            threat = new EntitySnapshot("z-1", "minecraft:zombie",
                new CellPos((int) d, 64, 0), 20f, 20f);
            distance = d;
        }
    }

    private static final class Rig {
        final ThreatInput threat = new ThreatInput();
        final TaskArbiter arbiter = new TaskArbiter();
        final CountingMission mission = new CountingMission();
        final StubFight[] fights = new StubFight[1];
        int factoryCalls;
        private final BotController controller;
        private final InMemoryEventQueue events;

        Rig() {
            SurvivalReflexLayer layer = new SurvivalReflexLayer(
                (world, board) -> {
                    board.nearestThreat = threat.threat;
                    board.nearestThreatDistance = threat.distance;
                });
            layer.addRule(new EngageOnHostileProximityRule());
            arbiter.register(mission);
            arbiter.requestControl(mission);
            Behavior mover = new PathingBehavior("mover",
                () -> new com.mcbot.mcbotserver.api.types.Vec3(
                    0.5, 64, 0.5),
                BasicMoves::from);
            Supplier<BotProcess> factory = () -> {
                factoryCalls++;
                return fights[0] = new StubFight();
            };
            events = new InMemoryEventQueue(() -> 1L, () -> 0L);
            controller = new BotController(layer, arbiter,
                List.of(mover), new SinkActor(),
                () -> POS, () -> 20f,
                new BotController.GameClock() {
                    @Override
                    public long day() {
                        return 1L;
                    }

                    @Override
                    public long timeOfDayTicks() {
                        return 6000L;
                    }
                }, events, ctx -> {
                }, factory);
        }

        void tick() {
            controller.onTick(new MockWorldView());
        }

        List<com.mcbot.mcbotserver.api.event.BotEvent> eventsOf(
                String kind) {
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
        rig.tick();  // quiet tick: the goto mission takes the seat
        assertEquals(1, rig.mission.tickCalls);
        rig.threat.at(5.0);
        rig.tick();
        assertEquals(1, rig.factoryCalls,
            "one engage submission on the first firing tick");
        assertNotNull(rig.arbiter.paused(),
            "the running mission must be parked for the fight");
        assertEquals(1, rig.mission.lostControlCalls);
        assertEquals(1, rig.eventsOf(EventKind.TASK_PAUSED).size());

        // Fight runs through the mission stage; no re-park churn.
        for (int i = 0; i < 3; i++) {
            rig.tick();
        }
        assertEquals(1, rig.factoryCalls,
            "a live reflex fight must not mint missions per tick");
        assertSame(rig.fights[0], rig.arbiter.current(),
            "the factory-built fight holds the body");
        assertTrue(rig.fights[0].tickCalls >= 3,
            "the fight ticks through the mission stage");
        assertEquals(1, rig.mission.lostControlCalls,
            "no further preemption while the fight lives");
        assertNotNull(rig.arbiter.paused(),
            "parked mission stays parked while the fight runs");

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
        assertTrue(resumed,
            "the parked mission must resume once the rule quiets");
        assertNull(rig.arbiter.paused());
        assertTrue(rig.mission.resumedCalls >= 1);
        assertTrue(rig.eventsOf(EventKind.TASK_RESUMED).size() >= 1);
        assertEquals(1, rig.factoryCalls,
            "the engagement tail must not mint a spurious defend");
    }

    /**
     * The one-tick window hazard: on the tick right after submission
     * the fight is not seated yet - the resume path must NOT hand
     * the body back to the parked mission before the fight starts.
     */
    @Test
    void submissionNextTickDoesNotResumeTheParkedMission() {
        Rig rig = new Rig();
        rig.tick();  // quiet first tick seats the goto mission
        assertEquals(1, rig.mission.tickCalls);

        rig.threat.at(5.0);
        rig.tick();  // submission tick
        rig.tick();  // the hazard tick: fight not yet current
        assertSame(rig.fights[0], rig.arbiter.current(),
            "the fight must win selection, not the parked mission");
        assertTrue(rig.mission.resumedCalls == 0,
            "resume must stay blocked while any reflex fires");
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
        rig.tick();  // head sweep retires the fight
        for (int i = 0; i < 5; i++) {
            rig.tick();
        }
        assertEquals(1, rig.factoryCalls,
            "cooldown must block a fresh mission per tick");
    }

    /** Claim sink; the gates never assert on claim content. */
    private static final class SinkActor implements Actor {

        @Override
        public void submit(Claim claim) {
        }

        @Override
        public java.util.Map<Channel, Claim> flush() {
            return java.util.Map.of();
        }

        @Override
        public void clearAllIntents() {
        }
    }
}
