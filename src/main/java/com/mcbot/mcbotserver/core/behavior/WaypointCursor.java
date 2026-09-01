package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import java.util.List;

/**
 * The active plan's waypoint chain and the cursor into it. Owns the
 * "where along the plan are we" state; the follower owns decisions,
 * this owns positions.
 *
 * <p>Contract: see boundaries.md decision 8 (movement five-tuple) and
 * issue 0001 §2 settled facts - waypoint reach is XZ-only
 * ({@code Math.hypot(dx, dz)} against {@link PathingBehavior#WAYPOINT_REACH});
 * Y is intentionally ignored for same-Y touch detection, the goal predicate
 * remains the 3D cell-equality authority for arrival. A vertical guard in
 * {@link #advance} (issue 0017) refuses to consume waypoints more than one
 * block above the floor on XZ reach alone — those are merged climb or
 * staircase segments owned by {@link #advanceClimb}.
 *
 * <p>Implementation note: single-threaded, tick-thread-local like all
 * follower state.
 */
final class WaypointCursor {

    private List<CellPos> waypoints = List.of();
    private int waypointIndex;

    /**
     * Install a new plan and point at the first cell worth steering
     * toward. Index 0 is skipped: it is the cell we are standing in.
     * A single-cell plan leaves the cursor at 0.
     *
     * @param plan the adopted waypoint chain; never null
     */
    void set(List<CellPos> plan) {
        waypoints = plan;
        waypointIndex = plan.size() > 1 ? 1 : 0;
    }

    /** Drop the plan; the cursor reads empty afterwards. */
    void clear() {
        waypoints = List.of();
        waypointIndex = 0;
    }

    /**
     * Whether no plan is installed.
     *
     * @return true iff the chain is empty
     */
    boolean isEmpty() {
        return waypoints.isEmpty();
    }

    /**
     * Whether every waypoint has been consumed.
     *
     * @return true iff the cursor passed the last cell
     */
    boolean exhausted() {
        return waypointIndex >= waypoints.size();
    }

    /**
     * The cell to consume next.
     *
     * @return the current waypoint; never null
     */
    CellPos current() {
        return waypoints.get(waypointIndex);
    }

    /**
     * The steering target: the current waypoint, clamped to the last
     * cell once the cursor runs past the end (steering keeps pushing
     * toward the goal neighbourhood while the mission winds down).
     *
     * @return the cell to steer toward; never null
     */
    CellPos steerTarget() {
        return waypoints.get(Math.min(waypointIndex, waypoints.size() - 1));
    }

    /**
     * Cursor position within the chain.
     *
     * @return zero-based waypoint index
     */
    int index() {
        return waypointIndex;
    }

    /**
     * Chain length.
     *
     * @return number of waypoints in the active plan
     */
    int size() {
        return waypoints.size();
    }

    /**
     * The waypoint behind the cursor: the far end of the segment
     * currently being walked. Smoothing (issue 0005 P1.2) makes
     * segments span many cells, so drift is measured against the
     * segment, not the endpoint; this accessor supplies that far
     * end. Reads as the current waypoint itself when no predecessor
     * exists (single-cell plan).
     *
     * @return the walked segment's origin; never null
     */
    CellPos previous() {
        return waypoints.get(Math.max(0, waypointIndex - 1));
    }

    /**
     * The terminal waypoint of the chain - the arrival brake scales
     * drive by remaining distance to it (issue 0005 P2.2).
     *
     * @return the last waypoint; never null on an installed plan
     */
    CellPos last() {
        return waypoints.get(waypoints.size() - 1);
    }

    /**
     * Consume every waypoint the body has reached. A waypoint counts
     * as touched when the body stands in its cell OR its XZ distance
     * to the waypoint centre is within
     * {@link PathingBehavior#WAYPOINT_REACH}; Y is ignored for touch
     * purposes (see class contract).
     *
     * <p>Vertical guard (issue 0017): a waypoint more than one block
     * above the body's floor is a vertical segment — a merged ladder
     * climb or a uniform staircase — and must NOT be consumed on
     * horizontal reach alone. {@code PlanSmoother.collapseRuns}
     * collapses a 5-cell climb into one waypoint at the top; without
     * this guard the bot standing one cell west of the ladder (within
     * 0.8 m XZ of the top waypoint, 5 m below it) consumes the entire
     * climb leg in one tick, skips to the platform waypoint, and
     * oscillates under the ceiling until the mission budget dies.
     * JumpUp legs (y+1) stay consumable: the body is mid-jump and
     * reaches that Y within a tick.
     *
     * @param position the body's current fine position; never null
     */
    void advance(Vec3 position) {
        CellPos floor = PathingBehavior.floorOf(position);
        while (waypointIndex < waypoints.size()) {
            CellPos wp = waypoints.get(waypointIndex);
            if (wp.y() > floor.y() + 1) {
                // Vertical segment: stop consuming and let the body
                // gain Y. advanceClimb owns exact-Y consumption once
                // the body enters the climbable column.
                break;
            }
            boolean sameCell = floor.equals(wp);
            double horizontal = Math.hypot(position.x() - (wp.x() + 0.5), position.z() - (wp.z() + 0.5));
            if (sameCell || horizontal <= PathingBehavior.WAYPOINT_REACH) {
                waypointIndex++;
            } else {
                break;
            }
        }
    }

    /**
     * Consume waypoints for a pure-vertical climb segment: a waypoint
     * counts as touched ONLY when the body's floor cell equals it (Y
     * included). The XZ-only reach in {@link #advance} would consume a
     * directly-overhead waypoint on the first tick (horizontal ~0 <=
     * WAYPOINT_REACH), exhausting the cursor before the body has
     * climbed a single block and triggering a replan loop. Vertical
     * segments route through this Y-aware variant; horizontal segments
     * keep the XZ-only contract.
     *
     * @param floor the body's current floor cell; never null
     */
    void advanceClimb(CellPos floor) {
        while (waypointIndex < waypoints.size()) {
            CellPos wp = waypoints.get(waypointIndex);
            if (floor.equals(wp)) {
                waypointIndex++;
            } else {
                break;
            }
        }
    }
}
