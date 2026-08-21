package com.mcbot.mcbotserver.api.event;

import java.util.Map;

/**
 * One entry on the boundary-D event stream.
 *
 * <p>Contract: see boundaries.md Boundary D protocol invariant 1 — the
 * bot stamps the game-time ({@code day} + {@code t}); the harness never
 * recomputes it. Invariant 4 — {@code urgent} means "the bot is making
 * a wrong decision because it does not know this", i.e. information
 * freshness, not importance.
 *
 * @param kind   registered vocabulary key from {@link EventKind} or a
 *               later registration; never null
 * @param day    in-game day counter at emission; monotonic per world
 * @param t      in-game time-of-day in ticks (0..23999) at emission
 * @param urgent true when freshness is decision-critical right now
 * @param attrs  structured attributes keyed by name; never null,
 *               possibly empty
 * @param text   human-readable rendering for logging and fallback
 *               display; never null, possibly blank for pure-attr events
 */
public record BotEvent(
    String kind,
    long day,
    long t,
    boolean urgent,
    Map<String, String> attrs,
    String text) {

    /**
     * Creates a validated event envelope.
     *
     * @param kind   must not be null or blank
     * @param day    any non-negative value
     * @param t      must be within 0..23999 inclusive
     * @param urgent freshness flag as defined above
     * @param attrs  must not be null
     * @param text   must not be null
     */
    public BotEvent {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (day < 0) {
            throw new IllegalArgumentException(
                "day must not be negative");
        }
        if (t < 0 || t > 23999) {
            throw new IllegalArgumentException(
                "t must be in 0..23999, got " + t);
        }
        attrs = Map.copyOf(attrs);
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
