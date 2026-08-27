package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keepalive cadence gate (issue 0001 fix 2): the pipeline emits one
 * KEEPALIVE per interval window carrying plan-progress attrs, and a
 * silent healthy bot keeps them flowing - the harness's "is the
 * plan progressing" channel, disjoint from STATE_PUSH.
 *
 * <p>Contract: see boundaries.md section D (STATE_PUSH-vs-status-vs-
 * KEEPALIVE responsibility table, ledger 28).
 */
class TickKeepaliveGateTest {

    /**
     * Keepalive fires every {@code KEEPALIVE_INTERVAL} ticks with a
     * snapshot of plan-progress state. Verified end-to-end: 20 ticks
     * of silent pipeline produce exactly one KEEPALIVE on the event
     * stream, carrying the documented attrs (position,
     * waypointIndex, ticksSinceProgress, ticksSincePlan, planAge,
     * goalCell).
     */
    @Test
    void keepaliveFiresOnInterval() {
        float[] health = {20f};
        InMemoryEventQueue events = new InMemoryEventQueue(() -> 1L, () -> 0L);
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer, arbiter, mover, actor, events);

        // Drive 20 ticks. The KEEPALIVE_INTERVAL is 20, so the
        // emission point fires once on tick 20.
        for (int i = 0; i < 20; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }
        List<BotEvent> keepalives = events.statusSnapshot(0).events().stream()
                .filter(e -> EventKind.KEEPALIVE.equals(e.kind()))
                .toList();
        assertEquals(1, keepalives.size(), "one keepalive per KEEPALIVE_INTERVAL window");
        BotEvent k = keepalives.get(0);
        assertEquals(false, k.urgent(), "keepalive is informational, not urgent");
        // Plan-progress attrs - the test pins the key set, not the
        // order; the contract is the field names, not positions.
        Map<String, String> a = k.attrs();
        assertTrue(a.containsKey("pose"), "keepalive carries position: " + a.keySet());
        assertTrue(a.containsKey("waypointIndex"), "keepalive carries waypointIndex: " + a.keySet());
        assertTrue(
                a.containsKey("ticksSincePlanProgress"),
                "keepalive carries ticksSincePlanProgress (renamed from " + "ticksSinceProgress in PR-2): "
                        + a.keySet());
        assertTrue(a.containsKey("ticksSincePlan"), "keepalive carries ticksSincePlan: " + a.keySet());
        assertTrue(a.containsKey("planAge"), "keepalive carries planAge: " + a.keySet());
        assertTrue(a.containsKey("goalCell"), "keepalive carries goalCell: " + a.keySet());

        // Drive another full window; a second keepalive fires.
        for (int i = 0; i < 20; i++) {
            controller.onTick(TickGateFixtures.flooredWorld());
        }
        assertEquals(
                2,
                events.statusSnapshot(0).events().stream()
                        .filter(e -> EventKind.KEEPALIVE.equals(e.kind()))
                        .count(),
                "second keepalive fires on the next interval");
    }
}
