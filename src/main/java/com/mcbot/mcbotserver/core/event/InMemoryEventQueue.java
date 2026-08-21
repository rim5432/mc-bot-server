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
 * <p>Why a time-source injection: every event must carry a game-time
 * stamp, but the queue does not own a clock; the bot supplies its
 * world-time accessors at construction.
 *
 * <p>Implementation note: single-threaded by policy; every method runs
 * on the server tick thread, no synchronization is added deliberately.
 */
// contract: see boundaries.md Boundary D protocol (invariants 1-4)
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
