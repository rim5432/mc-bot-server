package com.mcbot.mcbotserver.core.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalRange;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the GoalRange plan-time semantics in PathingBehavior: the band
 * predicate terminates A* at the nearest band edge, the band-edge
 * heuristic pulls outward when the body is inside min and inward when
 * outside max, and a body already in the band reports SUCCESS on the
 * first tick (it has nowhere to go — the combat behavior owns the
 * stand-and-shoot).
 *
 * <p>These tests use the synchronous planning constructor (no worker) so
 * the plan is adopted within the first tick and the waypoint chain is
 * inspectable immediately.
 */
class PathingRangeBandGateTest {

    private static final CellPos CENTER = new CellPos(0, 64, 0);
    private static final int BAND_MIN = 8;
    private static final int BAND_MAX = 12;

    @Test
    void bodyInBandReportsSuccessImmediately() {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = {new Vec3(10.5, 64, 0.5)}; // Chebyshev dist 10, in [8,12]
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(new GoalRange(CENTER, BAND_MIN, BAND_MAX));

        ExecutionReport r = mover.tick(world, directive, actor);
        assertEquals(
                ExecutionReport.Status.SUCCESS,
                r.status(),
                "a body already in the band has arrived — the goal predicate is the sole authority");
        assertTrue(
                PathingTestAccess.planEmpty(mover),
                "SUCCESS resets the plan — no stale waypoints linger for the next directive");
    }

    @Test
    void bodyInsideMinPlansOutward() {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = {new Vec3(5.5, 64, 0.5)}; // Chebyshev dist 5, inside min=8
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(new GoalRange(CENTER, BAND_MIN, BAND_MAX));

        mover.tick(world, directive, actor); // synchronous plan adoption
        List<CellPos> waypoints = PathingTestAccess.waypoints(mover);

        assertTrue(waypoints.size() > 1, "the planner must produce a route out of the too-close zone");
        // The route starts at the bot cell (5,64,0) and the first step must
        // move AWAY from center (x increasing), not toward it.
        CellPos firstStep = waypoints.get(1);
        assertTrue(
                firstStep.x() > 5,
                "the first waypoint must move away from center (x > 5), not toward it — "
                        + "the band-edge heuristic pulls outward when inside min; firstStep=" + firstStep);
        // The terminal waypoint must be in the band.
        CellPos terminal = waypoints.get(waypoints.size() - 1);
        int terminalDist = terminal.chebyshevTo(CENTER);
        assertTrue(
                terminalDist >= BAND_MIN && terminalDist <= BAND_MAX,
                "the plan terminates at a band edge cell; terminal dist=" + terminalDist);
    }

    @Test
    void bodyOutsideMaxPlansInward() {
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = {new Vec3(15.5, 64, 0.5)}; // Chebyshev dist 15, outside max=12
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(new GoalRange(CENTER, BAND_MIN, BAND_MAX));

        mover.tick(world, directive, actor);
        List<CellPos> waypoints = PathingTestAccess.waypoints(mover);

        assertTrue(waypoints.size() > 1, "the planner must produce a route into the band");
        CellPos firstStep = waypoints.get(1);
        assertTrue(
                firstStep.x() < 15,
                "the first waypoint must move toward center (x < 15), not away — "
                        + "the band-edge heuristic pulls inward when outside max; firstStep=" + firstStep);
        CellPos terminal = waypoints.get(waypoints.size() - 1);
        int terminalDist = terminal.chebyshevTo(CENTER);
        assertTrue(
                terminalDist >= BAND_MIN && terminalDist <= BAND_MAX,
                "the plan terminates at a band edge cell; terminal dist=" + terminalDist);
    }

    @Test
    void goalChangeToSameBandDoesNotReplan() {
        // The goal equality check uses record equality — a GoalRange with
        // the same center/min/max is equal to the previous one, so the
        // departure hold and noPath reset do not fire. This pins that
        // per-tick directive re-emission (DefendProcess updates the goal
        // every tick with the same values) does not churn the planner.
        MockWorldView world = MockWorldView.pavedFloor(60);
        Vec3[] position = {new Vec3(5.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover", () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        GoalRange band = new GoalRange(CENTER, BAND_MIN, BAND_MAX);

        mover.tick(world, Directive.of(band), actor);
        int ticksSinceFirstPlan = PathingTestAccess.ticksSincePlan(mover);

        // Second tick with an equal-but-distinct GoalRange instance —
        // record equality means lastGoal matches, no replan request.
        mover.tick(world, Directive.of(new GoalRange(CENTER, BAND_MIN, BAND_MAX)), actor);
        int ticksSinceSecondPlan = PathingTestAccess.ticksSincePlan(mover);

        assertTrue(
                ticksSinceSecondPlan > ticksSinceFirstPlan,
                "an equal GoalRange must not trigger a replan — record equality is the goal-change gate; "
                        + "ticksSincePlan went from " + ticksSinceFirstPlan + " to " + ticksSinceSecondPlan);
    }
}
