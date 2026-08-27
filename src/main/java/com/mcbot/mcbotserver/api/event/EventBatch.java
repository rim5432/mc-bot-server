package com.mcbot.mcbotserver.api.event;

import java.util.List;

/**
 * One polled page of the boundary-D event stream.
 *
 * <p>Contract: see boundaries.md Boundary D protocol invariants 2 and 3.
 * Events arrive in id order, strictly greater than the caller's cursor.
 * A non-zero {@code droppedCount} is always accompanied by a synthetic
 * {@link EventKind#EVENT_DROPPED} entry inside {@code events} — drops are
 * never silent. A changed {@code resetAt} means every earlier event and
 * cursor is void; the harness must reconcile via the state snapshot.
 *
 * @param events        entries with id &gt; sinceEventId, arrival order;
 *                      never null, possibly empty
 * @param droppedCount  entries discarded by backpressure since the last
 *                       poll; zero when nothing was dropped
 * @param latestEventId cursor to pass as sinceEventId on the next poll
 * @param resetAt       monotonic bot-restart marker; changes when the
 *                      in-memory queue has been wiped
 */
// contract: see boundaries.md Boundary D protocol (event stream shape)
public record EventBatch(List<BotEvent> events, int droppedCount, long latestEventId, long resetAt) {

    /**
     * Creates a validated batch view.
     *
     * @param events        must not be null
     * @param droppedCount  must not be negative
     * @param latestEventId any value; monotonic across restarts
     * @param resetAt       positive monotonic marker
     */
    public EventBatch {
        events = List.copyOf(events);
        if (droppedCount < 0) {
            throw new IllegalArgumentException("droppedCount must not be negative");
        }
        if (resetAt < 1) {
            throw new IllegalArgumentException("resetAt starts at 1");
        }
    }

    /**
     * A copy narrowed to events whose kind starts with the given
     * prefix. Cursor-integrity events ({@link EventKind#EVENT_GAP},
     * {@link EventKind#EVENT_DROPPED}) always survive: they describe
     * the stream, not the world, and filtering them out would turn a
     * missed batch into a silent gap. The cursor fields
     * ({@code latestEventId}, {@code resetAt}, {@code droppedCount})
     * are the TRUE stream values, never the filtered subset's - the
     * caller's next poll must advance by what existed, not by what
     * was shown.
     *
     * @param kindPrefix prefix such as {@code "TASK_"}; empty or
     *                   blank returns this batch unchanged
     * @return the narrowed view; never null
     */
    // contract: see issues/0011-harness-surface-convergence.md D3
    //            (narrowing never lies about cursor position)
    public EventBatch narrowedToKindPrefix(String kindPrefix) {
        if (kindPrefix == null || kindPrefix.isBlank()) {
            return this;
        }
        var kept = new java.util.ArrayList<BotEvent>(events.size());
        for (BotEvent event : events) {
            if (event.kind().startsWith(kindPrefix)
                    || EventKind.EVENT_GAP.equals(event.kind())
                    || EventKind.EVENT_DROPPED.equals(event.kind())) {
                kept.add(event);
            }
        }
        return new EventBatch(kept, droppedCount, latestEventId, resetAt);
    }
}
