package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.goal.GoalDistance;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * The plan-progress fuse (boundaries.md decision ledger 20; landed
 * as issue 0001 Ruling a, Path A): three OR criteria drive one
 * accumulator, and {@link PlanProgressFuse#STUCK_WINDOW} ticks
 * without progress fire it.
 *
 * <p>Criteria, evaluated against latched minima:
 * <ol>
 *   <li>waypoint index advanced (discrete, exact)</li>
 *   <li>goal distance closed by more than
 *       {@link PlanProgressFuse#STUCK_EPSILON}</li>
 *   <li>current-waypoint 3D distance dropped by more than
 *       {@link PlanProgressFuse#STUCK_EPSILON} (latched-min tracked)</li>
 * </ol>
 *
 * <p>Invariants (ledger 20 - do not re-litigate here):
 * external replan triggers (exhaustion, offPath, freshness drop)
 * MUST NOT clear the accumulator - only real plan-progress
 * ({@link #onProgress()}) or the fuse itself firing resets it. Plan
 * adoption resets the observation latches (via {@link #onAdopted})
 * so the new plan starts a fresh observation window, but NOT the
 * accumulator.
 *
 * <p>The 3D distance criterion 3 is correct under Path A (per-cell
 * waypoint gap, move-at-waypoint steering, both pinned in ledger
 * 20). If either assumption breaks, this class must be revisited
 * (criterion 3 reverts to arclength-style polyline projection per
 * Path B).
 *
 * <p>Implementation note: single-threaded, tick-thread-local like all
 * follower state.
 */
final class PlanProgressFuse {

    /**
     * Ticks without plan-progress before the progress fuse fires.
     * Frame: time (ticks), no spatial frame.
     */
    public static final int STUCK_WINDOW = 20;

    /**
     * New-min margin in metres for the plan-progress latched
     * minimum. A candidate new score (criterion 3 -
     * currentWaypointDistance3D, or criterion 2 - goalDistance)
     * must beat the latched value by at least this margin to count
     * as progress. Frame: 3D distance (metres). The previous
     * per-tick motion-detector threshold used the same constant
     * (0.01 m) so PR-2's redefinition of "what 0.01 means" is the
     * only semantics change; the value carries over.
     *
     * <p>Why margin, not equality: without a margin, in-place jitter
     * micro-noise would mint 0.001 m new minima and the fuse
     * would never trip on a waterfall column (the latched value
     * would only decrease by sub-margin amounts per tick). The
     * waterfall characterization test in
     * {@code LimboCharacterizationGateTest} exposes this immediately
     * if the margin is left at zero.
     */
    public static final double STUCK_EPSILON = 0.01;

    private int ticksSincePlanProgress;
    // Observation latches, initialised to "no prior observation"
    // sentinels so the first tick after plan adoption counts as
    // progress (criterion fires against the sentinel).
    private int lastWaypointIndex = -1;
    private double lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
    private double lastGoalDistance = Double.POSITIVE_INFINITY;
    // Latch preventing repeat STUCK reports across cooldown cycles.
    private boolean stuckLatched;

    /**
     * Compute the plan-progress score for this tick. Returns true if
     * any of the three OR criteria fires against the latched values;
     * updates the latches for the criteria that fire so the next
     * call can detect further progress.
     *
     * @param cursor        the active plan cursor; never null
     * @param position      the body's current position; never null
     * @param goalDistance  distance to the goal's target set; never
     *                      null. Position-space and goal-aware
     *                      (decision 56): for a range band this is
     *                      the band-edge distance, so an outward kite
     *                      counts as progress, not regression
     * @return true iff at least one criterion fired
     */
    boolean evaluate(WaypointCursor cursor, Vec3 position, GoalDistance goalDistance) {
        double goalDist = goalDistance.meters(position);

        boolean p1 = cursor.index() > lastWaypointIndex;
        if (p1) {
            lastWaypointIndex = cursor.index();
            // The latched criterion-3 minimum is bound to the waypoint
            // it was measured against; after an advance it must not
            // shadow the new, farther waypoint. Without this reset a
            // smoothed plan (issue 0005 P1.2, waypoint gaps > 1 cell)
            // leaves criterion 3 dormant for a whole segment, and a
            // detour leg that also opens the goal distance would
            // false-STUCK a moving body.
            lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
        }

        boolean p2 = goalDist < lastGoalDistance - STUCK_EPSILON;
        if (p2) {
            lastGoalDistance = goalDist;
        }

        boolean p3 = false;
        if (!cursor.exhausted()) {
            // Criterion 3 measures 3D, not XZ-only: vertical moves
            // (JumpUp, Drop) are real progress the latched min must
            // capture (ledger 20).
            CellPos wp = cursor.current();
            double wpDist = position.distanceTo(wp.x() + 0.5, wp.y() + 0.5, wp.z() + 0.5);
            p3 = wpDist < lastWaypoint3DDistance - STUCK_EPSILON;
            if (p3) {
                lastWaypoint3DDistance = wpDist;
            }
        }

        return p1 || p2 || p3;
    }

    /** A criterion fired: reset the accumulator and clear the latch. */
    void onProgress() {
        ticksSincePlanProgress = 0;
        stuckLatched = false;
    }

    /** No criterion fired: accumulate one silent tick. */
    void onStall() {
        ticksSincePlanProgress++;
    }

    /**
     * Whether the fuse is hot enough to report: window reached and
     * not already latched.
     *
     * @return true iff a STUCK verdict is due
     */
    boolean shouldFire() {
        return !stuckLatched && ticksSincePlanProgress >= STUCK_WINDOW;
    }

    /**
     * Record that the STUCK verdict was emitted; suppresses repeats
     * until real progress clears it.
     */
    void latch() {
        stuckLatched = true;
    }

    /**
     * Whether the STUCK verdict has already been emitted for this
     * stall.
     *
     * @return true iff latched
     */
    boolean latched() {
        return stuckLatched;
    }

    /**
     * Ticks without plan-progress, for the keepalive snapshot.
     *
     * @return accumulated silent-tick count
     */
    int ticksWithoutProgress() {
        return ticksSincePlanProgress;
    }

    /**
     * Reset the observation latches for an adopted plan so the new
     * plan starts a fresh observation window. The accumulator
     * (ticksWithoutProgress) is deliberately NOT reset - that is
     * the ledger-20 invariant: external replan does not
     * clear the fuse. lastWaypointIndex takes the CURRENT index
     * (not a sentinel) so the next evaluate call does not fire
     * criterion 1 as a false positive. lastGoalDistance also stays -
     * the goal cell is the same across replans, the latched value
     * remains meaningful.
     *
     * @param currentIndex the cursor index the adopted plan starts at
     */
    void onAdopted(int currentIndex) {
        lastWaypointIndex = currentIndex;
        lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
    }
}
