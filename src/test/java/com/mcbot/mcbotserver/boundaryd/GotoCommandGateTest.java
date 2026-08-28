package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Stage-1 goto verb wiring: decision-18 arguments parse into a mission
 * on the arbiter, bad arguments reject synchronously, cancellation
 * aborts the mission and emits TASK_CANCELLED.
 *
 * <p>Contract: see boundaries.md decision 18.
 */
class GotoCommandGateTest {

    private static final MockWorldView WORLD = new MockWorldView();

    /**
     * Well-formed goto lands on the arbiter; structural problems
     * (missing/non-integer/negative args) reject synchronously.
     */
    @Test
    void gotoVerbParsesAndRegisters() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        TaskArbiter arbiter = new TaskArbiter();
        GotoCommandHandler handler = new GotoCommandHandler(arbiter, queue, () -> 1L, () -> 0L);
        handler.attach(bus);
        // attach no longer self-registers: the composition root owns
        // the one cancel-listener slot; single-handler wirings too.
        bus.setCancelListener(handler::onCancel);

        assertTrue(
                bus.submit(new BotCommand("goto", Map.of("x", "10", "y", "64", "z", "-3"))) instanceof SubmitResult.Ok);
        arbiter.tick(WORLD);
        BotProcess current = arbiter.current();
        assertNotNull(current, "goto must request control immediately");
        assertTrue(current.displayName().startsWith("goto:task-"));

        assertEquals("missing arg: x", rejectionReason(bus, Map.of("y", "1", "z", "2")));
        assertEquals("arg x is not an integer: abc", rejectionReason(bus, Map.of("x", "abc", "y", "1", "z", "2")));
        assertEquals(
                "tolerance must not be negative",
                rejectionReason(bus, Map.of("x", "1", "y", "1", "z", "2", "tolerance", "-5")));
        assertEquals(
                "timeoutTicks must be positive",
                rejectionReason(bus, Map.of("x", "1", "y", "1", "z", "2", "timeoutTicks", "0")));
    }

    private String rejectionReason(CommandBus bus, Map<String, String> args) {
        SubmitResult r = bus.submit(new BotCommand("goto", args));
        assertTrue(r instanceof SubmitResult.Rejected, "expected sync rejection for " + args);
        return ((SubmitResult.Rejected) r).reason();
    }

    /**
     * cancel() aborts the live mission and surfaces TASK_CANCELLED on
     * the stream; cancelling an unknown id stays a silent false.
     */
    @Test
    void cancelAbortsMissionAndEmitsEvent() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 3L, () -> 15000L);
        CommandBus bus = new CommandBus(queue);
        TaskArbiter arbiter = new TaskArbiter();
        GotoCommandHandler handler = new GotoCommandHandler(arbiter, queue, () -> 3L, () -> 15000L);
        handler.attach(bus);
        bus.setCancelListener(handler::onCancel);

        SubmitResult.Ok ok =
                (SubmitResult.Ok) bus.submit(new BotCommand("goto", Map.of("x", "50", "y", "70", "z", "50")));
        arbiter.tick(WORLD);
        BotProcess mission = arbiter.current();
        assertNotNull(mission);

        assertTrue(bus.cancel(ok.taskId()));
        assertFalse(mission.isActive(), "cancel must stop the mission");
        assertFalse(bus.cancel(ok.taskId()), "double cancel reports not-found");

        var events = queue.statusSnapshot(0).events();
        assertEquals(1, events.size());
        assertEquals(EventKind.TASK_CANCELLED, events.get(0).kind());
        assertSame(false, events.get(0).urgent());
    }
}
