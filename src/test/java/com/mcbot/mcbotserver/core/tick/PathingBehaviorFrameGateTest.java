package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the coordinate-frame contract on PathingBehavior's distance
 * constants. Issue 0001 fix 1: 4 constants carry a frame convention
 * (3D Euclidean, XZ hypot, ticks) that is not derivable from the
 * value alone. These tests fail if a future change silently swaps
 * one frame for another.
 *
 * <p>The 4 frames are:
 * <ul>
 * <li>{@code STUCK_EPSILON} is 3D Euclidean (Y motion counts).</li>
 * <li>{@code WAYPOINT_REACH} is XZ only (Y offset ignored).</li>
 * <li>{@code REPLAN_DISTANCE} is XZ only (Y drift ignored).</li>
 * <li>{@code STUCK_WINDOW} and {@code REPLAN_COOLDOWN} are ticks
 * (no spatial frame; not pinned here).</li>
 * </ul>
 */
class PathingBehaviorFrameGateTest {

    /**
     * STUCK_EPSILON is 3D Euclidean: pure Y motion at 5x epsilon
     * (0.05 m/tick) for 25 ticks must NOT trip the progress fuse,
     * because per-tick 3D displacement is well above the threshold
     * and the counter keeps resetting. If a future change makes
     * the check XZ-only, the XZ component is 0 and the counter
     * would accumulate to 20 -> STUCK.
     */
    @Test
    void stuckEpsilonIs3DEuclidean() throws ReflectiveOperationException {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Walk forward 5 ticks so we have an active waypoint and
        // the first progress position is recorded.
        for (int i = 1; i <= 5; i++) {
            position[0] = new Vec3(0.5 + i * 0.2, 64, 0.5);
            mover.tick(world, directive, actor);
        }

        // Now drive 25 ticks of pure Y motion = 0.05 m/tick (5x
        // epsilon), XZ unchanged. Pre-fix-4 the 3D check sees 0.05
        // m/tick and resets the counter; an XZ-only check would
        // see 0 m/tick XZ and accumulate.
        boolean stuckFired = false;
        for (int i = 1; i <= 25; i++) {
            position[0] = new Vec3(position[0].x(),
                position[0].y() + 0.05, position[0].z());
            ExecutionReport r = mover.tick(world, directive, actor);
            if (r.status() == ExecutionReport.Status.FAILED
                && "STUCK".equals(r.reason())) {
                stuckFired = true;
                break;
            }
        }
        assertFalse(stuckFired,
            "STUCK must not fire on pure Y motion of 5x STUCK_EPSILON: "
            + "STUCK_EPSILON is 3D Euclidean, not XZ-only");
    }

    /**
     * WAYPOINT_REACH is XZ only: a position whose XZ is within the
     * reach but whose Y is 100 above the waypoint still consumes
     * the waypoint on the next tick (OR semantics, see issue 0001
     * §2). If a future change makes the reach 3D, the same position
     * would NOT consume the waypoint (Y diff >> 0.8) and the
     * waypointIndex would stay at the previous value.
     */
    @Test
    void waypointReachIgnoresY() throws ReflectiveOperationException {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // First tick: plan adopted. waypointIndex starts at 1
        // (skip 0 = current cell).
        mover.tick(world, directive, actor);
        int indexBefore = PathingTestAccess.waypointIndex(mover);
        List<CellPos> waypoints = PathingTestAccess.waypoints(mover);
        assertTrue(waypoints.size() > 1, "plan must have waypoints");
        assertEquals(1, indexBefore, "waypointIndex starts at 1 after plan adoption");

        // Place the position at XZ within 0.8 of the current waypoint
        // but Y 100 above. With OR semantics the waypoint must be
        // consumed.
        CellPos next = waypoints.get(indexBefore);
        position[0] = new Vec3(next.x() + 0.5, 164, next.z() + 0.5);
        mover.tick(world, directive, actor);

        int indexAfter = PathingTestAccess.waypointIndex(mover);
        assertTrue(indexAfter > indexBefore,
            "waypoint must be consumed when XZ is within "
            + PathingBehavior.WAYPOINT_REACH
            + " even if Y is 100 away: WAYPOINT_REACH is XZ only");
    }

