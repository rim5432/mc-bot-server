package com.mcbot.mcbotserver.api.pathing;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Search-side estimate of remaining cost from a cell to the goal.
 *
 * <p>Contract: see boundaries.md decision 7 - goals are predicates and
 * carry no search math, so the heuristic is an injected function. A*
 * stays admissible only if implementations never overestimate; the
 * finder does not enforce this (unforceable without knowing true
 * costs) and trusts the supplier.
 */
@FunctionalInterface
public interface Heuristic {

    /**
     * Estimated cost-to-go from a cell.
     *
     * @param from the cell to estimate from; never null
     * @return non-negative estimate; lower is closer to the goal
     */
    double estimate(CellPos from);

    /**
     * Straight-line distance to a fixed target, inflated by 1% -
     * admissible for our edge costs while breaking f-score ties
     * toward the goal.
     *
     * @param target the goal cell; never null
     * @return a heuristic aiming at that cell
     */
    static Heuristic euclideanTo(CellPos target) {
        return from -> from.distanceTo(target) * 1.001;
    }
}
