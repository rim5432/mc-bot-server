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

import java.util.List;

/**
 * Waypoint follower over the A* move graph: plans through the finder,
 * steers toward the next waypoint, and replans when the plan runs out,
 * drifts away, or the progress fuse fires.
 *
 * <p>Contract: see boundaries.md decisions 7/8 and ADR-0004 D3/D4 -
 * SUCCESS comes only from the goal predicate evaluated on the bot cell;
 * STUCK is declared here (this behavior owns motion history) but only
 * when a recovery replan also failed - a frozen body with a fresh,
 * usable route stays silent and simply waits out the replan cooldown
 * with claims flowing. Replan triggers: plan exhausted, body drifted
 * beyond {@value #REPLAN_DISTANCE} of the active waypoint, or
 * {@value #STUCK_WINDOW} ticks without displacement.
 *
 * <p>Progress tracking is a plain per-tick delta counter, immune to
 * replanning: clearing a motion window on every plan once created a
 * livelock where a frozen body never accumulated enough window to trip
 * the fuse (three debug rounds; do not regress).
 *
 * <p>Implementation note: runs on the server tick thread only; the
 * Stage 2 async replan worker will own threading around the same
 * finder.
 */
// contract: see ADR-0004 D3 + numen-notes.md section 18 (replan ladder)
public final class PathingBehavior implements Behavior {

    /** Ticks without displacement before the progress fuse fires. */
    public static final int STUCK_WINDOW = 20;

    /** Per-tick displacement above this counts as progress. */
    public static final double STUCK_EPSILON = 0.01;

    /** Horizontal reach that counts as "waypoint touched". */
    public static final double WAYPOINT_REACH = 0.8;

    /** Drift from the active waypoint that forces a fresh plan. */
    public static final double REPLAN_DISTANCE = 3.0;

    /** Minimum ticks between deliberate replans. */
    public static final int REPLAN_COOLDOWN = 10;

    private final String name;
    private final PoseSource poseSource;
    private final MoveGraph graph;
    private List<CellPos> waypoints = List.of();
    private int waypointIndex;
    private boolean stuckLatched;
    private boolean neverPlanned = true;
    private int ticksSincePlan = REPLAN_COOLDOWN;
    private int ticksSinceProgress;
    private Vec3 lastProgressPose;

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
     * Creates a follower over one move graph.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param poseSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     */
    public PathingBehavior(String name, PoseSource poseSource,
                           MoveGraph graph) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.poseSource = poseSource;
        this.graph = graph;
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

        // Progress tracking: per-tick delta against the last recorded
        // pose, immune to replanning. Only real displacement resets the
        // counter - a fresh plan around an immovable body must not.
        if (lastProgressPose == null
            || pose.distanceTo(lastProgressPose) > STUCK_EPSILON) {
            ticksSinceProgress = 0;
            lastProgressPose = pose;
            stuckLatched = false;
        } else {
            ticksSinceProgress++;
        }

        CellPos cell = floorOf(pose);
        if (directive.goal().isInGoal(cell)) {
            resetPlan();
            return ExecutionReport.success();
        }

        boolean fuseCondition = !stuckLatched
            && ticksSinceProgress >= STUCK_WINDOW;
        boolean offPath = waypointIndex < waypoints.size()
            && distanceToWaypoint(pose,
                waypoints.get(waypointIndex)) > REPLAN_DISTANCE;
        boolean exhausted = waypointIndex >= waypoints.size();

        if ((fuseCondition || offPath || exhausted)
            && (neverPlanned || ticksSincePlan >= REPLAN_COOLDOWN)) {
            neverPlanned = false;
            ticksSincePlan = 0;
            plan(world, cell, directive.goal());
        }

        if (waypoints.isEmpty()) {
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

    private void plan(WorldView world, CellPos start, Goal goal) {
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
    }

    private void resetPlan() {
        waypoints = List.of();
        waypointIndex = 0;
        neverPlanned = true;
        ticksSincePlan = REPLAN_COOLDOWN;
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
