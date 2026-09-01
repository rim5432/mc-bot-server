package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * Continuous distance from a body position to a goal's target set,
 * in metres. The progress-tier counterpart of
 * {@link com.mcbot.mcbotserver.api.pathing.Heuristic}: the heuristic
 * estimates cost-to-go over CELLS for the search, this measures
 * progress over POSITIONS for the follower - the plan-progress fuse
 * and the no-path witness ledger both need "is the body getting
 * closer to the goal set", which for a range band is NOT the
 * distance to the center (moving outward, the correct kite, reads as
 * regression against a center anchor).
 *
 * <p>Zero on the goal set, monotonically larger further away; exact
 * per-goal geometry is chosen by {@link Goals#distanceOf(Goal)}.
 *
 * <p>Contract: see boundaries.md decision 7 (Goal is a pure data
 * abstraction; geometry dispatch lives in Goals, not on the sealed
 * interface).
 */
// contract: see boundaries.md decision 7
@FunctionalInterface
public interface GoalDistance {

    /**
     * Distance from a body position to the goal's target set.
     *
     * @param from the position to measure from; never null
     * @return non-negative metres; zero when the position is on the
     *         goal set
     */
    double meters(Vec3 from);
}
