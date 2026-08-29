package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.AcquireFoodWhenHungryRule;
import com.mcbot.mcbotserver.core.reflex.EatWhenHungryRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * End-to-end offline gate for the eat reflex WIRING (the engine
 * eatsWhenHungry scenario's offline twin): a hungry body with food in
 * the hotbar must produce the select-and-use claim pair through the
 * real layer + controller chain - not just the rule's isolated
 * emission. The first engine batch (2026-08-29) fired this exact
 * chain green offline while the engine read food=10 at timeout, so
 * the wiring seam gets its own gate.
 */
class EatReflexIntegrationGateTest {

    @Test
    void hungryBodyWithFoodSubmitsSelectAndUsePair() {
        AtomicInteger foodLevel = new AtomicInteger(10);
        AtomicInteger bestFoodSlot = new AtomicInteger(0);

        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
            board.foodLevel = foodLevel.get();
            board.foodSlot = bestFoodSlot.get();
        });
        layer.addRule(new EatWhenHungryRule());
        layer.addRule(new AcquireFoodWhenHungryRule());

        InMemoryEventQueue events = new InMemoryEventQueue(() -> 1L, () -> 0L);
        TaskArbiter arbiter = new TaskArbiter();
        RecordingActor actor = new RecordingActor();
        Behavior idle = (world, directive, a) -> {
            if (directive == null) {
                return com.mcbot.mcbotserver.api.process.ExecutionReport.running();
            }
            return com.mcbot.mcbotserver.api.process.ExecutionReport.running();
        };
        BotController controller =
                TickGateFixtures.controller(new float[] {20f}, layer, arbiter, idle, actor, (EventQueue) events);
        // The rig wires the injector's eat slot (BotAssembly); the
        // gate mirrors that wiring, mirroring the scenario under test.
        controller.setEatSlotSupplier(bestFoodSlot::get);

        WorldView world = new MockWorldView();
        controller.onTick(world);

        Claim use = lastClaimOn(actor, Channel.USE);
        Claim slot = lastClaimOn(actor, Channel.SLOT);
        assertNotNull(use, "the eat reflex must submit a USE claim for the bite");
        assertNotNull(slot, "the eat reflex must submit a SLOT claim selecting the food");
        assertTrue(
                use.intent() instanceof com.mcbot.mcbotserver.api.actor.Intent.Use
                        && ((com.mcbot.mcbotserver.api.actor.Intent.Use) use.intent()).pressing(),
                "the USE claim must be a press edge");
        assertTrue(
                slot.intent() instanceof com.mcbot.mcbotserver.api.actor.Intent.SelectSlot
                        && ((com.mcbot.mcbotserver.api.actor.Intent.SelectSlot) slot.intent()).slot() == 0,
                "the SLOT claim must select the bread slot");
    }

    private static Claim lastClaimOn(Actor actor, Channel channel) {
        RecordingActor recording = (RecordingActor) actor;
        List<Claim> submitted = recording.submitted;
        for (int i = submitted.size() - 1; i >= 0; i--) {
            Claim claim = submitted.get(i);
            if (claim.channel() == channel) {
                return claim;
            }
        }
        return null;
    }
}
