package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-0 ordering gate: the fixed pipeline order holds end to end ???
 * a non-null reflex skips the mission stage and halts the body through
 * the claim surface; a silent reflex lets arbiter and behaviors run.
 *
 * <p>Contract: see ADR-0004 D1 and boundaries.md section C.
 */
class TickPipelineGateTest {

    /** Delegating actor that records submissions and flush results. */
    private static final class RecordingActor implements Actor {
        private final ChannelArbiter delegate = new ChannelArbiter();
        private final List<Claim> submitted =
            new java.util.ArrayList<>();
        private Map<Channel, Claim> lastFlush = Map.of();

        @Override
        public void submit(Claim claim) {
            submitted.add(claim);
            delegate.submit(claim);
        }

        @Override
        public Map<Channel, Claim> flush() {
            lastFlush = delegate.flush();
            return lastFlush;
        }

        @Override
        public void clearAllIntents() {
            submitted.clear();
            delegate.clearAllIntents();
        }
    }

    /** Mission stub counting ticks for skip assertions. */
    private static class CountingMission implements BotProcess {
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
            return Directive.of(new GoalBlock(new CellPos(9, 64, 9)));
        }

        @Override
        public void onLostControl(
                com.mcbot.mcbotserver.api.interrupt.InterruptionContext c) {
        }

        @Override
        public boolean resume(
                com.mcbot.mcbotserver.api.interrupt.InterruptionContext c) {
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

    private static BotController controller(float[] health,
                                            SurvivalReflexLayer layer,
                                            TaskArbiter arbiter,
                                            Behavior behavior,
                                            Actor actor,
                                            EventQueue events) {
        return new BotController(layer, arbiter, List.of(behavior),
            actor, () -> new CellPos(0, 64, 0), () -> health[0],
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
            });
    }

