package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Arrive exactly at one block cell.
 *
 * <p>Contract: see boundaries.md decision 7. Tolerance belongs in
 * {@link GoalNear}; this is the strict point goal.
 *
 * @param target the cell that satisfies the goal; never null
 */
public record GoalBlock(CellPos target) implements Goal {

    /**
     * Creates a validated goal.
     *
     * @param target must not be null
     */
    public GoalBlock {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
    }

    @Override
    public boolean isInGoal(CellPos pos) {
        return pos != null && pos.equals(target);
    }

    @Override
    public String describe() {
        return "GoalBlock(" + target.x() + "," + target.y() + "," + target.z() + ")";
    }
}
