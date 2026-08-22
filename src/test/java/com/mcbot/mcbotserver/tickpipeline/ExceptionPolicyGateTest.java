package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.MinimalReflex;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-0 exception gate: ADR-0005's full state machine — a
 * RuntimeException from any stage latches the crash, clears intents,
 * reports through both channels, degrades to MinimalReflex only, and
 * recovers asymmetrically (reset vs respawn).
 *
 * <p>Contract: see ADR-0005 D1-D5.
 */
class ExceptionPolicyGateTest {

    /** Delegating actor that records every submission this tick. */
    private static final class RecordingActor implements Actor {
        private final com.mcbot.mcbotserver.core.actor.ChannelArbiter
            delegate = new com.mcbot.mcbotserver.core.actor.ChannelArbiter();
        final List<Claim> submitted = new ArrayList<>();
        boolean throwOnFlush;

        @Override
        public void submit(Claim claim) {
            submitted.add(claim);
            delegate.submit(claim);
        }

        @Override
        public Map<Channel, Claim> flush() {
            if (throwOnFlush) {
                throw new IllegalStateException("flush boom");
            }
            return delegate.flush();
        }

        @Override
        public void clearAllIntents() {
            submitted.clear();
            delegate.clearAllIntents();
        }
    }

    /** Behavior that throws once armed; counts executions otherwise. */
    private static final class ThrowingBehavior implements Behavior {
        private boolean armed;
        private int executions;

        @Override
        public ExecutionReport tick(WorldView world,
                                    Directive directive, Actor actor) {
            if (directive != null) {
                executions++;
            }
            if (armed) {
                throw new IllegalStateException("boom");
            }
            return ExecutionReport.running();
        }

        @Override
        public String name() {
            return "thrower";
        }
    }

    /** Mission stub counting ticks for the degraded-mode assertion. */
    private static final class QuietMission implements BotProcess {
        private int tickCalls;

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
            return Directive.of(new GoalBlock(new CellPos(1, 64, 1)));
        }

        @Override
        public void onLostControl(InterruptionContext context) {
        }

        @Override
        public boolean resume(InterruptionContext context) {
            return true;
        }

        @Override
        public void onContextInvalidated() {
        }

