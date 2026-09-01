package com.mcbot.mcbotserver.api.goal;

import com.mcbot.mcbotserver.api.pathing.Heuristic;
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
        if (goal instanceof GoalRange r) {
            return r.center();
        }
        throw new IllegalStateException("unhandled goal variant: " + goal.getClass());
    }

    /**
     * The search heuristic appropriate for the goal's geometry.
     * GoalBlock and GoalNear keep the historical straight-line pull
     * to the anchor; GoalRange uses Chebyshev distance to the nearest
     * band edge so a body inside the minimum radius is pulled outward
     * rather than toward the center it is already too close to. The
     * band-edge distance is zero on the goal set and admissible
     * elsewhere (each move changes Chebyshev distance by at most one).
     *
     * @param goal the goal to build a heuristic for; never null
     * @return an admissible heuristic; never null
     */
    public static Heuristic heuristicOf(Goal goal) {
        if (goal instanceof GoalRange r) {
            return from -> {
                int dist = from.chebyshevTo(r.center());
                if (dist < r.min()) {
                    return r.min() - dist;
                }
                if (dist > r.max()) {
                    return dist - r.max();
                }
                return 0.0;
            };
        }
        return Heuristic.euclideanTo(cellOf(goal));
    }

    /**
     * The progress-tier distance from a body position to the goal's
     * target set. Point goals keep the exact historical math the
     * plan-progress fuse and the no-path ledger were tuned against:
     * 3D Euclidean to the anchor cell centre. GoalRange uses the
     * continuous Chebyshev band-edge distance - the position-space
     * analogue of {@link #heuristicOf(Goal)} - because distance to
     * the CENTER reads a correct outward kite as regression
     * (search layer and progress layer split the same way; see
     * {@link GoalDistance}).
     *
     * @param goal the goal to measure against; never null
     * @return a distance function over positions; never null
     */
    public static GoalDistance distanceOf(Goal goal) {
        if (goal instanceof GoalRange r) {
            return from -> {
                double dx = Math.abs(from.x() - r.center().x() - 0.5);
                double dy = Math.abs(from.y() - r.center().y() - 0.5);
                double dz = Math.abs(from.z() - r.center().z() - 0.5);
                double dist = Math.max(dx, Math.max(dy, dz));
                if (dist < r.min()) {
                    return r.min() - dist;
                }
                if (dist > r.max()) {
                    return dist - r.max();
                }
                return 0.0;
            };
        }
        CellPos anchor = cellOf(goal);
        return from -> from.distanceTo(anchor.x() + 0.5, anchor.y() + 0.5, anchor.z() + 0.5);
    }
}
