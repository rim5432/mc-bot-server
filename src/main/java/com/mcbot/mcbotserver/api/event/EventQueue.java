package com.mcbot.mcbotserver.api.event;

/**
 * Boundary-D event stream: bot is the producer, the harness polls at
 * its own cadence with a cursor.
 *
 * <p>Contract: see boundaries.md Boundary D protocol. Invariants owned
 * here: game-time stamping (caller supplies it, queue never invents),
 * no-survive-restart ({@link #reset()} bumps {@code resetAt} and wipes
 * entries), no-silent-drop (backpressure accounting plus a synthetic
 * EVENT_DROPPED entry), urgent-as-freshness (queue treats urgent as
 * ordinary payload — gating is a harness concern). The multi-holder
 * lock is reserved by the interface; Stage 0's reference implementation
 * supports any number of opaque tokens but nothing in the bot issues
 * more than one.
 *
 * <p>Implementation note: single-threaded by policy — all methods run
 * on the server tick thread; do not call from other threads.
 */
public interface EventQueue {

    /**
     * Enqueue one event; stamps are carried by the event itself.
     * When the capacity cap is reached, the oldest entry is dropped and
     * accounted per invariant 3.
     *
     * @param event the entry to append; must not be null
     */
    void push(BotEvent event);

    /**
     * Drain everything newer than the caller's cursor.
     *
     * @param sinceEventId cursor from the previous batch's
     *                     latestEventId; use 0 for a fresh consumer
     * @return the ordered page; never null
     */
    EventBatch statusSnapshot(long sinceEventId);

    /**
     * Acquire an opaque drain lock. Locks gate outflow decisions only;
     * intake and clearing are unaffected.
     *
     * @param holder opaque token identifying the consumer; must not be
     *               null or blank
     */
    void lock(String holder);

    /**
     * Release a previously acquired lock token.
     *
     * @param holder the exact token passed to {@link #lock(String)}
     * @return true if it was held and is now released; false otherwise
     */
    boolean unlock(String holder);

    /**
     * Query whether any holder currently holds a lock.
     *
     * @return true while at least one token is outstanding
     */
    boolean isLocked();

    /**
     * Current monotonic bot-restart marker.
     *
     * @return value starting at 1; increases by one on every reset
     */
    long resetAt();

    /**
     * Wipe all queued entries and bump {@code resetAt}. Models "the bot
     * restarted"; cursors held by consumers become stale by protocol,
     * not by bookkeeping — ids stay monotonic so old pages can never be
     * replayed after a reset.
     */
    void reset();
}