    /**
     * Reflex fires -> mission stage skipped this tick -> the winning
     * MOVE claim is the reflex's own halt.
     */
    @Test
    void nonNullReflexSkipsMissionStageAndHaltsBody() {
        float[] health = {20f};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));
        MockWorldView world = flooredWorld();

        // Warm past the P2.1 departure hold so the mover claims MOVE;
        // the pin is the healthy pipeline, not the first tick.
        int warmup = PathingBehavior.DEPARTURE_DELAY_TICKS + 1;
        for (int i = 0; i < warmup; i++) {
            controller.onTick(world);
        }
        assertEquals(warmup, mission.tickCalls);
        Claim healthyMove = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(healthyMove);
        assertEquals("mover", healthyMove.holder(),
            "sanity: mover claimed MOVE while healthy");

        // Bleed: reflex must preempt and the body must receive the halt.
        health[0] = 3f;
        actor.submitted.clear();
        controller.onTick(world);

        assertEquals(warmup, mission.tickCalls,
            "mission stage must be skipped while a reflex fires");
        assertNotNull(arbiter.paused(),
            "the running mission must be parked with its context");
        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "halt claim must come from the reflex, got "
                + move.holder());

        // Heal: signal crosses release (20 > 15) but the 20-tick
        // hold window keeps the rule firing for FREEZE_HOLD_TICKS
        // ticks. Drain the hold one tick past the window so the
        // next controller.onTick sees a silent reflex and resumes.
        health[0] = 20f;
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            controller.onTick(world);
        }
        actor.submitted.clear();
        controller.onTick(world);
        assertNull(arbiter.paused());
        assertEquals(warmup + 1, mission.tickCalls,
            "resumed mission runs again after revalidation");
    }

    /**
     * Drowning reflex drives the body up, not to a halt: the ASCEND
     * action kind maps to a held jump so vanilla fluid physics swims
     * the body to the surface (ReflexAction contract, issue 0004
     * F6(2)).
     */
    @Test
    void lowAirReflexHoldsJumpInsteadOfHalting() {
        float[] health = {20f};
        int[] air = {com.mcbot.mcbotserver.api.reflex.ThreatBlackboard
            .MAX_AIR_SUPPLY};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.airSupply = air[0]);
        layer.addRule(new com.mcbot.mcbotserver.core.reflex
            .SurfaceOnLowAirRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5),
            BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));
        MockWorldView world = flooredWorld();
        controller.onTick(world);

        air[0] = 50;
        actor.submitted.clear();
        controller.onTick(world);

        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "ascend claim must come from the reflex");
        assertTrue(move.intent() instanceof Intent.Move hold
                && hold.jump(),
            "drowning reflex must hold jump, not halt: "
                + move.intent());
    }

    /**
     * Suffocation reflex digs instead of halting: the DIG action maps
     * to a held MOVE + an aim ROT + an INTERACT dig claim at the
     * decision's target cell (issue 0009). A targetless DIG - the
     * board stamped the vital without the position - degrades to the
     * plain freeze hold: missing data must not mint a dig-at-null.
     */
    @Test
    void suffocationReflexSubmitsDigClaimsAtTheEyeBlock() {
        float[] health = {20f};
        boolean[] inWall = {false};
        CellPos[] eyeBlock = {null};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> {
                board.inWall = inWall[0];
                board.suffocationBlock =
                    inWall[0] ? eyeBlock[0] : null;
            });
        layer.addRule(new com.mcbot.mcbotserver.core.reflex
            .DigOnSuffocationRule());
        TaskArbiter arbiter = new TaskArbiter();
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5),
            BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));

        CellPos target = new CellPos(0, 65, 0);
        inWall[0] = true;
        eyeBlock[0] = target;
        controller.onTick(new MockWorldView());

        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "hold claim must come from the reflex");
        assertTrue(move.intent() instanceof Intent.Move hold
                && !hold.jump() && hold.forward() == 0,
            "the dig runs standing still: " + move.intent());
        Claim rot = lastClaimOf(actor, Channel.ROT);
        assertNotNull(rot,
            "the reflex must aim at the dig target");
        assertTrue(rot.intent() instanceof Intent.Look);
        Claim interact = lastClaimOf(actor, Channel.INTERACT);
        assertNotNull(interact,
            "the reflex must hold the dig claim");
        assertTrue(interact.intent() instanceof Intent.Dig d
                && d.target().equals(target),
            "the dig claim names the eye block: "
                + interact.intent());

        // Targetless degrade: vital without position keeps the hold
        // but must not mint a dig-at-null.
        actor.submitted.clear();
        eyeBlock[0] = null;
        controller.onTick(new MockWorldView());
        assertNotNull(lastClaimOf(actor, Channel.MOVE));
        assertNull(lastClaimOf(actor, Channel.INTERACT),
            "a targetless DIG degrades to the freeze hold");
    }

    private Claim lastClaimOf(RecordingActor actor, Channel channel) {
        Claim found = null;
        for (Claim c : actor.submitted) {
            if (c.channel() == channel) {
                found = c;
            }
        }
        return found;
    }

    /**
     * Walkable strip so planner-based mover tests can find routes.
     */
    private static MockWorldView flooredWorld() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 60; x++) {
            for (int z = -2; z <= 12; z++) {
                world.putBlock(new com.mcbot.mcbotserver.api.world
                    .BlockSnapshot(new CellPos(x, 63, z),
                        "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    /**
     * Silent reflex -> full pipeline: mission ticks, mover claims MOVE
     * toward the goal, nothing pauses.
     */
    @Test
    void silentReflexLetsMissionAndBehaviorsRun() {
        float[] health = {18f};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));

        MockWorldView world = flooredWorld();

        // Warm past the P2.1 departure hold so the mover claims MOVE.
        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS;
             i++) {
            controller.onTick(world);
        }

        assertFalse(controller.isCrashed());
        assertEquals(PathingBehavior.DEPARTURE_DELAY_TICKS + 1,
            mission.tickCalls);
        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertEquals("mover", move.holder());
        assertTrue(move.intent() instanceof Intent.Move m
                && m.forward() > 0,
            "healthy bot should walk toward its goal");
        assertFalse(actor.lastFlush.isEmpty(),
            "stage 4 must resolve claims every tick");
    }

    /**
     * G4: the pause / resume / drop transitions are visible to the
     * harness as TASK_* events ??? urgent for paused and dropped, since
     * both mean "your mental model of the task is wrong".
     */
    @Test
    void pauseResumeDropEmitLifecycleEvents() {
        float[] health = {20f};
        InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 2L, () -> 9000L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, events);
        MockWorldView world = flooredWorld();

        // Establish the mission as current before any threat exists.
        controller.onTick(world);

        health[0] = 3f;
        controller.onTick(world);
        assertEquals(List.of(EventKind.TASK_PAUSED), kindsOf(events),
            "preemption must surface as TASK_PAUSED");

        // Heal: drain the 20-tick hold window before asserting the
        // TASK_RESUMED event. Each pre-hold tick is also a tick
        // where the reflex still fires, but the controller's tryResume
        // is gated by the reflex decision only, not by an event, so
        // no extra TASK_PAUSED events appear.
        health[0] = 20f;
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            controller.onTick(world);
        }
        controller.onTick(world);
        assertEquals(List.of(EventKind.TASK_PAUSED, EventKind.TASK_RESUMED),
            kindsOf(events),
            "clean revalidation must surface as TASK_RESUMED");
    }

    /**
     * G4 drop path: a failed revalidation surfaces as an urgent
     * TASK_DROPPED so the harness knows to replan.
     */
    @Test
    void droppedMissionEmitsUrgentTaskDropped() {
        float[] health = {20f};
        InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 2L, () -> 9000L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission() {
            @Override
            public boolean resume(
                    com.mcbot.mcbotserver.api.interrupt.InterruptionContext
                        c) {
                return false;
            }
        };
        arbiter.register(mission);
        arbiter.requestControl(mission);
        BotController controller = controller(health, layer, arbiter,
            new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from),
            new RecordingActor(), events);

        controller.onTick(flooredWorld());
        health[0] = 3f;
        controller.onTick(flooredWorld());
        health[0] = 20f;
        // Drain the 20-tick hold window; the mission is parked but
        // tryResume must run to actually surface TASK_DROPPED.
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            controller.onTick(flooredWorld());
        }
        controller.onTick(flooredWorld());

        List<String> kinds = kindsOf(events);
        assertEquals(2, kinds.size());
        assertEquals(EventKind.TASK_PAUSED, kinds.get(0));
        assertEquals(EventKind.TASK_DROPPED, kinds.get(1));
        BotEvent dropped = events.statusSnapshot(0).events().get(1);
        assertTrue(dropped.urgent(),
            "a dropped task is decision-critical: it will never finish");
    }

    /**
     * Structured failure: the reason travels in event attrs (task,
     * reason), never as text a harness must parse.
     */
    @Test
    void failedMissionSurfacesReasonInEventAttrs() {
        float[] health = {20f};
        InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        com.mcbot.mcbotserver.core.process.GotoProcess mission =
            new com.mcbot.mcbotserver.core.process.GotoProcess(
                "gt-attrs",
                new GoalBlock(new CellPos(5, 80, 5)), 50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, events);

        for (int i = 0; i < 6; i++) {
            controller.onTick(flooredWorld());
        }

        assertFalse(mission.isActive(), "unreachable goal must fail");
        assertEquals("NO_PATH", mission.failureReasonOrNull());
        BotEvent failed = events.statusSnapshot(0).events().stream()
            .filter(e -> EventKind.TASK_FAILED.equals(e.kind()))
            .findFirst().orElseThrow(
                () -> new AssertionError("TASK_MISSING"));
        assertEquals("gt-attrs", failed.attrs().get("task"),
            "task identity must be structured");
        assertEquals("NO_PATH", failed.attrs().get("reason"),
            "reason must be structured, not embedded in text");
    }

    /**
     * Preemption-vs-completion race (the retirement lap): a mission
     * that went terminal LAST tick is still in the arbiter's current
     * slot when a reflex fires. The freeze must retire it - never
     * park it - so its verdict event reaches the stream; PAUSED for a
     * corpse would be a lie, and silence would swallow the result.
     */
    @Test
    void reflexInRetirementLapEmitsVerdictNotPaused() {
        float[] health = {20f};
        InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        com.mcbot.mcbotserver.core.process.GotoProcess mission =
            new com.mcbot.mcbotserver.core.process.GotoProcess(
                "gt-lap", new GoalBlock(new CellPos(5, 80, 5)),
                50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, events);

        // Tick 1: planner fails the floating goal -> mission terminal
        // (active=false) but STILL current until stage 2 retires it.
        controller.onTick(flooredWorld());
        assertFalse(mission.isActive());

        // Tick 2: reflex fires BEFORE retirement could happen.
        health[0] = 3f;
        controller.onTick(flooredWorld());

        List<String> kinds = kindsOf(events);
        assertFalse(kinds.contains(EventKind.TASK_PAUSED),
            "a decided mission must not be announced as paused");
        assertEquals(EventKind.TASK_FAILED, kinds.get(0),
            "the verdict survives the freeze arriving one tick late");
        assertEquals("NO_PATH",
            events.statusSnapshot(0).events().get(0).attrs()
                .get("reason"));

        // Exactly-once emission: drive five more healthy ticks - the
        // retirement must not re-announce now that previousCurrent
        // has latched onto null.
        for (int i = 0; i < 5; i++) {
            controller.onTick(flooredWorld());
        }
        assertEquals(1, kindsOf(events).size(),
            "the verdict must be emitted exactly once, ever");
    }

    /**
     * Mirror of the retirement-lap test for the SUCCESS path: a
     * mission that completed via its goal predicate must surface
     * TASK_COMPLETED - not PAUSED - when the freeze lands one tick
     * later, proving both transition branches share the fix.
     */
    @Test
    void reflexInRetirementLapEmitsCompletedNotPaused() {
        float[] health = {20f};
        InMemoryEventQueue events =
            new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        com.mcbot.mcbotserver.core.process.GotoProcess mission =
            new com.mcbot.mcbotserver.core.process.GotoProcess(
                "gt-lap-ok", new GoalBlock(new CellPos(0, 64, 0)),
                50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5), BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, events);

        // Tick 1: goal predicate passes immediately -> terminal.
        controller.onTick(flooredWorld());
        assertFalse(mission.isActive());

        // Tick 2: freeze lands inside the retirement lap.
        health[0] = 3f;
        controller.onTick(flooredWorld());

        List<String> kinds = kindsOf(events);
        assertFalse(kinds.contains(EventKind.TASK_PAUSED),
            "a decided mission must not be announced as paused");
        assertEquals(EventKind.TASK_COMPLETED, kinds.get(0),
            "the success verdict survives the freeze");

        // Exactly-once emission across subsequent healthy ticks.
        for (int i = 0; i < 5; i++) {
            controller.onTick(flooredWorld());
        }
        assertEquals(1, kindsOf(events).size(),
            "the verdict must be emitted exactly once, ever");
    }

    private static List<String> kindsOf(InMemoryEventQueue events) {
        return events.statusSnapshot(0).events().stream()
            .map(BotEvent::kind)
            .filter(k -> k.startsWith("TASK_"))
            .toList();
    }

    /**
     * Keepalive (issue 0001 fix 2) fires every
     * {@code KEEPALIVE_INTERVAL} ticks with a snapshot of
     * plan-progress state. Verified end-to-end: 20 ticks of silent
     * pipeline produce exactly one KEEPALIVE on the event stream,
     * carrying the documented attrs (position, waypointIndex,
     * ticksSinceProgress, ticksSincePlan, planAge, goalCell).
     */
    @Test
    void keepaliveFiresOnInterval() {
        float[] health = {20f};
        InMemoryEventQueue events = new InMemoryEventQueue(
            () -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        RecordingActor actor = new RecordingActor();
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new com.mcbot.mcbotserver.api.types.Vec3(0.5, 64, 0.5),
            BasicMoves::from);
        BotController controller = controller(health, layer, arbiter,
            mover, actor, events);

        // Drive 20 ticks. The KEEPALIVE_INTERVAL is 20, so the
        // emission point fires once on tick 20.
        for (int i = 0; i < 20; i++) {
            controller.onTick(flooredWorld());
        }
        List<BotEvent> keepalives = events.statusSnapshot(0).events().stream()
            .filter(e -> EventKind.KEEPALIVE.equals(e.kind()))
            .toList();
        assertEquals(1, keepalives.size(),
            "one keepalive per KEEPALIVE_INTERVAL window");
        BotEvent k = keepalives.get(0);
        assertEquals(false, k.urgent(),
            "keepalive is informational, not urgent");
        // Plan-progress attrs - the test pins the key set, not the
        // order; the contract is the field names, not positions.
        Map<String, String> a = k.attrs();
        assertTrue(a.containsKey("pose"),
            "keepalive carries position: " + a.keySet());
        assertTrue(a.containsKey("waypointIndex"),
            "keepalive carries waypointIndex: " + a.keySet());
        assertTrue(a.containsKey("ticksSincePlanProgress"),
            "keepalive carries ticksSincePlanProgress (renamed from "
            + "ticksSinceProgress in PR-2): " + a.keySet());
        assertTrue(a.containsKey("ticksSincePlan"),
            "keepalive carries ticksSincePlan: " + a.keySet());
        assertTrue(a.containsKey("planAge"),
            "keepalive carries planAge: " + a.keySet());
        assertTrue(a.containsKey("goalCell"),
            "keepalive carries goalCell: " + a.keySet());

        // Drive another full window; a second keepalive fires.
        for (int i = 0; i < 20; i++) {
            controller.onTick(flooredWorld());
        }
        assertEquals(2, events.statusSnapshot(0).events().stream()
            .filter(e -> EventKind.KEEPALIVE.equals(e.kind()))
            .count(),
            "second keepalive fires on the next interval");
    }
}
