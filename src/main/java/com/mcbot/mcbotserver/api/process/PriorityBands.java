package com.mcbot.mcbotserver.api.process;

/**
 * Priority-band vocabulary for the task channel. Numbers only order
 * processes inside their band; they never compete with the reflex
 * layer, which sits outside the numeric space entirely.
 *
 * <p>Contract: see boundaries.md decision ledger items 6 and 11;
 * ADR-0002 section 2 (winner-take-all with banded budgets). Bands:
 *
 * <ul>
 * <li>ROUTINE 0..99 — background maintenance (idle wander, follow).</li>
 * <li>TACTICAL 100..199 — combat-era tasks; DefendProcess parks at 150.</li>
 * <li>200..999 — reserved, rejected at registration so future tiers
 * cannot silently collide.</li>
 * </ul>
 */
public final class PriorityBands {

    /** Lower bound of the routine band. */
    public static final int ROUTINE_MIN = 0;

    /** Upper bound of the routine band. */
    public static final int ROUTINE_MAX = 99;

    /** Lower bound of the tactical band. */
    public static final int TACTICAL_MIN = 100;

    /** Upper bound of the tactical band. */
    public static final int TACTICAL_MAX = 199;

    /** DefendProcess's parking spot inside the tactical band. */
    public static final int DEFEND_PRIORITY = 150;

    private PriorityBands() {
    }

    /**
     * Validate that a priority falls inside a defined band.
     *
     * @param priority the value to check
     * @return the same value when legal
     * @throws IllegalArgumentException when the value lands in the
     *         reserved range or below zero — registering such a process
     *         is a programming error, not a runtime condition
     */
    public static int requireLegal(int priority) {
        boolean routine = priority >= ROUTINE_MIN
            && priority <= ROUTINE_MAX;
        boolean tactical = priority >= TACTICAL_MIN
            && priority <= TACTICAL_MAX;
        if (!routine && !tactical) {
            throw new IllegalArgumentException(
                "priority " + priority
                    + " outside bands [0..99] and [100..199]");
        }
        return priority;
    }
}
