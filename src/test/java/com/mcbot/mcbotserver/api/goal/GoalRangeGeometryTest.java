package com.mcbot.mcbotserver.api.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Pins the GoalRange geometry contract: the band predicate, the
 * band-edge heuristic direction, and the constructor validation.
 * The heuristic is the load-bearing piece for the "back away when
 * too close" behavior — a center-pulling heuristic would make A*
 * explore the inside of the ring first and hand the follower a
 * best-partial that walks toward the target it is trying to escape.
 */
class GoalRangeGeometryTest {

    private static final CellPos CENTER = new CellPos(10, 64, 10);
    private static final int MIN = 8;
    private static final int MAX = 12;

    @Test
    void cellAtInnerEdgeIsInGoal() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        // Chebyshev distance 8: x + 8, same y/z.
        assertTrue(g.isInGoal(new CellPos(18, 64, 10)), "cell at min distance is in the band");
    }

    @Test
    void cellAtOuterEdgeIsInGoal() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        assertTrue(g.isInGoal(new CellPos(22, 64, 10)), "cell at max distance is in the band");
    }

    @Test
    void cellInsideInnerEdgeIsNotInGoal() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        assertFalse(g.isInGoal(new CellPos(15, 64, 10)), "cell at distance 5 is inside min, not in band");
    }

    @Test
    void cellOutsideOuterEdgeIsNotInGoal() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        assertFalse(g.isInGoal(new CellPos(25, 64, 10)), "cell at distance 15 is outside max, not in band");
    }

    @Test
    void nullPositionIsNotInGoal() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        assertFalse(g.isInGoal(null), "null position is never in a goal");
    }

    @Test
    void heuristicIsZeroInsideBand() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        Heuristic h = Goals.heuristicOf(g);
        assertEquals(0.0, h.estimate(new CellPos(20, 64, 10)), 0.001, "cell in band has zero heuristic");
    }

    @Test
    void heuristicInsideMinPullsOutward() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        Heuristic h = Goals.heuristicOf(g);
        // Distance 5 from center, min is 8 -> heuristic should be 3.
        assertEquals(
                3.0,
                h.estimate(new CellPos(15, 64, 10)),
                0.001,
                "cell inside min has heuristic = min - dist, pulling outward");
    }

    @Test
    void heuristicOutsideMaxPullsInward() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        Heuristic h = Goals.heuristicOf(g);
        // Distance 15 from center, max is 12 -> heuristic should be 3.
        assertEquals(
                3.0,
                h.estimate(new CellPos(25, 64, 10)),
                0.001,
                "cell outside max has heuristic = dist - max, pulling inward");
    }

    @Test
    void heuristicForGoalNearKeepsEuclideanPull() {
        // Regression: GoalBlock and GoalNear must keep the historical
        // euclideanTo heuristic — changing them would alter path quality
        // for every existing goto/defend mission.
        GoalNear near = new GoalNear(CENTER, 5);
        Heuristic h = Goals.heuristicOf(near);
        double expected = new CellPos(20, 64, 10).distanceTo(CENTER) * 1.001;
        assertEquals(
                expected,
                h.estimate(new CellPos(20, 64, 10)),
                0.001,
                "GoalNear keeps euclideanTo heuristic, not band-edge");
    }

    @Test
    void minMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new GoalRange(CENTER, -1, MAX), "negative min is rejected");
    }

    @Test
    void maxMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new GoalRange(CENTER, MIN, 0), "zero max is rejected");
    }

    @Test
    void minMustNotExceedMax() {
        assertThrows(IllegalArgumentException.class, () -> new GoalRange(CENTER, 10, 5), "min > max is rejected");
    }

    @Test
    void centerMustNotBeNull() {
        assertThrows(IllegalArgumentException.class, () -> new GoalRange(null, MIN, MAX), "null center is rejected");
    }

    /**
     * The progress-tier distance mirrors the heuristic's band-edge
     * geometry in position space (decision 56): inside the min it
     * counts DOWN as the body moves outward - the kite-progress
     * contract. A center-anchored metric would read the same motion
     * as regression and mint false STUCK/NO_PATH witnesses.
     */
    @Test
    void distanceOfCountsOutwardKiteAsProgress() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        GoalDistance d = Goals.distanceOf(g);
        // West of center, inside min by 3 (continuous Chebyshev 5 vs min 8).
        assertEquals(3.0, d.meters(new Vec3(5.5, 64.5, 10.5)), 1.0E-9, "inside-min distance is the gap to the band");
        // One step outward (away from center, x decreasing) closes the gap.
        assertEquals(2.0, d.meters(new Vec3(4.5, 64.5, 10.5)), 1.0E-9, "outward motion must shrink the distance");
        // On the band: zero, like the heuristic on the goal set.
        assertEquals(0.0, d.meters(new Vec3(18.5, 64.5, 10.5)), 1.0E-9, "in-band distance is zero");
        // Beyond max: the gap reopens outward.
        assertEquals(3.0, d.meters(new Vec3(25.5, 64.5, 10.5)), 1.0E-9, "outside-max distance is the overshoot");
    }

    /**
     * Point goals keep the exact historical anchor math the fuse and
     * the witness ledger were tuned against: 3D Euclidean to the
     * anchor cell centre. Swapping this for a banded or Chebyshev
     * variant would silently re-tune STUCK timing for every goto.
     */
    @Test
    void distanceOfKeepsPointGoalAnchorMath() {
        GoalDistance d = Goals.distanceOf(new GoalBlock(CENTER));
        Vec3 from = new Vec3(5.5, 64.5, 10.5);
        assertEquals(
                from.distanceTo(10.5, 64.5, 10.5), d.meters(from), 1.0E-12, "point goals measure to the anchor centre");
    }

    @Test
    void describeCarriesAllFields() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        String d = g.describe();
        assertTrue(d.contains("GoalRange"), "describe starts with type name");
        assertTrue(d.contains("min=8"), "describe carries min");
        assertTrue(d.contains("max=12"), "describe carries max");
    }
}
