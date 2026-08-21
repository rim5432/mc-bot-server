package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
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
 * Stage-0 ordering gate: the fixed pipeline order holds end to end —
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
    private static final class CountingMission implements BotProcess {
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
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new CellPos(0, 64, 0));
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));
        MockWorldView world = new MockWorldView();

        controller.onTick(world);
        assertEquals(1, mission.tickCalls);
        Claim healthyMove = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(healthyMove);
        assertEquals("mover", healthyMove.holder(),
            "sanity: mover claimed MOVE while healthy");

        // Bleed: reflex must preempt and the body must receive the halt.
        health[0] = 3f;
        actor.submitted.clear();
        controller.onTick(world);

        assertEquals(1, mission.tickCalls,
            "mission stage must be skipped while a reflex fires");
        assertNotNull(arbiter.paused(),
            "the running mission must be parked with its context");
        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "halt claim must come from the reflex, got "
                + move.holder());

        // Heal: threat gone -> resume hands control back next tick.
        health[0] = 20f;
        actor.submitted.clear();
        controller.onTick(world);
        assertNull(arbiter.paused());
        assertEquals(2, mission.tickCalls,
            "resumed mission runs again after revalidation");
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
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new CellPos(0, 64, 0));
        BotController controller = controller(health, layer, arbiter,
            mover, actor, new InMemoryEventQueue(() -> 1L, () -> 0L));

        controller.onTick(new MockWorldView());

        assertFalse(controller.isCrashed());
        assertEquals(1, mission.tickCalls);
        Claim move = lastClaimOf(actor, Channel.MOVE);
        assertNotNull(move);
        assertEquals("mover", move.holder());
        assertTrue(move.intent() instanceof Intent.Move m
                && m.forward() > 0,
            "healthy bot should walk toward its goal");
        assertFalse(actor.lastFlush.isEmpty(),
            "stage 4 must resolve claims every tick");
    }
}
