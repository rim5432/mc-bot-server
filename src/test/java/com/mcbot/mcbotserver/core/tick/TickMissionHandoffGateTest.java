package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * G4 mission-handoff gate: the pause / resume / drop transitions and
 * the retirement lap are visible to the harness as structured TASK_*
 * events - urgent for paused and dropped, since both mean "your
 * mental model of the task is wrong" - and verdicts are emitted
 * exactly once even when a freeze lands one tick late.
 *
 * <p>Contract: see ADR-0004 D1 (MissionReporter) and boundaries.md
 * section D (event disclosure).
 */
class TickMissionHandoffGateTest {

    /**
     * G4: the pause / resume / drop transitions are visible to the
     * harness as TASK_* events - urgent for paused and dropped,
     * since both mean "your mental model of the task is wrong".
     */
    @Test
    void pauseResumeDropEmitLifecycleEvents() {
        float[] health = {20f};
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 2L, () -> 9000L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, actor, events);
        MockWorldView world = TickGateFixtures.flooredWorld();

        // Establish the mission as current before any threat exists.
        controller.onTick(world);

        health[0] = 3f;
        controller.onTick(world);
        assertEquals(List.of(EventKind.TASK_PAUSED), kindsOf(events), "preemption must surface as TASK_PAUSED");

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
        assertEquals(
                List.of(EventKind.TASK_PAUSED, EventKind.TASK_RESUMED),
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
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 2L, () -> 9000L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission() {
            @Override
            public boolean resume(com.mcbot.mcbotserver.api.interrupt.InterruptionContext c) {
                return false;
            }
        };
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, new PipelineActor(), events);

        controller.onTick(TickGateFixtures.flooredWorld());
        health[0] = 3f;
        controller.onTick(TickGateFixtures.flooredWorld());
        health[0] = 20f;
        // Drain the 20-tick hold window; the mission is parked but
        // tryResume must run to actually surface TASK_DROPPED.
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }
        controller.onTick(TickGateFixtures.flooredWorld());

        List<String> kinds = kindsOf(events);
        assertEquals(2, kinds.size());
        assertEquals(EventKind.TASK_PAUSED, kinds.get(0));
        assertEquals(EventKind.TASK_DROPPED, kinds.get(1));
        BotEvent dropped = events.statusSnapshot(0).events().get(1);
        assertTrue(dropped.urgent(), "a dropped task is decision-critical: it will never finish");
    }

    /**
     * Structured failure: the reason travels in event attrs (task,
     * reason), never as text a harness must parse.
     */
    @Test
    void failedMissionSurfacesReasonInEventAttrs() {
        float[] health = {20f};
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        GotoProcess mission = new GotoProcess("gt-attrs", new GoalBlock(new CellPos(5, 80, 5)), 50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, actor, events);

        for (int i = 0; i < 6; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }

        assertFalse(mission.isActive(), "unreachable goal must fail");
        assertEquals("NO_PATH", mission.failureReasonOrNull());
        BotEvent failed = events.statusSnapshot(0).events().stream()
                .filter(e -> EventKind.TASK_FAILED.equals(e.kind()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TASK_MISSING"));
        assertEquals("gt-attrs", failed.attrs().get("task"), "task identity must be structured");
        assertEquals("NO_PATH", failed.attrs().get("reason"), "reason must be structured, not embedded in text");
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
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        GotoProcess mission = new GotoProcess("gt-lap", new GoalBlock(new CellPos(5, 80, 5)), 50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, actor, events);

        // Tick 1: planner fails the floating goal -> mission terminal
        // (active=false) but STILL current until stage 2 retires it.
        controller.onTick(TickGateFixtures.flooredWorld());
        assertFalse(mission.isActive());

        // Tick 2: reflex fires BEFORE retirement could happen.
        health[0] = 3f;
        controller.onTick(TickGateFixtures.flooredWorld());

        List<String> kinds = kindsOf(events);
        assertFalse(kinds.contains(EventKind.TASK_PAUSED), "a decided mission must not be announced as paused");
        assertEquals(EventKind.TASK_FAILED, kinds.get(0), "the verdict survives the freeze arriving one tick late");
        assertEquals("NO_PATH", events.statusSnapshot(0).events().get(0).attrs().get("reason"));

        // Exactly-once emission: drive five more healthy ticks - the
        // retirement must not re-announce now that previousCurrent
        // has latched onto null.
        for (int i = 0; i < 5; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }
        assertEquals(1, kindsOf(events).size(), "the verdict must be emitted exactly once, ever");
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
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        GotoProcess mission = new GotoProcess("gt-lap-ok", new GoalBlock(new CellPos(0, 64, 0)), 50, 400);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, actor, events);

        // Tick 1: goal predicate passes immediately -> terminal.
        controller.onTick(TickGateFixtures.flooredWorld());
        assertFalse(mission.isActive());

        // Tick 2: freeze lands inside the retirement lap.
        health[0] = 3f;
        controller.onTick(TickGateFixtures.flooredWorld());

        List<String> kinds = kindsOf(events);
        assertFalse(kinds.contains(EventKind.TASK_PAUSED), "a decided mission must not be announced as paused");
        assertEquals(EventKind.TASK_COMPLETED, kinds.get(0), "the success verdict survives the freeze");

        // Exactly-once emission across subsequent healthy ticks.
        for (int i = 0; i < 5; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }
        assertEquals(1, kindsOf(events).size(), "the verdict must be emitted exactly once, ever");
    }

    private static List<String> kindsOf(InMemoryEventQueue events) {
        return events.statusSnapshot(0).events().stream()
                .map(BotEvent::kind)
                .filter(k -> k.startsWith("TASK_"))
                .toList();
    }
}