        @Override
        public String displayName() {
            return "quiet-mission";
        }
    }

    /** Everything the scenarios need, pre-wired. */
    private static final class Harness {
        final float[] health = {20f};
        final InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 4L, () -> 7000L);
        final List<InterruptionContext> fallbackCalls = new ArrayList<>();
        final RecordingActor actor = new RecordingActor();
        final TaskArbiter arbiter = new TaskArbiter();
        final QuietMission mission = new QuietMission();
        final ThrowingBehavior behavior = new ThrowingBehavior();
        final BotController controller;

        Harness(boolean throwingSensor) {
            SurvivalReflexLayer layer = new SurvivalReflexLayer(
                throwingSensor
                    ? (world, board) -> {
                        throw new IllegalStateException("sensor boom");
                    }
                    : (world, board) -> board.botHealth = health[0]);
            controller = new BotController(layer, arbiter,
                List.of(behavior), actor, () -> new CellPos(0, 64, 0),
                () -> health[0],
                new BotController.GameClock() {
                    @Override
                    public long day() {
                        return 4L;
                    }

                    @Override
                    public long timeOfDayTicks() {
                        return 7000L;
                    }
                },
                events,
                fallbackCalls::add);
            arbiter.register(mission);
            arbiter.requestControl(mission);
        }

        List<BotEvent> crashEvents() {
            return events.statusSnapshot(0).events().stream()
                .filter(e -> EventKind.BOT_CRASHED.equals(e.kind()))
                .toList();
        }
    }

    /**
     * D2: first RuntimeException -> latch set, intents cleared, both
     * channels fired, nothing rethrown to the caller.
     */
    @Test
    void crashLatchesClearsIntentsAndReportsBothChannels() {
        Harness h = new Harness(false);
        h.arbiter.tick(new MockWorldView());
        h.behavior.armed = true;

        h.controller.onTick(new MockWorldView());

        assertTrue(h.controller.isCrashed(), "latch must be sticky");
        assertEquals(1, h.controller.crashCounter());

        List<BotEvent> crashes = h.crashEvents();
        assertEquals(1, crashes.size(),
            "primary channel must carry exactly one crash event");
        assertTrue(crashes.get(0).urgent(),
            "a crash is decision-critical freshness by definition");
        assertEquals("IllegalStateException:boom",
            crashes.get(0).attrs().get("cause"));

        assertEquals(1, h.fallbackCalls.size(),
            "fallback channel must fire even when primary succeeded");
        assertEquals("IllegalStateException:boom",
            h.fallbackCalls.get(0).causeSummary());
    }

    /**
     * D3: while latched, every tick runs MinimalReflex only — mission
     * and behaviors never execute again until recovery.
     */
    @Test
    void crashedStateRunsMinimalReflexOnly() {
        Harness h = new Harness(false);
        h.arbiter.tick(new MockWorldView());
        h.behavior.armed = true;
        h.controller.onTick(new MockWorldView());
        int behaviorExecs = h.behavior.executions;
        int missionTicks = h.mission.tickCalls;

        h.controller.onTick(new MockWorldView());
        h.controller.onTick(new MockWorldView());

        assertEquals(behaviorExecs, h.behavior.executions,
            "behaviors are dead while latched");
        assertEquals(missionTicks, h.mission.tickCalls,
            "mission tier is dead while latched");

        Claim lastMove = null;
        for (Claim c : h.actor.submitted) {
            if (c.channel() == Channel.MOVE) {
                lastMove = c;
            }
        }
        assertNotNull(lastMove);
        assertEquals(MinimalReflex.MINIMAL_PRIORITY, lastMove.priority());
        assertEquals("minimal-reflex", lastMove.holder());
    }

    /** D5a vs D5b: reset wipes the counter; respawn preserves it. */
    @Test
    void resetClearsCounterButRespawnPreservesIt() {
        Harness h = new Harness(true);

        h.controller.onTick(new MockWorldView());
        assertTrue(h.controller.isCrashed());
        assertEquals(1, h.controller.crashCounter());

        h.controller.onRespawned();
        assertFalse(h.controller.isCrashed(), "respawn clears the latch");
        assertEquals(1, h.controller.crashCounter(),
            "respawn must NOT clear the counter");

        h.controller.onTick(new MockWorldView());
        assertTrue(h.controller.isCrashed());
        assertEquals(2, h.controller.crashCounter(),
            "second crash increments across respawns");

        h.controller.reset();
        assertFalse(h.controller.isCrashed());
        assertEquals(0, h.controller.crashCounter(),
            "harness reset is the full wipe");
    }

    /** The catch frame covers stage 1 too — a poisoned sensor latches. */
    @Test
    void sensorCrashIsContainedTheSameWay() {
        Harness h = new Harness(true);

        h.controller.onTick(new MockWorldView());

        assertTrue(h.controller.isCrashed());
        assertEquals(1, h.controller.crashCounter());
        assertEquals(1, h.crashEvents().size());
        assertEquals(1, h.fallbackCalls.size());
        assertTrue(h.fallbackCalls.get(0).causeSummary()
                .contains("sensor boom"),
            "crash snapshot must name the original cause");
    }

    /**
     * S2 gate: ADR-0005 D3's literal promise — if MinimalReflex itself
     * throws while latched, the SAME latch fires again and both
     * channels re-report, and the exception still never escapes to the
     * caller (which would mean the MC main thread).
     */
    @Test
    void minimalReflexThrowKeepsLatchedAndReReports() {
        Harness h = new Harness(false);
        h.arbiter.tick(new MockWorldView());
        h.behavior.armed = true;
        h.controller.onTick(new MockWorldView());
        assertEquals(1, h.controller.crashCounter());

        // Arm the actor so flush() throws inside the crashed-state path.
        h.actor.throwOnFlush = true;
        h.controller.onTick(new MockWorldView());

        assertTrue(h.controller.isCrashed(),
            "still latched; the floor is documentation");
        assertEquals(2, h.controller.crashCounter(),
            "the same D2 path fires again per ADR-0005 D3");
        assertEquals(2, h.crashEvents().size(),
            "primary channel re-reported");
        assertEquals(2, h.fallbackCalls.size(),
            "fallback channel re-reported");
    }

    /**
     * The outer try-catch seam: a RuntimeException that escapes the
     * Forge listener's harness (e.g. GotoCommandHandler.tick, an
     * init NPE, or a future seam between onServerTick and onTick)
     * must reach the same observable state as an in-pipeline crash.
     * Latch + clear + dual-channel report, counter monotonic.
     */
    @Test
    void emergencyLatchFromOutsideMirrorsInPipelineCrash() {
        Harness h = new Harness(false);
        assertFalse(h.controller.isCrashed(),
            "clean controller starts un-latched");
        assertEquals(0, h.controller.crashCounter());

        h.controller.emergencyLatch(
            new IllegalStateException("harness-outer-failure"));

        assertTrue(h.controller.isCrashed(),
            "outside-the-pipeline latch still latches");
        assertEquals(1, h.controller.crashCounter(),
            "counter starts at 1 for the first outside failure");
        assertEquals(1, h.crashEvents().size(),
            "primary channel reports the outside failure");
        assertEquals(1, h.fallbackCalls.size(),
            "fallback channel reports the outside failure");
        assertTrue(h.fallbackCalls.get(0).causeSummary()
                .contains("harness-outer-failure"),
            "snapshot must carry the original cause text");

        // Idempotency: latch stays set, counter still increments, both
        // channels re-report. A second outside failure is its own
        // report-worthy event so pathological rates stay visible.
        h.controller.emergencyLatch(
            new IllegalStateException("second-outer-failure"));
        assertTrue(h.controller.isCrashed(),
            "still latched after a second outside failure");
        assertEquals(2, h.controller.crashCounter(),
            "counter is monotonic across outside failures");
        assertEquals(2, h.crashEvents().size(),
            "primary re-reports so harness can see the rate");
        assertEquals(2, h.fallbackCalls.size(),
            "fallback re-reports so harness can see the rate");
    }
}
