package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.TameCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TameProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tame verb wiring: decision-28 arguments parse into a mission on the
 * arbiter, bad arguments reject synchronously - the tame twin of the
 * goto gate.
 *
 * <p>Contract: see boundaries.md decision 18's goto shape (task verbs
 * ride CommandBus) and decision 28 (the verb table grows by issue).
 */
class TameCommandGateTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);

    private static final MockWorldView WORLD = new MockWorldView();

    /**
     * A well-formed tame lands a TameProcess on the arbiter named
     * after the handed animal; blank ids, a missing id, and a
     * non-positive budget reject synchronously.
     */
    @Test
    void tameVerbParsesRegistersAndRejects() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        TaskArbiter arbiter = new TaskArbiter();
        TameCommandHandler handler = new TameCommandHandler(arbiter, queue, () -> 1L, () -> 0L, () -> BOT);
        handler.attach(bus);
        bus.setCancelListener(handler::onCancel);

        assertTrue(
                bus.submit(new BotCommand("tame", Map.of("targetId", "wolf-1"))) instanceof SubmitResult.Ok,
                "a well-formed tame is accepted");
        arbiter.tick(WORLD);
        TameProcess current = (TameProcess) arbiter.current();
        assertNotNull(current, "tame must request control immediately");
        assertTrue(current.displayName().startsWith("tame:task-"), "the display name carries the verb");
        assertEquals("wolf-1", current.targetId(), "the mission is pointed at the handed animal");

        String reason = "tame wants a non-blank target id [timeoutTicks]";
        assertEquals(reason, rejectionReason(bus, Map.of()), "a missing id rejects");
        assertEquals(reason, rejectionReason(bus, Map.of("targetId", " ")), "a blank id rejects");
        assertEquals(
                reason,
                rejectionReason(bus, Map.of("targetId", "w", "timeoutTicks", "0")),
                "a non-positive budget rejects");
        assertEquals(
                reason,
                rejectionReason(bus, Map.of("targetId", "w", "timeoutTicks", "abc")),
                "a non-numeric budget rejects");
    }

    /**
     * An accepted mission cancelled through the handler goes terminal
     * as CANCELLED - the generic VerbTaskHandler cancel path rides
     * the tame verb too.
     */
    @Test
    void cancelAbortsTheMission() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        TaskArbiter arbiter = new TaskArbiter();
        TameCommandHandler handler = new TameCommandHandler(arbiter, queue, () -> 1L, () -> 0L, () -> BOT);
        handler.attach(bus);
        bus.setCancelListener(handler::onCancel);

        SubmitResult.Ok accepted = (SubmitResult.Ok) bus.submit(new BotCommand("tame", Map.of("targetId", "wolf-1")));
        // A sighted tameable with its item equipped: the first scan
        // keeps the mission live instead of refusing it.
        MockWorldView world = new MockWorldView();
        world.setInventory(inventoryWithBone());
        world.addEntity(new EntitySnapshot("wolf-1", "minecraft:wolf", new CellPos(6, 64, 0), 10f, 10f));
        arbiter.tick(world);
        TameProcess mission = (TameProcess) arbiter.current();

        handler.onCancel(accepted.taskId(), "tame");
        arbiter.tick(world);

        assertFalse(mission.isActive(), "the cancelled mission is terminal");
        assertEquals("CANCELLED", mission.failureReasonOrNull(), "the cancel verdict is sticky");
    }

    private static String rejectionReason(CommandBus bus, Map<String, String> args) {
        SubmitResult result = bus.submit(new BotCommand("tame", args));
        if (result instanceof SubmitResult.Ok) {
            throw new AssertionError("expected a synchronous rejection, got " + result);
        }
        return ((SubmitResult.Rejected) result).reason();
    }

    private static InventoryView inventoryWithBone() {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bone", 1));
        return new InventoryView(
                main, 0, List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)), ItemView.EMPTY);
    }
}
