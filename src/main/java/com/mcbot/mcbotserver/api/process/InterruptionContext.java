package com.mcbot.mcbotserver.api.process;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Uniform snapshot carried on every preemption and every crash — the
 * shape ADR-0003 section 3 froze and ADR-0005 D2 reuses.
 *
 * <p>Contract: see ADR-0003 section 3 (resume revalidates the world
 * assumptions recorded here first) and ADR-0005 D2 (crash path captures
 * the same shape so downstream code never needs a second context type).
 *
 * @param tick            server tick counter at interruption
 * @param botPos          block cell the body occupied; never null
 * @param interruptedTask display name of the active mission, or the
 *                        empty string when none; never null
 * @param causeSummary    one-line cause ("reflex-preempt:FREEZE" or
 *                        "RuntimeException:boom"); never null
 * @param stackTrace      exception stack for crash contexts; empty
 *                        string for clean reflex preemptions; never null
 */
public record InterruptionContext(
        long tick, CellPos botPos, String interruptedTask, String causeSummary, String stackTrace) {

    /**
     * Creates a validated context.
     *
     * @param tick            non-negative
     * @param botPos          must not be null
     * @param interruptedTask must not be null
     * @param causeSummary    must not be null or blank
     * @param stackTrace      must not be null
     */
    public InterruptionContext {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must not be negative");
        }
        if (botPos == null) {
            throw new IllegalArgumentException("botPos must not be null");
        }
        if (interruptedTask == null) {
            throw new IllegalArgumentException("interruptedTask must not be null");
        }
        if (causeSummary == null || causeSummary.isBlank()) {
            throw new IllegalArgumentException("causeSummary must not be blank");
        }
        if (stackTrace == null) {
            throw new IllegalArgumentException("stackTrace must not be null");
        }
    }
}
