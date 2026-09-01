package com.mcbot.mcbotserver.api.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
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

    @Test
    void describeCarriesAllFields() {
        GoalRange g = new GoalRange(CENTER, MIN, MAX);
        String d = g.describe();
        assertTrue(d.contains("GoalRange"), "describe starts with type name");
        assertTrue(d.contains("min=8"), "describe carries min");
        assertTrue(d.contains("max=12"), "describe carries max");
    }
}
