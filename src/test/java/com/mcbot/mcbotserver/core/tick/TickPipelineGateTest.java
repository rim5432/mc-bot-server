package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Stage-0 ordering gate, end-to-end half: a non-null reflex skips
 * the mission stage and halts the body through the claim surface;
 * a silent reflex lets arbiter and behaviors run the full pipeline.
 *
 * <p>Contract: see ADR-0004 D1 and boundaries.md section C.
 */
class TickPipelineGateTest {

    /**
     * Reflex fires -> mission stage skipped this tick -> the winning
     * MOVE claim is the reflex's own halt.
     */
    @Test
    void nonNullReflexSkipsMissionStageAndHaltsBody() {
        float[] health = {20f};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(
                health, layer, arbiter, mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));
        MockWorldView world = TickGateFixtures.flooredWorld();

        // Warm past the P2.1 departure hold so the mover claims MOVE;
        // the pin is the healthy pipeline, not the first tick.
        int warmup = PathingBehavior.DEPARTURE_DELAY_TICKS + 1;
        for (int i = 0; i < warmup; i++) {
            controller.onTick(world);
        }
        assertEquals(warmup, mission.tickCalls);
        Claim healthyMove = actor.lastClaim(Channel.MOVE);
        assertNotNull(healthyMove);
        assertEquals("mover", healthyMove.holder(), "sanity: mover claimed MOVE while healthy");

        // Bleed: reflex must preempt and the body must receive the halt.
        health[0] = 3f;
        actor.submitted.clear();
        controller.onTick(world);

        assertEquals(warmup, mission.tickCalls, "mission stage must be skipped while a reflex fires");
        assertNotNull(arbiter.paused(), "the running mission must be parked with its context");
        Claim move = actor.lastClaim(Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"), "halt claim must come from the reflex, got " + move.holder());

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
        assertEquals(warmup + 1, mission.tickCalls, "resumed mission runs again after revalidation");
    }

    /**
     * Silent reflex -> full pipeline: mission ticks, mover claims MOVE
     * toward the goal, nothing pauses.
     */
    @Test
    void silentReflexLetsMissionAndBehaviorsRun() {
        float[] health = {18f};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover", () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(
                health, layer, arbiter, mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));

        MockWorldView world = TickGateFixtures.flooredWorld();

        // Warm past the P2.1 departure hold so the mover claims MOVE.
        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS; i++) {
            controller.onTick(world);
        }

        assertFalse(controller.isCrashed());
        assertEquals(PathingBehavior.DEPARTURE_DELAY_TICKS + 1, mission.tickCalls);
        Claim move = actor.lastClaim(Channel.MOVE);
        assertNotNull(move);
        assertEquals("mover", move.holder());
        assertTrue(
                move.intent() instanceof Intent.Move m && m.forward() > 0, "healthy bot should walk toward its goal");
        assertFalse(actor.lastFlush.isEmpty(), "stage 4 must resolve claims every tick");
    }
}
