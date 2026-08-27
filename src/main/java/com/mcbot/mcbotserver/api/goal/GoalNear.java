package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Arrive within a Chebyshev radius of a center cell — the "stand
 * next to it" goal used by the GotoCommand tolerance field.
 *
 * <p>Contract: see boundaries.md decision 7 and decision 18 (GotoCommand
 * tolerance maps to this construction).
 *
 * @param center the cell to approach; never null
 * @param range  inclusive Chebyshev tolerance in cells; non-negative
 */
public record GoalNear(CellPos center, int range) implements Goal {

    /**
     * Creates a validated goal.
     *
     * @param center must not be null
     * @param range  must not be negative
     */
    public GoalNear {
        if (center == null) {
            throw new IllegalArgumentException("center must not be null");
        }
        if (range < 0) {
            throw new IllegalArgumentException("range must not be negative");
        }
    }

    @Override
    public boolean isInGoal(CellPos pos) {
        return pos != null && pos.chebyshevTo(center) <= range;
    }

    @Override
    public String describe() {
        return "GoalNear(" + center.x() + "," + center.y() + "," + center.z() + ",r=" + range + ")";
    }
}
