package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Arrive inside a distance band around a center cell — the "hold
 * range" goal for kiting and standoff combat. Unlike {@link GoalNear},
 * which is satisfied once inside the radius and never backs the body
 * up, a range band has a lower bound: a cell too close to the center
 * is NOT in the goal, so the planner paths outward to the nearest
 * band edge. This is the vocabulary growth that closes issue 0018
 * (combat/pathing keep-range channel) without touching the claim
 * model — processes swap GoalNear for GoalRange directly.
 *
 * <p>Distance frame is Chebyshev, matching {@link GoalNear} — the
 * band is a square ring in cell space, not a Euclidean circle.
 *
 * <p>Contract: see boundaries.md decision 7 (Goal is a pure data
 * abstraction) and decision 54 (range-band vocabulary growth).
 *
 * @param center the cell to hold range around; never null
 * @param min    inclusive inner radius in Chebyshev cells; non-negative,
 *               less than or equal to {@code max}
 * @param max    inclusive outer radius in Chebyshev cells; positive,
 *               greater than or equal to {@code min}
 */
// contract: see boundaries.md decision 7 + decision 54
public record GoalRange(CellPos center, int min, int max) implements Goal {

    /**
     * Creates a validated range-band goal.
     *
     * @param center must not be null
     * @param min    must not be negative
     * @param max    must be positive and not less than min
     */
    public GoalRange {
        if (center == null) {
            throw new IllegalArgumentException("center must not be null");
        }
        if (min < 0) {
            throw new IllegalArgumentException("min must not be negative");
        }
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive");
        }
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
    }

    @Override
    public boolean isInGoal(CellPos pos) {
        if (pos == null) {
            return false;
        }
        int dist = pos.chebyshevTo(center);
        return dist >= min && dist <= max;
    }

    @Override
    public String describe() {
        return "GoalRange(" + center.x() + "," + center.y() + "," + center.z() + ",min=" + min + ",max=" + max + ")";
    }
}
