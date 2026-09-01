package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Pure goal predicate: the only authority on "have we arrived". Search
 * algorithms and behaviors evaluate goals; they never hard-code arrival
 * logic — that is what keeps new goals from touching behavior code.
 *
 * <p>Contract: see boundaries.md decision 7 (Goal is a pure data
 * abstraction) and ADR-0004 D3 (SUCCESS is decided solely by evaluating
 * this predicate on the bot position). Sealed over the current algebra so
 * consumers like PathingBehavior can switch exhaustively — a new goal
 * variant is a compiler event, never a silent ClassCastException.
 */
public sealed interface Goal permits GoalBlock, GoalNear, GoalRange {

    /**
     * Arrival test.
     *
     * @param pos the bot's current block cell; must not be null
     * @return true when the cell satisfies the goal
     */
    boolean isInGoal(CellPos pos);

    /**
     * Human-readable identity for diagnostics and task summaries;
     * stable for the same construction.
     *
     * @return short description, e.g. {@code GoalNear(x,y,z,r)};
     *         never null
     */
    String describe();
}
