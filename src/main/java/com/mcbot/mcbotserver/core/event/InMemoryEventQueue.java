package com.mcbot.mcbotserver.core.event;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Reference in-memory EventQueue: cursor drain, bounded backpressure,
 * restart marker, reserved multi-holder lock.
 *
 * <p>Contract: see boundaries.md Boundary D protocol and
 * disclosure-patterns.md section 3 (loss-prevention invariants).
 * Backpressure drops the oldest entry beyond {@value #DEFAULT_CAP};
 * the next poll reports the drop both as {@code droppedCount} and as a
 * synthetic EVENT_DROPPED entry appended to that page — by mechanism,
 * not by producer diligence.
 *
 * <p>Behind-consumer recovery: when a caller polls with a
 * {@code sinceEventId} older than the oldest retained entry, the page
 * is preceded by a synthetic EVENT_GAP entry carrying the size of the
 * lost range, the caller's stale cursor, and the oldest id the page
 * actually contains. The gap event is urgent so the harness short-
 * circuits its {@code shouldDrain} gate and reconciles via
 * {@code getState()} before consuming the page — events answer
 * "what happened", state answers "what is", and a behind consumer
 * needs the second one first.
 *
 * <p>Why a time-source injection: every event must carry a game-time
 * stamp, but the queue does not own a clock; the bot supplies its
 * world-time accessors at construction.
 *
 * <p>Implementation note: single-threaded by policy; every method runs
 * on the server tick thread, no synchronization is added deliberately.
 */
// contract: see boundaries.md Boundary D protocol (invariants 1-4, gap recovery)
public final class InMemoryEventQueue implements EventQueue {

    /** Queue capacity before oldest-entry eviction kicks in. */
    public static final int DEFAULT_CAP = 200;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Set<String> holders = new HashSet<>();
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private long lastEventId;
    private long resetMarker = 1L;
    private int droppedSinceLastPoll;

    /** One queued event paired with its immutable stream id. */
    private record Entry(long id, BotEvent event) {
    }

    /**
     * Creates a queue whose synthetic drop notices carry live stamps.
     *
     * @param daySupplier       in-game day counter accessor; never null
     * @param timeOfDaySupplier time-of-day ticks accessor; never null
     */
    public InMemoryEventQueue(LongSupplier daySupplier,
                              LongSupplier timeOfDaySupplier) {
        if (daySupplier == null || timeOfDaySupplier == null) {
            throw new IllegalArgumentException("suppliers must not be null");
        }
        this.daySupplier = daySupplier;
        this.timeOfDaySupplier = timeOfDaySupplier;
    }

    @Override
    public void push(BotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (entries.size() >= DEFAULT_CAP) {
            entries.pollFirst();
            droppedSinceLastPoll++;
        }
        entries.addLast(new Entry(++lastEventId, event));
    }

    @Override
    public EventBatch statusSnapshot(long sinceEventId) {
        List<BotEvent> page = new ArrayList<>();
        // Behind-consumer detection: if sinceEventId is older than
        // the oldest retained entry, the range between them was lost
        // to rotation. Surface it as a synthetic EVENT_GAP at the
        // head of the page so the harness sees the loss at the same
        // point it would have seen the lost events, not in some
        // later reconciliation step. Empty queue + stale cursor is
        // the worst case (everything was lost) - we still emit the
        // gap so the harness can tell "behind and the world moved on"
        // from "the bot is genuinely idle".
        if (sinceEventId >= 0 && sinceEventId < lastEventId) {
            long oldestId = entries.isEmpty()
                ? lastEventId + 1L     // everything past sinceEventId was lost
                : entries.peekFirst().id();
            if (sinceEventId < oldestId - 1) {
                long gap = oldestId - sinceEventId - 1;
                java.util.Map<String, String> gapAttrs =
                    new java.util.LinkedHashMap<>();
                gapAttrs.put("count", String.valueOf(gap));
                gapAttrs.put("since", String.valueOf(sinceEventId));
                gapAttrs.put("oldest", String.valueOf(oldestId));
                page.add(new BotEvent(EventKind.EVENT_GAP,
                    daySupplier.getAsLong(),
                    timeOfDaySupplier.getAsLong(),
                    true, java.util.Map.copyOf(gapAttrs),
                    "events between since=" + sinceEventId
                        + " and oldest=" + oldestId
                        + " were lost; call getState() and re-poll from "
                        + oldestId));
            }
        }
        for (Entry e : entries) {
            if (e.id() > sinceEventId) {
                page.add(e.event());
            }
        }
        int dropped = droppedSinceLastPoll;
        droppedSinceLastPoll = 0;
        if (dropped > 0) {
            page.add(new BotEvent(EventKind.EVENT_DROPPED,
                daySupplier.getAsLong(), timeOfDaySupplier.getAsLong(),
                false, java.util.Map.of("count", String.valueOf(dropped)),
                dropped + " events could not be recorded"));
        }
        return new EventBatch(
            List.copyOf(page), dropped, lastEventId, resetMarker);
    }

    @Override
    public void lock(String holder) {
        if (holder == null || holder.isBlank()) {
            throw new IllegalArgumentException("holder must not be blank");
        }
        holders.add(holder);
    }

    @Override
    public boolean unlock(String holder) {
        return holders.remove(holder);
    }

    @Override
    public boolean isLocked() {
        return !holders.isEmpty();
    }

    @Override
    public long resetAt() {
        return resetMarker;
    }

    /**
     * Wipe entries for a simulated restart. Ids stay monotonic so stale
     * cursors can never replay pre-reset pages; only {@code resetAt}
     * tells consumers their cursor is void.
     */
    @Override
    public void reset() {
        entries.clear();
        resetMarker++;
    }
}
