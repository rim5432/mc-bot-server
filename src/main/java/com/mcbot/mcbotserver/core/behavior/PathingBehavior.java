package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stage-0 mover shell: walk straight at the goal, declare arrival by
 * the goal predicate, fuse on a displacement window.
 *
 * <p>Contract: see ADR-0004 D3/D4 — SUCCESS comes only from the Goal
 * predicate; STUCK is declared here because this behavior owns motion
 * history; timeoutTicks enforcement arrives with GotoCommand in
 * Stage 1. Path search is intentionally absent (straight-line slice);
 * A* replaces the guts in Stage 2 without touching callers.
 *
 * <p>Stuck rule: while motion is intended, if the pose moves less than
 * {@value #STUCK_EPSILON} blocks across any pair inside the last
 * {@value #STUCK_WINDOW} poses, the fuse trips once and re-arms after
 * real movement resumes.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see ADR-0004 D3 (stuck detection lives inside behaviors)
public final class PathingBehavior implements Behavior {

    /** Fuse window length in ticks. */
    public static final int STUCK_WINDOW = 20;

    /** Displacement below this counts as "not moving", in blocks. */
    public static final double STUCK_EPSILON = 0.01;

    private final String name;
    private final PoseSource poseSource;
    private final Deque<Vec3> recentPoses = new ArrayDeque<>();
    private boolean stuckLatched;

    /**
     * Fine-grained body position. Doubles are load-bearing here: the
     * stuck fuse measures sub-cell motion, and a block-cell source
     * reads zero while a slowly accelerating body crosses its own cell
     * (the false-trip bug that shipped this record).
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
     * Creates a mover over a pose source.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param poseSource body position accessor; never null
     */
    public PathingBehavior(String name, PoseSource poseSource) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (poseSource == null) {
            throw new IllegalArgumentException(
                "poseSource must not be null");
        }
        this.name = name;
        this.poseSource = poseSource;
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
        recordPose(pose);

        CellPos cell = new CellPos(
            (int) Math.floor(pose.x()),
            (int) Math.floor(pose.y()),
            (int) Math.floor(pose.z()));
        if (directive.goal().isInGoal(cell)) {
            stuckLatched = false;
            return ExecutionReport.success();
        }

        double moved = windowDisplacement();
        if (!stuckLatched && moved < STUCK_EPSILON
            && recentPoses.size() >= STUCK_WINDOW) {
            stuckLatched = true;
            return ExecutionReport.stuck(
                "displacement < " + STUCK_EPSILON + " over "
                    + STUCK_WINDOW + " ticks");
        }
        if (moved > STUCK_EPSILON) {
            stuckLatched = false;
        }

        submitMotionClaims(directive, pose, actor);
        return ExecutionReport.running();
    }

    private void recordPose(Vec3 pose) {
        if (recentPoses.size() >= STUCK_WINDOW) {
            recentPoses.pollFirst();
        }
        recentPoses.addLast(pose);
    }

    private double windowDisplacement() {
        if (recentPoses.isEmpty()) {
            return Double.MAX_VALUE;
        }
        Vec3 oldest = recentPoses.peekFirst();
        return poseSource.get().distanceTo(oldest);
    }

    private void submitMotionClaims(Directive directive, Vec3 pose,
                                    Actor actor) {
        CellPos target = nearestGoalCell(directive);
        double dx = target.x() + 0.5 - pose.x();
        double dz = target.z() + 0.5 - pose.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        int movePriority = 10;

        actor.submit(new Claim(Channel.MOVE, movePriority, name,
            new Intent.Move(1.0, 0, false, false)));
        actor.submit(new Claim(Channel.ROT, movePriority, name,
            new Intent.Look(yaw, 0f)));
    }

    /**
     * The anchor cell to steer toward. Goal is sealed, so a new variant
     * must edit this method's permits list first; the terminal throw
     * turns any future miss into a crash-at-write-site instead of a
     * silent wrong-direction walk (Java 17 has no exhaustive-switch
     * patterns yet).
     *
     * @param directive the active mission's directive; never null
     * @return the steering anchor cell; never null
     */
    private CellPos nearestGoalCell(Directive directive) {
        var goal = directive.goal();
        if (goal instanceof com.mcbot.mcbotserver.api.goal.GoalBlock b) {
            return b.target();
        }
        if (goal instanceof com.mcbot.mcbotserver.api.goal.GoalNear n) {
            return n.center();
        }
        throw new IllegalStateException(
            "unhandled goal variant: " + goal.getClass());
    }

    /**
     * Test hook: how far the body traveled inside the current window.
     *
     * @return distance between oldest buffered pose and now
     */
    double currentWindowDisplacement() {
        return windowDisplacement();
    }
}
