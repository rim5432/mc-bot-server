package com.mcbot.mcbotserver.boundaryd;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary-D idempotency-key gate: retry protection on the command
 * channel holds for both key schemes and the dedupe window closes
 * exactly at terminal state.
 *
 * <p>Contract: see boundaries.md Boundary D protocol - idempotency key
 * section (C scheme: explicit key OR verb+args fallback; window lives
 * while the task is live; eviction on terminal state makes a post-task
 * retry a fresh acceptance, not an error).
 */
class IdempotencyKeyGateTest {

    /**
     * A structurally-valid handler that records executions instead of
     * finishing, so each test controls when the dedupe window closes.
     */
    private static CommandBus.Handler countingHandler(List<String> executed) {
        return new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return null;
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                executed.add(taskId);
            }
        };
    }

    private static SubmitResult.Ok submitOk(CommandBus bus, BotCommand command,
                                            String idempotencyKey) {
        SubmitResult result = bus.submit(command, idempotencyKey);
        assertTrue(result instanceof SubmitResult.Ok,
            "well-formed submissions must be accepted, got: " + result);
        return (SubmitResult.Ok) result;
    }

    /**
     * Explicit key: two submits carrying the same key collapse to the
     * first acceptance - the replay points at the original taskId and
     * the handler executes exactly once.
     */
    @Test
    void explicitKeyCollapsesRetryToFirstAcceptance() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "attempt-1");
        SubmitResult.Ok retry = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "attempt-1");

        assertFalse(first.idempotencyReplay(), "first submit is fresh");
        assertTrue(retry.idempotencyReplay(),
            "same-key resubmit must answer as a deduped replay");
        assertEquals(first.taskId(), retry.taskId(),
            "the replay carries the original taskId, not a new one");
        assertEquals(List.of(first.taskId()), executed,
            "a deduped retry must not re-execute the handler");
    }

    /**
     * Fallback scheme: with a null key, two structurally identical
     * commands collapse while different args stay independent - the
     * harness gets retry protection without supplying any key.
     */
    @Test
    void fallbackKeyDedupesIdenticalCommandsOnly() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), null);
        SubmitResult.Ok same = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), null);
        SubmitResult.Ok other = submitOk(bus,
            new BotCommand("goto", Map.of("x", "9")), null);

        assertTrue(same.idempotencyReplay(),
            "identical verb+args inside the window must collapse");
        assertEquals(first.taskId(), same.taskId());
        assertFalse(other.idempotencyReplay(),
            "different args are a different logical command");
        assertNotEquals(first.taskId(), other.taskId());
        assertEquals(2, executed.size());
    }

    /**
     * The fallback canonical form is indifferent to the caller's map
     * insertion order: equal-by-content args must hash to one dedupe
     * key regardless of literal order.
     */
    @Test
    void fallbackKeyIsIndifferentToArgInsertionOrder() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "1", "y", "2")), null);
        SubmitResult.Ok reordered = submitOk(bus,
            new BotCommand("goto", Map.of("y", "2", "x", "1")), null);

        assertTrue(reordered.idempotencyReplay(),
            "same logical args in another insertion order must collapse");
        assertEquals(first.taskId(), reordered.taskId());
        assertEquals(1, executed.size());
    }

    /**
     * Distinct explicit keys never collapse, even for identical
     * payloads: an explicit key means the harness owns the dedupe
     * identity, and two keys are two intentional missions.
     */
    @Test
    void distinctExplicitKeysStayIndependent() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok a = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "key-a");
        SubmitResult.Ok b = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "key-b");

        assertFalse(a.idempotencyReplay());
        assertFalse(b.idempotencyReplay());
        assertNotEquals(a.taskId(), b.taskId());
        assertEquals(2, executed.size());
    }

    /**
     * Terminal state closes the window: a same-key submit after
     * TASK_COMPLETED is a fresh acceptance with a new taskId, not a
     * replay of a dead task and not an error.
     */
    @Test
    void terminalStateClosesTheDedupeWindow() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "retry-token");
        bus.finishTask(first.taskId(), EventKind.TASK_COMPLETED,
            1L, 601L, "done");

        SubmitResult.Ok after = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "retry-token");

        assertFalse(after.idempotencyReplay(),
            "post-terminal retry must be a fresh acceptance");
        assertNotEquals(first.taskId(), after.taskId());
        assertEquals(2, executed.size());
    }

    /**
     * Cancellation closes the window exactly like a terminal event:
     * a harness retrying a cancelled task gets a working mission, not
     * a pointer at the cancelled one.
     */
    @Test
    void cancelClosesTheDedupeWindow() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult.Ok first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "retry-token");
        assertTrue(bus.cancel(first.taskId()), "live task must cancel");

        SubmitResult.Ok after = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), "retry-token");

        assertFalse(after.idempotencyReplay(),
            "post-cancel retry must be a fresh acceptance");
        assertNotEquals(first.taskId(), after.taskId());
        assertEquals(2, executed.size());
    }

    /**
     * Structurally rejected submissions leave no cache entry behind:
     * a later well-formed submit reusing the same key must be accepted
     * fresh, so a malformed first attempt cannot poison the window.
     */
    @Test
    void rejectedSubmissionLeavesNoCacheEntry() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return command.args().isEmpty() ? null : "bad args";
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                executed.add(taskId);
            }
        });

        SubmitResult bad = bus.submit(
            new BotCommand("goto", Map.of("x", "5")), "retry-token");
        assertTrue(bad instanceof SubmitResult.Rejected,
            "validation failure must be a sync reject");
        SubmitResult.Ok good = submitOk(bus,
            new BotCommand("goto", Map.of()), "retry-token");

        assertFalse(good.idempotencyReplay(),
            "the rejected attempt must not have seeded the cache");
        assertEquals(1, executed.size());
    }

    @Test
    void retireClosesTheWindowForDeathsAnnouncedOutsideTheBus() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        List<String> executed = new ArrayList<>();
        bus.register("goto", countingHandler(executed));

        SubmitResult first = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), null);
        String taskId = ((SubmitResult.Ok) first).taskId();

        // The arbiter announces TIMEOUT deaths directly onto the event
        // stream; the bus only learns of the death via retire() (the
        // verb handler's lifecycle sweep). Before that sweep lands, a
        // retry must still replay - after it, it must be fresh.
        SubmitResult duringLife = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), null);
        assertTrue(((SubmitResult.Ok) duringLife).idempotencyReplay(),
            "while the death is unannounced, dedupe still holds");
        assertEquals(1, executed.size());

        assertTrue(bus.retire(taskId),
            "retiring a live task closes its window");
        assertFalse(bus.retire(taskId), "second retire is idempotent");

        SubmitResult afterDeath = submitOk(bus,
            new BotCommand("goto", Map.of("x", "5")), null);
        assertFalse(((SubmitResult.Ok) afterDeath).idempotencyReplay(),
            "post-retirement retry must be a fresh acceptance");
        assertNotEquals(taskId,
            ((SubmitResult.Ok) afterDeath).taskId());
        assertEquals(2, executed.size());
    }
}