    /**
     * REPLAN_DISTANCE is XZ only: a position that has drifted 5 cells
     * straight up (Y) but stays in the same XZ as the active
     * waypoint does NOT trigger an offPath replan. The drift
     * signal is horizontal-only; Y drift is intentionally outside
     * the drift trigger (issue 0001 §3 branch 2/3 territory).
     */
    @Test
    void replanDistanceIgnoresY() throws ReflectiveOperationException {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Walk to midpoint, adopt plan.
        for (int i = 1; i <= 10; i++) {
            position[0] = new Vec3(0.5 + i * 0.2, 64, 0.5);
            mover.tick(world, directive, actor);
        }

        // The plan has a waypoint near (2, 64, 0). The bot is
        // currently past that point, so waypointIndex has advanced
        // and the active waypoint is further along the path. We
        // want to verify: jumping to Y=+5 (5 cells up) with XZ
        // unchanged does NOT trigger a replan via offPath.
        // (Y=5 > REPLAN_DISTANCE if the check were 3D; under the
        // XZ-only check the drift is 0 and offPath is false.)
        int indexBefore = PathingTestAccess.waypointIndex(mover);
        position[0] = new Vec3(position[0].x(), 69, position[0].z());
        mover.tick(world, directive, actor);
        int indexAfter = PathingTestAccess.waypointIndex(mover);

        // The waypoint index should not have decreased (replan
        // would either advance or rewind; if the bot stayed on
        // its existing plan the index advances normally). The
        // stronger assertion is that no failed/no-path report
        // fires from a 3D-distance interpretation.
        ExecutionReport r = mover.tick(world, directive, actor);
        assertNotEquals(ExecutionReport.Status.FAILED, r.status(),
            "a 5-cell Y jump with XZ unchanged must not be "
            + "interpreted as drift: REPLAN_DISTANCE is XZ only");
        // Sanity: waypoint consumption still works in the XZ plane.
        assertTrue(indexAfter >= indexBefore,
            "waypoint index must not regress on a Y-only jump");
    }

    /**
     * REPLAN_DISTANCE is XZ distance to the WALKED SEGMENT (previous
     * to current waypoint), not to the current waypoint: smoothing
     * (issue 0005 P1.2) makes mid-leg waypoint distances large and
     * legitimate. A body mid-segment, ON the line, farther from the
     * current waypoint than REPLAN_DISTANCE must NOT trigger a
     * replan; a lateral departure beyond it must.
     */
    @Test
    void replanDriftIsXZToSegment()
        throws ReflectiveOperationException {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = { new Vec3(0.5, 64, 0.5) };
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        // Walk 11 ticks: past the departure hold and the replan
        // cooldown since the initial request, still making progress.
        for (int i = 1; i <= 11; i++) {
            position[0] = new Vec3(0.5 + i * 0.1, 64, 0.5);
            mover.tick(world, directive, actor);
        }
        int before = PathingTestAccess.ticksSincePlan(mover);
        assertTrue(before >= PathingBehavior.REPLAN_COOLDOWN,
            "cooldown must have expired for a request to be possible");

        // On the segment, 38 cells from the current waypoint (the
        // goal): not drift under segment semantics; under the old
        // waypoint semantics this distance would force a replan.
        position[0] = new Vec3(2.6, 64, 0.5);
        mover.tick(world, directive, actor);
        assertEquals(before + 1,
            PathingTestAccess.ticksSincePlan(mover),
            "mid-segment on the line is not drift");

        // Lateral departure beyond REPLAN_DISTANCE: replan fires.
        position[0] = new Vec3(2.6, 64, 4.5);
        mover.tick(world, directive, actor);
        assertEquals(0, PathingTestAccess.ticksSincePlan(mover),
            "lateral departure from the walked segment must trigger "
                + "a replan");
    }

    /**
     * GoalBlock.isInGoal is 3D cell equality: the goal predicate
     * treats a body one cell above the target as NOT at the goal.
     * If a future "fix" relaxed the predicate to "same XZ is
     * enough", the success verdict would fire when the body is
     * still climbing/falling to the actual Y, and the arrival
     * semantics in the S-B boundary would silently drift. This
     * test pins the 3D cell-equality contract.
     */
    @Test
    void goalBlockIsInGoalIs3DCellEquality() {
        com.mcbot.mcbotserver.api.goal.GoalBlock goal =
            new com.mcbot.mcbotserver.api.goal.GoalBlock(
                new CellPos(5, 64, 0));

        // Same cell: true.
        assertTrue(goal.isInGoal(new CellPos(5, 64, 0)),
            "same cell must satisfy the goal predicate");

        // Different Y: false (the cell equality is 3D, not 2.5D).
        // If someone "fixes" isInGoal to ignore Y so the bot
        // claims success while still climbing/falling, the
        // S-B arrival semantic silently breaks.
        assertFalse(goal.isInGoal(new CellPos(5, 65, 0)),
            "Y+1 must NOT satisfy the goal: isInGoal is 3D, not 2.5D");
        assertFalse(goal.isInGoal(new CellPos(5, 63, 0)),
            "Y-1 must NOT satisfy the goal: isInGoal is 3D, not 2.5D");

        // Different X or Z: false.
        assertFalse(goal.isInGoal(new CellPos(4, 64, 0)),
            "X-1 must NOT satisfy the goal");
        assertFalse(goal.isInGoal(new CellPos(6, 64, 0)),
            "X+1 must NOT satisfy the goal");
        assertFalse(goal.isInGoal(new CellPos(5, 64, 1)),
            "Z+1 must NOT satisfy the goal");
        assertFalse(goal.isInGoal(new CellPos(5, 64, -1)),
            "Z-1 must NOT satisfy the goal");
    }
}
