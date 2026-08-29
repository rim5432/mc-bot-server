package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stage-0 disclosure gate: the boundary-D protocol holds end to end on
 * pure Java.
 *
 * <p>Contract: see boundaries.md Boundary D protocol — sync error vs
 * async execution split (invariant 5), no-silent-drop with synthetic
 * EVENT_DROPPED (invariant 3), no-survive-restart via resetAt
 * (invariant 2), and model-relevant-only state snapshot fields.
 */
class DisclosureGateTest {

    private static BotEvent stamped(String kind) {
        return new BotEvent(kind, 1L, 600L, false, Map.of(), kind);
    }

    /**
     * Invariant 5: structural failures answer Rejected synchronously
     * and never reach the stream; a well-formed command answers Ok and
     * its outcome arrives only as a TASK_* event.
     */
    @Test
    void syncRejectVsAsyncExecution() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        CommandBus bus = new CommandBus(queue);
        bus.register("noop", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return command.args().isEmpty() ? null : "args must be empty";
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                bus.finishTask(taskId, EventKind.TASK_COMPLETED, 1L, 601L, "done");
            }
        });

        assertTrue(bus.submit(null) instanceof SubmitResult.Rejected, "null command must be a sync structural reject");
        assertTrue(
                bus.submit(new BotCommand("ghost", Map.of())) instanceof SubmitResult.Rejected,
                "unknown verb must be a sync structural reject");
        SubmitResult badArgs = bus.submit(new BotCommand("noop", Map.of("x", "1")));
        assertTrue(badArgs instanceof SubmitResult.Rejected, "handler validation failure must be a sync reject");

        SubmitResult ok = bus.submit(new BotCommand("noop", Map.of()));
        assertTrue(ok instanceof SubmitResult.Ok);
        String taskId = ((SubmitResult.Ok) ok).taskId();
        EventBatch batch = queue.statusSnapshot(0);
        assertEquals(
                List.of(EventKind.TASK_COMPLETED),
                batch.events().stream().map(BotEvent::kind).toList(),
                "execution feedback must travel via the event stream");
        assertEquals(taskId, batch.events().get(0).attrs().get("taskId"));
    }

    /**
     * Invariant 3: overflow drops the oldest entries beyond the cap,
     * counts them since the last poll, and appends exactly one synthetic
     * EVENT_DROPPED to that page; the surviving window is the newest
     * DEFAULT_CAP payloads in arrival order.
     */
    @Test
    void overflowDropsAreCountedAndSignalled() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 2L, () -> 5L);
        int total = InMemoryEventQueue.DEFAULT_CAP + 5;
        for (int i = 0; i < total; i++) {
            queue.push(stamped("e" + i));
        }
        EventBatch batch = queue.statusSnapshot(0);
        assertEquals(5, batch.droppedCount());
        List<BotEvent> events = batch.events();
        assertEquals(InMemoryEventQueue.DEFAULT_CAP + 1, events.size(), "full surviving window plus one drop notice");
        assertEquals("e5", events.get(0).text(), "oldest five (e0..e4) were evicted; e5 leads the window");
        assertEquals(
                "e" + (total - 1),
                events.get(events.size() - 2).text(),
                "newest payload sits right before the drop notice");
        assertEquals(EventKind.EVENT_DROPPED, events.get(events.size() - 1).kind());
        assertEquals("5", events.get(events.size() - 1).attrs().get("count"));
        assertEquals(
                2L, events.get(events.size() - 1).day(), "synthetic notice is stamped by the injected time source");
    }

    /**
     * Invariant 1 hardening: the timestamp contract is enforced at
     * construction — out-of-range time-of-day or a negative day can
     * never enter the stream, so a Stage 2 adapter bug fails fast at
     * the emit site instead of silently poisoning the protocol.
     */
    @Test
    void eventsRejectOutOfRangeTimeStamps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BotEvent("e", 1L, 24000L, false, Map.of(), "x"),
                "t == 24000 must be rejected");
        assertThrows(IllegalArgumentException.class, () -> new BotEvent("e", 1L, -1L, false, Map.of(), "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BotEvent("e", -1L, 0L, false, Map.of(), "x"),
                "negative day must be rejected");
        // Boundary values stay legal.
        new BotEvent("e", 0L, 0L, false, Map.of(), "midnight");
        new BotEvent("e", 9L, 23999L, false, Map.of(), "last tick");
    }

    /**
     * Invariant 2: after reset the queue is empty and resetAt advanced.
     * The protocol makes the HARNESS responsible for noticing resetAt
     * and reconciling via the state snapshot — a stale cursor that kept
     * polling simply continues with monotonic ids and may see only
     * post-reset events; pre-reset entries are gone forever.
     */
    @Test
    void restartMarkerInvalidatesStaleCursor() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        queue.push(stamped("a"));
        EventBatch beforeReset = queue.statusSnapshot(0);
        assertEquals(List.of("a"), kindsOf(beforeReset));
        long markerBefore = queue.resetAt();

        queue.reset();

        assertEquals(markerBefore + 1, queue.resetAt(), "reset must advance the restart marker");
        assertTrue(queue.statusSnapshot(0).events().isEmpty(), "wipe must remove pre-reset entries");

        queue.push(stamped("b"));
        EventBatch stalePoll = queue.statusSnapshot(beforeReset.latestEventId());
        assertEquals(List.of("b"), kindsOf(stalePoll), "pre-reset entries never come back; new ones flow normally");
        assertEquals(queue.resetAt(), stalePoll.resetAt(), "the batch carries the marker the harness must check");
        assertTrue(stalePoll.latestEventId() > beforeReset.latestEventId(), "ids stay monotonic across a reset");
    }

    private static List<String> kindsOf(EventBatch batch) {
        return batch.events().stream().map(BotEvent::kind).toList();
    }

    /**
     * State snapshot carries only model-relevant categorical fields —
     * enforced structurally: the component list stays within the
     * allowed set, so continuous fields can never sneak in silently.
     */
    @Test
    void botStateIsModelRelevantOnly() {
        Set<String> allowed = Set.of(
                "pos",
                "yaw",
                "pitch",
                "dimension",
                "itemCounts",
                "selectedHotbarSlot",
                "effectAmplifiers",
                "currentTaskSummary",
                // Issue 0013 R5: bucketed hearts keep the change-detect
                // cadence honest (raw floats would push on every regen
                // tick); freeSlots rides the itemCounts snapshot loop.
                "healthHearts",
                "freeSlots",
                // Ledger 34 (eat slice): hunger rides the snapshot.
                "foodLevel",
                // Experience level: enchanting/anvil/trading cost display;
                // progress fraction deliberately absent (continuous).
                "experienceLevel");
        Set<String> components = Stream.of(BotState.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(allowed, components, "BotState shape drifted outside the frozen model-relevant set");
        new BotState(new CellPos(0, 64, 0), 0f, 0f, "overworld", Map.of(), 0, Map.of(), "idle", 20, 0, 20, 0);
    }
}
