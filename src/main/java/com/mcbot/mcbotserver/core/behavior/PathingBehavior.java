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

import java.util.List;
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
     * {@link #advanceReachedWaypoints} and {@link #distanceToWaypoint}).
     * Y is intentionally ignored - a pose directly above a waypoint
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

    private final String name;
    private final PoseSource poseSource;
    private final MoveGraph graph;
    private final PlanWorker worker;
    private List<CellPos> waypoints = List.of();
    private int waypointIndex;
    private boolean stuckLatched;
    private boolean neverPlanned = true;
    private int ticksSincePlan = REPLAN_COOLDOWN;
    private int ticksSincePlanProgress;
    private int ticksSinceAdoption;
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
    public interface PoseSource {

        /**
         * Current body position in world doubles.
         *
         * @return the pose; never null
         */
        Vec3 get();
    }

    /**
     * Creates a follower over one move graph, planning synchronously
     * on the tick thread. Offline tests and tiny graphs only - real
     * bots use the worker constructor.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param poseSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     */
    public PathingBehavior(String name, PoseSource poseSource,
                           MoveGraph graph) {
        this(name, poseSource, graph, null);
    }

    /**
     * Creates a follower that plans off-thread through the given
     * worker. Searches read an immutable snapshot captured here on the
     * tick thread; results are adopted only when still fresh (goal
     * unchanged, start cell still ours) - decision 17b's staleness
     * guard.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param poseSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     * @param worker     search executor; never null
     */
    public PathingBehavior(String name, PoseSource poseSource,
                           MoveGraph graph, PlanWorker worker) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.poseSource = poseSource;
        this.graph = graph;
        // Null is the deliberate synchronous-planning mode used by
        // offline tests; see the two constructors above.
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
        Vec3 pose = poseSource.get();
        ticksSincePlan++;
        ticksSinceAdoption++;

        CellPos cell = floorOf(pose);
        if (directive.goal().isInGoal(cell)) {
            resetPlan();
            return ExecutionReport.success();
        }

        // Adopt finished searches before trigger evaluation so a fresh
        // route is usable in the same tick it arrived.
        adoptCompletedPlan(directive.goal(), cell);

        // Plan-progress score (issue 0001 §Ruling a, Path A). Three
        // OR criteria; any one counts as progress and resets the
        // accumulator. External replan (exhaustion, offPath,
        // freshness drop) MUST NOT clear the accumulator - only real
        // plan-progress or the fuse itself does. The previous
        // motion detector is gone: it was strictly weaker than
        // plan-progress for the "moving but not progressing" case
        // (limbo body in free fall - large per-tick 3D displacement,
        // zero waypoint-progress).
        if (updatePlanProgress(pose, directive.goal())) {
            ticksSincePlanProgress = 0;
            stuckLatched = false;
        } else {
            ticksSincePlanProgress++;
        }

        boolean fuseCondition = !stuckLatched
            && ticksSincePlanProgress >= STUCK_WINDOW;
        boolean offPath = waypointIndex < waypoints.size()
            && distanceToWaypoint(pose,
                waypoints.get(waypointIndex)) > REPLAN_DISTANCE;
        boolean exhausted = waypointIndex >= waypoints.size();

        if ((fuseCondition || offPath || exhausted)
            && (neverPlanned || ticksSincePlan >= REPLAN_COOLDOWN)) {
            neverPlanned = false;
            ticksSincePlan = 0;
            requestPlan(world, cell, directive.goal());
        }

        if (waypoints.isEmpty()) {
            // A search still running is not an answer: report RUNNING
            // rather than inventing NO_PATH for a question in flight.
            if (planInFlight()) {
                return ExecutionReport.running();
            }
            // Definitive no-path right now: fail cleanly; the mission
            // process owns the terminal transition from here. A fuse
            // -triggered failure reports STUCK (physically immovable),
            // a planning-side one reports NO_PATH.
            if (fuseCondition) {
                stuckLatched = true;
                return ExecutionReport.failed("STUCK");
            }
            return ExecutionReport.failed("NO_PATH");
        }
        if (fuseCondition && !stuckLatched) {
            // Fresh-plan grace expired while the body stayed frozen and
            // the replan cooldown is still blocking the next attempt.
            // Report once, latch, keep claims flowing; movement or a
            // later successful plan clears the latch.
            stuckLatched = true;
            return ExecutionReport.stuck(
                "frozen between replan cooldowns");
        }

        advanceReachedWaypoints(pose);
        if (waypointIndex >= waypoints.size()) {
            return ExecutionReport.running();
        }
        steerTowardCurrentWaypoint(pose, actor);
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
     * against the CURRENT directive and pose: a plan for an abandoned
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

        boolean fresh = currentGoal.equals(pendingGoal)
            && cell.equals(pendingStart);
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
            waypoints = List.of();
            waypointIndex = 0;
            return;
        }
        // Skip index 0: it is the cell we are standing in.
        waypoints = result.waypoints();
        waypointIndex = waypoints.size() > 1 ? 1 : 0;
        ticksSinceAdoption = 0;
        // Plan adopted: reset the plan-progress latches so the new
        // plan starts a fresh observation window. The fuse
        // accumulator (ticksSincePlanProgress) is NOT reset - that
        // is the invariant from issue 0001 §Ruling a: external
        // replan does not clear the fuse. lastWaypointIndex is
        // set to the CURRENT waypointIndex (not a sentinel) so
        // the next updatePlanProgress call does not fire
        // criterion 1 as a false positive.
        lastWaypointIndex = waypointIndex;
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
            waypoints = List.of();
            waypointIndex = 0;
            return;
        }
        // Skip index 0: it is the cell we are standing in.
        waypoints = result.waypoints();
        waypointIndex = waypoints.size() > 1 ? 1 : 0;
        ticksSinceAdoption = 0;
        // See adoptCompletedPlan for the latch-reset rationale.
        // lastWaypointIndex is set to the CURRENT waypointIndex
        // (not a sentinel) so the next updatePlanProgress call
        // does not fire criterion 1 as a false positive.
        lastWaypointIndex = waypointIndex;
        lastWaypoint3DDistance = Double.POSITIVE_INFINITY;
    }

    private void resetPlan() {
        waypoints = List.of();
        waypointIndex = 0;
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
     * @param pose the body's current pose; never null
     * @param goal the directive's goal; never null
     * @return true iff at least one criterion fired
     */
    private boolean updatePlanProgress(Vec3 pose, Goal goal) {
        CellPos goalCell = anchorCell(goal);
        double goalDist = distance3D(pose,
            goalCell.x() + 0.5, goalCell.y() + 0.5, goalCell.z() + 0.5);

        boolean p1 = waypointIndex > lastWaypointIndex;
        if (p1) {
            lastWaypointIndex = waypointIndex;
        }

        boolean p2 = goalDist < lastGoalDistance - STUCK_EPSILON;
        if (p2) {
            lastGoalDistance = goalDist;
        }

        boolean p3 = false;
        if (waypointIndex < waypoints.size()) {
            CellPos wp = waypoints.get(waypointIndex);
            double wpDist = distance3D(pose,
                wp.x() + 0.5, wp.y() + 0.5, wp.z() + 0.5);
            p3 = wpDist < lastWaypoint3DDistance - STUCK_EPSILON;
            if (p3) {
                lastWaypoint3DDistance = wpDist;
            }
        }

        return p1 || p2 || p3;
    }

    /**
     * 3D Euclidean distance from a pose to a 3D point. Used by the
     * plan-progress score for goal and waypoint distance checks.
     * Issue 0001 §Ruling a: criterion 3 is 3D (not XZ-only) because
     * the move vocabulary has vertical moves (ClimbUp, Drop) and
     * the latched min must capture vertical progress too.
     */
    private static double distance3D(Vec3 pose, double x, double y, double z) {
        double dx = pose.x() - x;
        double dy = pose.y() - y;
        double dz = pose.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
     * @param pose     the body's current pose (sampled by the caller
     *                 in the same tick); never null
     * @param goalCell the active goal cell (sampled by the caller
     *                 from the directive); may be null if no
     *                 directive is active this tick
     * @return a fresh {@code LinkedHashMap} of attribute strings;
     *         never null
     */
    public java.util.Map<String, String> keepaliveAttrs(
        Vec3 pose, CellPos goalCell) {
        java.util.Map<String, String> attrs =
            new java.util.LinkedHashMap<>();
        attrs.put("pose", pose.x() + "," + pose.y() + "," + pose.z());
        attrs.put("waypointIndex", String.valueOf(waypointIndex));
        attrs.put("waypointsTotal", String.valueOf(waypoints.size()));
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

    private void advanceReachedWaypoints(Vec3 pose) {
        while (waypointIndex < waypoints.size()) {
            CellPos wp = waypoints.get(waypointIndex);
            boolean sameCell = floorOf(pose).equals(wp);
            double horizontal = Math.hypot(
                pose.x() - (wp.x() + 0.5), pose.z() - (wp.z() + 0.5));
            if (sameCell || horizontal <= WAYPOINT_REACH) {
                waypointIndex++;
            } else {
                break;
            }
        }
    }

    private double distanceToWaypoint(Vec3 pose, CellPos wp) {
        return Math.hypot(pose.x() - (wp.x() + 0.5),
            pose.z() - (wp.z() + 0.5));
    }

    private void steerTowardCurrentWaypoint(Vec3 pose, Actor actor) {
        CellPos wp = waypoints.get(Math.min(waypointIndex,
            waypoints.size() - 1));
        double dx = wp.x() + 0.5 - pose.x();
        double dz = wp.z() + 0.5 - pose.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        actor.submit(new Claim(Channel.MOVE, 10, name,
            new Intent.Move(1.0, 0, false, false)));
        actor.submit(new Claim(Channel.ROT, 10, name,
            new Intent.Look(yaw, 0f)));
    }

    private static CellPos floorOf(Vec3 pose) {
        return new CellPos((int) Math.floor(pose.x()),
            (int) Math.floor(pose.y()), (int) Math.floor(pose.z()));
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
