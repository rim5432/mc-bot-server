package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Helpers over the sealed {@link Goal} algebra. The cell extraction
 * used to be hand-rolled per consumer (pathing anchor, combat aim,
 * keepalive) and the copies had already split into throw vs
 * null-return flavors - one canonical extraction keeps a new Goal
 * variant a one-file change.
 */
public final class Goals {

    private Goals() {}

    /**
     * The single target cell of a locomotion goal. Exhaustive over the
     * sealed goal algebra: both current variants carry exactly one
     * cell, so a future variant without one must extend this method
     * explicitly rather than fall through silently.
     *
     * @param goal the goal to read; never null
     * @return the goal's target cell; never null
     * @throws IllegalStateException when a new Goal variant has not
     *         been taught to this extractor
     */
    public static CellPos cellOf(Goal goal) {
        if (goal instanceof GoalBlock b) {
            return b.target();
        }
        if (goal instanceof GoalNear n) {
            return n.center();
        }
        throw new IllegalStateException("unhandled goal variant: " + goal.getClass());
    }
}
