package com.mcbot.mcbotserver.api.world;

/**
 * Which consistency a WorldView read promises. Reserved now so the
 * future off-thread A* worker needs no interface change.
 *
 * <p>Contract: see boundaries.md decision 17b. {@link #SNAPSHOT} reads
 * a frozen view captured by the caller (planner side, possibly stale);
 * {@link #LIVE} reads current state and must never block (executor
 * side). Under the single-threaded Stage 0 implementation the two are
 * equivalent by construction; the distinction exists so the seam does
 * not have to be re-frozen later.
 */
public enum ViewMode {

    /** Frozen-at-capture read; safe to hand to an off-thread planner. */
    SNAPSHOT,

    /** Current-tick read; server-thread callers only, never blocks. */
    LIVE
}
