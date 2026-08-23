package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.pathing.AStarPathFinder;
import com.mcbot.mcbotserver.core.pathing.MoveGraph;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import com.mcbot.mcbotserver.core.world.SnapshotWorldView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Waypoint follower over the A* move graph: plans through the finder,
 * steers toward the next waypoint, and replans when the plan runs out,
 * drifts away, or the progress fuse fires.
 *
 * <p>Contract: see boundaries.md decisions 7/8 and ADR-0004 D3/D4 -
 * SUCCESS comes only from the goal predicate evaluated on the bot cell;
 * STUCK is declared here (this behavior owns plan-progress history) but
 * only when a recovery replan also failed - a frozen body with a fresh,
 * usable route stays silent and simply waits out the replan cooldown
 * with claims flowing. Replan triggers: plan exhausted, body drifted
 * beyond {@value #REPLAN_DISTANCE} of the active waypoint, or
 * {@value #STUCK_WINDOW} ticks without plan-progress.
 *
 * <p>Plan-progress tracking (issue 0001 §Ruling a, Path A): three OR
 * criteria - waypointIndex advanced, goalDistance decreased, or
 * currentWaypointDistance3D decreased by more than {@code STUCK_EPSILON}
 * metres - drive a single accumulator. The accumulator is immune to
 * replan triggers: external replan (exhaustion, offPath, freshness
 * drop) MUST NOT clear it (settled fact 2 in issue 0001 §2 documents
 * compliance for the prior motion detector, this fuse preserves it).
 * Only real plan-progress or the fuse itself firing resets the
 * accumulator. The motion detector (per-tick 3D displacement) was
 * removed: it was strictly weaker than plan-progress in the
 * "moving but not progressing" case (limbo body in free fall -
 * 4.3 cells per 20 ticks of motion, zero waypoint-progress) and is
 * the root cause of the waterfall-column silent deadlock.
 *
 * <p>Vertical gate (issue 0001 fix 6): trigger evaluation
 * (plan-progress score, offPath, exhausted, fuse, replan request,
 * STUCK report) runs only when the body is in ground contact.
 * Airborne ticks skip all of it - a ballistic trajectory's drift
 * and progress scores are meaningless for "should I replan?" because
 * the active plan was authored for the take-off cell, not the
 * landing cell, and re-evaluating mid-flight burns cooldown slots
 * on snapshots the freshness check will discard. The landing edge
 * ({@code onGround} transitions false -&gt; true) bypasses the
 * replan cooldown: the moment of contact is when the new position first
 * matters, and a stale plan from before take-off must be replaced
 * immediately rather than after the cooldown elapses.
 *
 * <p>Implementation note: runs on the server tick thread only. With a
 * {@link PlanWorker} injected, searches execute off-thread on an
 * immutable {@link SnapshotWorldView} and are adopted only when still
 * fresh (goal unchanged, start cell still ours) - the staleness guard
 * of decision 17b. All behavior state stays tick-thread-local; the
 * future is the sole cross-thread object.
 */
// contract: see ADR-0004 D3 + numen-notes.md section 18 (replan ladder)
public final class PathingBehavior implements Behavior {

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
     * <p>Why margin, not equality: without a margin, 原地振荡
     * micro-noise would刷出 0.001 m new minima and the fuse
     * would never trip on a waterfall column (the latched value
     * would only decrease by sub-margin amounts per tick). The
     * waterfall characterization test in
     * {@code LimboCharacterizationGateTest} exposes this immediately
     * if the margin is left at zero.
     */
    public static final double STUCK_EPSILON = 0.01;

    /**
     * Horizontal reach that counts as "waypoint touched", in metres.
     * Frame: XZ only (`Math.hypot(dx, dz)` in
     * {@link WaypointCursor#advance} and {@link #distanceToWaypoint}).
     * Y is intentionally ignored - a position directly above a waypoint
     * is treated as "not at the waypoint" for reach purposes; the
     * goal predicate is the 3D cell-equality authority for arrival.
     */
    public static final double WAYPOINT_REACH = 0.8;

    /**
     * Drift from the active waypoint that forces a fresh plan, in
     * metres. Frame: XZ only (same {@code Math.hypot(dx, dz)} as
     * {@code WAYPOINT_REACH}). Y drift is intentionally ignored;
     * the three-branch failure mode documented in issue 0001 §3
     * rides on this XZ-only choice. Pinned by
     * {@code PathingBehaviorFrameGateTest.replanDistanceIsXZOnly}.
     */
    public static final double REPLAN_DISTANCE = 3.0;

    /**
     * Minimum ticks between deliberate replans. Frame: time (ticks),
     * no spatial frame.
     */
    public static final int REPLAN_COOLDOWN = 10;

    /**
     * Soft wall-clock cap for one off-thread search. The node budget
     * is the safety net; this is the CPU throttle the worker owns
     * (numen-notes section 17).
     */
    public static final long PLAN_WALL_CLOCK_MS = 50;

    /**
     * Freshness tolerance in cells (issue 0001 fix 5 / Ruling (c).
     * The bot's current cell must be within Chebyshev distance
     * {@code FRESHNESS_CELLS} of the {@code pendingStart} the
     * search was launched from for the result to be adopted.
     * {@code FRESHNESS_CELLS == 1} is the v1 vocabulary's
     * tightest tolerance: every BasicMoves move is max-Chebyshev
     * 1, so a 1-cell drift is the maximum the bot can accumulate
     * in any single tick.
     */
    public static final int FRESHNESS_CELLS = 1;

    private final String name;
    private final PositionSource positionSource;
    private final OnGroundSource onGroundSource;
    private final MoveGraph graph;
    private final PlanWorker worker;
    private final WaypointCursor cursor = new WaypointCursor();
    private boolean stuckLatched;
    private boolean neverPlanned = true;
    private int ticksSincePlan = REPLAN_COOLDOWN;
    private int ticksSincePlanProgress;
    private int ticksSinceAdoption;
    // Previous tick's onGround state, used to detect the landing
    // edge (false -> true). Initialised to false so the first
    // grounded tick is correctly classified as a "landing" - the
    // edge is harmless then because neverPlanned short-circuits
    // the replan gate anyway.
    private boolean wasOnGround;
    // Plan-progress latches for issue 0001 §Ruling a. Initialised
    // to "no prior observation" sentinels so the first tick after
    // plan adoption counts as progress (criterion fires against
    // the sentinel), and reset to sentinels on plan adoption so
    // a new plan starts a fresh observation window.
    private int lastWaypointIndex = -1;
    private double lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
    private double lastGoalDistance = Double.POSITIVE_INFINITY;

    // Async plan bookkeeping; all fields touched on the tick thread
    // only - the future itself is the sole cross-thread object.
    private CompletableFuture<AStarPathFinder.PathResult> pendingPlan;
    private Goal pendingGoal;
    private CellPos pendingStart;

    /**
     * Fine-grained body position. Doubles are load-bearing here: the
     * stuck fuse measures sub-cell motion, and a block-cell source
     * reads zero while a slowly accelerating body crosses its own cell.
     */
    @FunctionalInterface
    public interface PositionSource {

        /**
         * Current body position in world doubles.
         *
         * @return the position; never null
         */
        Vec3 get();
    }

    /**
     * Body contact state accessor. Drives the vertical gate in
     * {@link #tick} (issue 0001 fix 6): trigger evaluation is
     * suppressed when the body is airborne and bypasses the replan
     * cooldown on the landing edge.
     *
     * <p>For real entities the wiring is {@code () -> body.onGround()};
     * for offline tests the two-argument and three-argument
     * constructors use {@code () -> true} (always grounded), which
     * is correct because the rig's position is stationary.
     */
    @FunctionalInterface
    public interface OnGroundSource {

        /**
         * Whether the body's feet are on a solid surface this tick.
         *
         * @return true iff the body is in ground contact
         */
        boolean isOnGround();
    }

    /**
     * Creates a follower over one move graph, planning synchronously
     * on the tick thread, assuming the body is always grounded.
     * Offline tests and tiny graphs only - real bots use the worker
     * constructor (or the four-/five-argument variants below).
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param positionSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     */
    public PathingBehavior(String name, PositionSource positionSource,
                           MoveGraph graph) {
        this(name, positionSource, () -> true, graph, null);
    }

    /**
     * Creates a follower that plans off-thread through the given
     * worker, assuming the body is always grounded. Offline tests
     * and tiny graphs only - real bots use the five-argument
     * constructor below.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param positionSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     * @param worker     search executor; never null
     */
    public PathingBehavior(String name, PositionSource positionSource,
                           MoveGraph graph, PlanWorker worker) {
        this(name, positionSource, () -> true, graph, worker);
    }

    /**
     * Creates a follower over one move graph, planning synchronously
     * on the tick thread, with an explicit ground-state accessor.
     * Offline tests and tiny graphs only - real bots use the
     * five-argument constructor.
     *
     * @param name            stable identity for claims and
     *                        diagnostics; never null or blank
     * @param positionSource      body position accessor; never null
     * @param onGroundSource  body contact-state accessor; never null
     * @param graph           edge supplier for planning; never null
     */
    public PathingBehavior(String name, PositionSource positionSource,
                           OnGroundSource onGroundSource,
                           MoveGraph graph) {
        this(name, positionSource, onGroundSource, graph, null);
    }

    /**
     * Creates a follower that plans off-thread through the given
     * worker, with an explicit ground-state accessor. Searches
     * read an immutable snapshot captured here on the tick thread;
     * results are adopted only when still fresh (goal unchanged,
     * start cell still ours) - decision 17b's staleness guard.
     *
     * @param name            stable identity for claims and
     *                        diagnostics; never null or blank
     * @param positionSource      body position accessor; never null
     * @param onGroundSource  body contact-state accessor; never null
     * @param graph           edge supplier for planning; never null
     * @param worker          search executor; never null
     */
    public PathingBehavior(String name, PositionSource positionSource,
                           OnGroundSource onGroundSource,
                           MoveGraph graph, PlanWorker worker) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.positionSource = positionSource;
        this.onGroundSource = onGroundSource;
        this.graph = graph;
        // Null is the deliberate synchronous-planning mode used by
        // offline tests; see the two-, three-, and four-argument
        // constructors above.
        this.worker = worker;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ExecutionReport tick(WorldView world, Directive directive,
                                Actor actor) {
        if (directive == null) {
            return ExecutionReport.running();
        }
        Vec3 position = positionSource.get();
        ticksSincePlan++;
        ticksSinceAdoption++;

        // Vertical gate (issue 0001 fix 6). Replan triggers fire only
        // while the body is in ground contact; the landing edge
        // (false -> true) bypasses the replan cooldown so the
        // post-landing position is the basis for the next plan, not a
        // stale pre-take-off plan. Mid-air ticks still steer toward
        // the current waypoint (the bot moves through the air on
        // its trajectory), but they do not score progress, do not
        // accumulate the fuse, and do not request replans - those
        // would all be measured against a snapshot the freshness
        // check will discard on landing.
        boolean onGround = onGroundSource.isOnGround();
        boolean landing = onGround && !wasOnGround;
        wasOnGround = onGround;
        boolean evaluateTriggers = onGround || landing;

        CellPos cell = floorOf(position);
        if (directive.goal().isInGoal(cell)) {
            resetPlan();
            return ExecutionReport.success();
        }

        // Adopt finished searches before trigger evaluation so a
        // fresh route is usable in the same tick it arrived.
        // Adoption is not gated by the vertical gate: a plan that
        // completes during flight is still adopted (the next
        // ground tick immediately has a usable plan), and the
        // freshness check is the cell-based guard regardless of
        // ground state.
        adoptCompletedPlan(directive.goal(), cell);

        if (evaluateTriggers) {
            // Plan-progress score (issue 0001 §Ruling a, Path A).
            // Three OR criteria; any one counts as progress and
            // resets the accumulator. External replan (exhaustion,
            // offPath, freshness drop) MUST NOT clear the
            // accumulator - only real plan-progress or the fuse
            // itself does. The previous motion detector is gone:
            // it was strictly weaker than plan-progress for the
            // "moving but not progressing" case (limbo body in
            // free fall - large per-tick 3D displacement, zero
            // waypoint-progress).
            if (updatePlanProgress(position, directive.goal())) {
                ticksSincePlanProgress = 0;
                stuckLatched = false;
            } else {
                ticksSincePlanProgress++;
            }

            boolean fuseCondition = !stuckLatched
                && ticksSincePlanProgress >= STUCK_WINDOW;
            boolean offPath = !cursor.exhausted()
                && distanceToWaypoint(position,
                    cursor.current()) > REPLAN_DISTANCE;
            boolean exhausted = cursor.exhausted();

            // Replan cooldown bypassed on the landing edge: contact
            // is the first tick the body's position is meaningful for
            // pathing, and a stale plan from before take-off must
            // be replaced immediately rather than after the
            // cooldown elapses.
            if ((fuseCondition || offPath || exhausted)
                && (neverPlanned || landing
                    || ticksSincePlan >= REPLAN_COOLDOWN)) {
                neverPlanned = false;
                ticksSincePlan = 0;
                requestPlan(world, cell, directive.goal());
            }

            if (cursor.isEmpty()) {
                // A search still running is not an answer: report
                // RUNNING rather than inventing NO_PATH for a
                // question in flight.
                if (planInFlight()) {
                    return ExecutionReport.running();
                }
                // Definitive no-path right now: fail cleanly; the
                // mission process owns the terminal transition
                // from here. A fuse-triggered failure reports
                // STUCK (physically immovable), a planning-side
                // one reports NO_PATH.
                if (fuseCondition) {
                    stuckLatched = true;
                    return ExecutionReport.failed("STUCK");
                }
                return ExecutionReport.failed("NO_PATH");
            }
            if (fuseCondition && !stuckLatched) {
                // Fresh-plan grace expired while the body stayed
                // frozen and the replan cooldown is still blocking
                // the next attempt. Report once, latch, keep
                // claims flowing; movement or a later successful
                // plan clears the latch.
                stuckLatched = true;
                return ExecutionReport.stuck(
                    "frozen between replan cooldowns");
            }
        }

        cursor.advance(position);
        if (cursor.exhausted()) {
            return ExecutionReport.running();
        }
        steerTowardCurrentWaypoint(position, actor);
        return ExecutionReport.running();
    }

    private void requestPlan(WorldView world, CellPos start,
                             Goal goal) {
        if (worker == null) {
            planSync(world, start, goal);
            return;
        }
        // One search in flight at a time; the cooldown gate above
        // spaces requests, this guard absorbs same-tick re-triggers.
        if (planInFlight()) {
            return;
        }
        pendingGoal = goal;
        pendingStart = start;
        CellPos anchor = anchorCell(goal);
        var snapshot = SnapshotWorldView.capture(world, start, anchor);
        pendingPlan = worker.submit(snapshot, graph, start, goal,
            Heuristic.euclideanTo(anchor),
            AStarPathFinder.DEFAULT_NODE_BUDGET, PLAN_WALL_CLOCK_MS);
    }

    /**
     * Consume a finished off-thread search. Freshness is decided
     * against the CURRENT directive and position: a plan for an abandoned
     * goal or from a cell we have left is stale and discarded whole -
     * adopting it would steer toward where we no longer want to go.
     */
    private void adoptCompletedPlan(Goal currentGoal, CellPos cell) {
        if (pendingPlan == null || !pendingPlan.isDone()) {
            return;
        }
        AStarPathFinder.PathResult result;
        try {
            result = pendingPlan.join();
        } catch (RuntimeException cancelled) {
            result = AStarPathFinder.PathResult.failed(0);
        }
        pendingPlan = null;

        // Freshness check (issue 0001 fix 5 / Ruling (c): cell-equality
        // is too strict. A* takes time; the bot moves during the
        // search. Walking speed 0.215 b/tick x a 4-5 tick search
        // already crosses 1 cell; strict cell-equality would discard
        // every result, eating the fuse accumulator through churn.
        // Chebyshev distance 1 (max(|dx|, |dy|, |dz|) <= 1) accepts
        // any cell the bot can reach in 1 step in the move graph
        // (Walk, ClimbUp, Drop, Diagonal - all max-Chebyshev 1).
        // Note: 1 cell is the v1 vocabulary's tightest tolerance;
        // future moves that span more cells per step must revisit
        // this constant in lockstep.
        boolean fresh = currentGoal.equals(pendingGoal)
            && chebyshevDistance(cell, pendingStart) <= FRESHNESS_CELLS;
        pendingGoal = null;
        pendingStart = null;
        if (!fresh) {
            // The discarded answer was for a question we no longer
            // ask. Prime the ladder so THIS tick's trigger evaluation
            // immediately requests a plan for the current goal - the
            // cooldown guards executing routes, not unanswered goals,
            // and reporting NO_PATH without asking would be a lie.
            ticksSincePlan = REPLAN_COOLDOWN;
            return;
        }
        if (!result.reachedGoal() && result.waypoints().isEmpty()) {
            cursor.clear();
            return;
        }
        // Skip index 0: it is the cell we are standing in.
        cursor.set(result.waypoints());
        ticksSinceAdoption = 0;
        // Plan adopted: reset the plan-progress latches so the new
        // plan starts a fresh observation window. The fuse
        // accumulator (ticksSincePlanProgress) is NOT reset - that
        // is the invariant from issue 0001 §Ruling a: external
        // replan does not clear the fuse. lastWaypointIndex is
        // set to the CURRENT waypointIndex (not a sentinel) so
        // the next updatePlanProgress call does not fire
        // criterion 1 as a false positive.
        lastWaypointIndex = cursor.index();
        lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
        // lastGoalDistance stays - the goal cell is the same
        // across replans, the latched value remains meaningful.
    }

    private boolean planInFlight() {
        return pendingPlan != null && !pendingPlan.isDone();
    }

    private void planSync(WorldView world, CellPos start, Goal goal) {
        CellPos anchor = anchorCell(goal);
        var finder = new AStarPathFinder(graph,
            Heuristic.euclideanTo(anchor),
            AStarPathFinder.DEFAULT_NODE_BUDGET);
        var result = finder.compute(world, start, goal,
            Heuristic.euclideanTo(anchor));
        if (!result.reachedGoal() && result.waypoints().isEmpty()) {
            cursor.clear();
            return;
        }
        // Skip index 0: it is the cell we are standing in.
        cursor.set(result.waypoints());
        ticksSinceAdoption = 0;
        // See adoptCompletedPlan for the latch-reset rationale.
        // lastWaypointIndex is set to the CURRENT waypointIndex
        // (not a sentinel) so the next updatePlanProgress call
        // does not fire criterion 1 as a false positive.
        lastWaypointIndex = cursor.index();
        lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
    }

    private void resetPlan() {
        cursor.clear();
        neverPlanned = true;
        ticksSincePlan = REPLAN_COOLDOWN;
        pendingPlan = null;
        pendingGoal = null;
        pendingStart = null;
    }

    /**
     * Compute the plan-progress score for this tick (issue 0001
     * §Ruling a, Path A). Returns true if any of the three OR
     * criteria fires against the latched values; updates the latches
     * for the criteria that fire so the next call can detect
     * further progress.
     *
     * <p>Three OR criteria:
     * <ol>
     *   <li>{@code waypointIndex > lastWaypointIndex} (discrete
     *       advance along the path)</li>
     *   <li>{@code goalDistance < lastGoalDistance - STUCK_EPSILON}
     *       (closure toward the goal by more than the margin)</li>
     *   <li>current waypoint 3D distance dropped by more than
     *       {@code STUCK_EPSILON} metres (latched-min tracked)</li>
     * </ol>
     *
     * <p>The latches are reset to sentinels ({@code lastWaypointIndex
     * = -1}, {@code lastWaypoint3DDistance = +inf}, {@code
     * lastGoalDistance = +inf}) on plan adoption, so the first
     * observation after a new plan is adopted always fires
     * criterion 1 (any waypointIndex > -1) and criterion 3 (any
     * distance is less than +inf). The accumulator is NOT reset on
     * plan adoption; the first-observation "progress" is the
     * initialization, not a real signal.
     *
     * <p>The 3D distance criterion 3 is correct under Path A
     * (per-cell waypoint gap, move-at-waypoint steering, both
     * pinned in the issue). If either assumption breaks, this
     * method must be revisited (criterion 3 reverts to
     * arclength-style polyline projection per Path B).
     *
     * @param position the body's current position; never null
     * @param goal the directive's goal; never null
     * @return true iff at least one criterion fired
     */
    private boolean updatePlanProgress(Vec3 position, Goal goal) {
        CellPos goalCell = anchorCell(goal);
        double goalDist = position.distanceTo(goalCell.x() + 0.5,
            goalCell.y() + 0.5, goalCell.z() + 0.5);

        boolean p1 = cursor.index() > lastWaypointIndex;
        if (p1) {
            lastWaypointIndex = cursor.index();
        }

        boolean p2 = goalDist < lastGoalDistance - STUCK_EPSILON;
        if (p2) {
            lastGoalDistance = goalDist;
        }

        boolean p3 = false;
        if (!cursor.exhausted()) {
            // Criterion 3 measures 3D, not XZ-only: vertical moves
            // (ClimbUp, Drop) are real progress the latched min must
            // capture (issue 0001 Ruling a).
            CellPos wp = cursor.current();
            double wpDist = position.distanceTo(wp.x() + 0.5,
                wp.y() + 0.5, wp.z() + 0.5);
            p3 = wpDist < lastWaypoint3DDistance - STUCK_EPSILON;
            if (p3) {
                lastWaypoint3DDistance = wpDist;
            }
        }

        return p1 || p2 || p3;
    }

    /**
     * Chebyshev distance between two cells (max of the per-axis
     * deltas). Used by the freshness check in {@link #adoptCompletedPlan}:
     * a search result is fresh if the bot's current cell is within
     * {@link #FRESHNESS_CELLS} Chebyshev units of the cell the
     * search was launched from. Chebyshev (not Euclidean) is the
     * right metric here because the move graph's maximum step in
     * any single tick is max-Chebyshev 1 (Walk, ClimbUp, Drop,
     * Diagonal) - a 1-cell Euclidean step would reject valid moves.
     *
     * @param a one cell; must not be null
     * @param b another cell; must not be null
     * @return non-negative Chebyshev distance in cells
     */
    private static int chebyshevDistance(CellPos a, CellPos b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, Math.max(dy, dz));
    }

    /**
     * Snapshot of the plan-progress state for the keepalive event
     * (issue 0001 fix 2). The harness reads this on every KEEPALIVE
     * emission to distinguish "alive and progressing" from "alive
     * but the planner is silent". Zero behaviour change to the
     * pipeline - the snapshot is read at the BotController emission
     * point and never feeds back into PathingBehavior.
     *
     * <p>Why this method, not getters: a single {@code Map} return
     * keeps the emission point in BotController free of cast /
     * null-bridges per field. The map keys are stable strings; the
     * caller copies them into a {@code BotEvent} attrs map.
     *
     * @param position     the body's current position (sampled by the caller
     *                 in the same tick); never null
     * @param goalCell the active goal cell (sampled by the caller
     *                 from the directive); may be null if no
     *                 directive is active this tick
     * @return a fresh {@code LinkedHashMap} of attribute strings;
     *         never null
     */
    public Map<String, String> keepaliveAttrs(
        Vec3 position, CellPos goalCell) {
        Map<String, String> attrs =
            new LinkedHashMap<>();
        attrs.put("pose", position.x() + "," + position.y() + "," + position.z());
        attrs.put("waypointIndex", String.valueOf(cursor.index()));
        attrs.put("waypointsTotal", String.valueOf(cursor.size()));
        // Renamed from "ticksSinceProgress" in PR-2. The field
        // now counts ticks without PLAN-PROGRESS (one of the
        // three Ruling (a) criteria), not ticks without MOTION.
        // The key name change is intentional and the S-F
        // verifier-side impact is "ignore unknown attrs" (issue
        // 0001 §7 fix-2 contract).
        attrs.put("ticksSincePlanProgress",
            String.valueOf(ticksSincePlanProgress));
        attrs.put("ticksSincePlan", String.valueOf(ticksSincePlan));
        attrs.put("planAge", String.valueOf(ticksSinceAdoption));
        if (goalCell != null) {
            attrs.put("goalCell",
                goalCell.x() + "," + goalCell.y() + "," + goalCell.z());
        }
        return attrs;
    }

    private double distanceToWaypoint(Vec3 position, CellPos wp) {
        return Math.hypot(position.x() - (wp.x() + 0.5),
            position.z() - (wp.z() + 0.5));
    }

    private void steerTowardCurrentWaypoint(Vec3 position, Actor actor) {
        CellPos wp = cursor.steerTarget();
        double dx = wp.x() + 0.5 - position.x();
        double dz = wp.z() + 0.5 - position.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Jump only when the current waypoint sits above the body's
        // floor cell - the execution half of a ClimbUp edge.
        // Auto-step clears rises <= 0.5 (slabs); taller steps need
        // this thrust to leave the ground.
        boolean climbing = wp.y() > floorOf(position).y();
        actor.submit(new Claim(Channel.MOVE, 10, name,
            new Intent.Move(1.0, 0, climbing, false)));
        actor.submit(new Claim(Channel.ROT, 10, name,
            new Intent.Look(yaw, 0f)));
    }

    static CellPos floorOf(Vec3 position) {
        return new CellPos((int) Math.floor(position.x()),
            (int) Math.floor(position.y()), (int) Math.floor(position.z()));
    }

    /**
     * The steering anchor of the CURRENT plan's terminal waypoint.
     * Exhaustive over the sealed goal algebra.
     *
     * @param goal the mission goal; never null
     * @return anchor cell; never null
     */
    private static CellPos anchorCell(Goal goal) {
        if (goal instanceof GoalBlock b) {
            return b.target();
        }
        if (goal instanceof GoalNear n) {
            return n.center();
        }
        throw new IllegalStateException(
            "unhandled goal variant: " + goal.getClass());
    }
}
