package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Stage-2 A* gate: the engine finds paths over the basic move set on
 * flat ground, detours around walls, refuses unreachable goals, and
 * honors its node budget.
 *
 * <p>Contract: see boundaries.md decisions 7/8 and numen-notes.md
 * section 17 (anytime semantics: goal path, best partial, or failure).
 */
class AStarGateTest {

    private static MockWorldView flatGround(int xLen, int zWide) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = -zWide; z <= zWide; z++) {
                world.putBlock(new com.mcbot.mcbotserver.api.world.BlockSnapshot(
                        new CellPos(x, 64, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    /**
     * Straight corridor: path is start-first, goal-last, and every hop
     * stays on the walk plane.
     */
    @Test
    void straightCorridorFindsGoal() {
        MockWorldView world = flatGround(12, 3);
        CellPos start = new CellPos(0, 65, 0);
        CellPos goal = new CellPos(8, 65, 0);

        var result = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(goal))
                .compute(world, start, new GoalBlock(goal));

        assertTrue(result.reachedGoal());
        assertEquals(start, result.waypoints().get(0));
        assertEquals(goal, result.waypoints().get(result.waypoints().size() - 1));
        for (CellPos c : result.waypoints()) {
            assertEquals(65, c.y(), "path left the walk plane at " + c);
        }
    }

    /** A wall on the direct line forces a legal detour around it. */
    @Test
    void wallForcesDetourAroundBlockedColumn() {
        MockWorldView world = flatGround(12, 3);
        CellPos blocked = new CellPos(4, 65, 0);
        world.putBlock(new com.mcbot.mcbotserver.api.world.BlockSnapshot(blocked, "minecraft:stone"));
        CellPos start = new CellPos(0, 65, 0);
        CellPos goal = new CellPos(8, 65, 0);

        var result = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(goal))
                .compute(world, start, new GoalBlock(goal));

        assertTrue(result.reachedGoal());
        assertFalse(result.waypoints().contains(blocked), "path must not traverse the wall cell");
        assertTrue(result.waypoints().size() >= 8, "path must still span the corridor");
    }

    /** No support chain to the goal: clean NO_PATH, empty waypoints. */
    @Test
    void unreachableGoalFailsCleanly() {
        MockWorldView world = flatGround(6, 2);
        CellPos start = new CellPos(0, 65, 0);
        CellPos floatingGoal = new CellPos(20, 80, 0);

        var result = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(floatingGoal))
                .compute(world, start, new GoalBlock(floatingGoal));

        assertFalse(result.reachedGoal());
        assertTrue(result.waypoints().isEmpty(), "no usable partial exists toward a floating goal");
    }

    /**
     * The node budget is a hard safety net on pathological
     * searches. With 50 nodes against a ~60-cell goal the search
     * makes enough heuristic progress to clear the confidence
     * floor, so the result is a usable PARTIAL - exercising both
     * the cap and the gate's pass-through.
     */
    @Test
    void nodeBudgetCapsExpansion() {
        MockWorldView world = flatGround(60, 30);
        CellPos start = new CellPos(0, 65, 0);
        CellPos farGoal = new CellPos(55, 65, 25);

        var finder = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(farGoal), 50);
        var result = finder.compute(world, start, new GoalBlock(farGoal));

        assertFalse(result.reachedGoal(), "50 nodes cannot reach 40+ blocks away");
        assertTrue(result.expandedNodes() <= 50, "budget must cap expansions");
        assertTrue(
                result.waypoints().size() > 1,
                "a 50-node search against a 60-cell goal makes " + "enough progress to clear MIN_PARTIAL_CONFIDENCE");
    }

    /**
     * Confidence gate: a budget cut whose best-frontier ratio is
     * below MIN_PARTIAL_CONFIDENCE collapses to FAILED, not
     * PARTIAL. With budget=2 against a 29-cell goal the search
     * expands the start and one neighbor - the frontier's h
     * stays near startH, so the heuristic progress ratio is
     * well below 0.10 and the partial is suppressed.
     */
    @Test
    void lowConfidencePartialCollapsesToFailed() {
        MockWorldView world = flatGround(30, 20);
        CellPos start = new CellPos(0, 65, 0);
        CellPos farGoal = new CellPos(25, 65, 15);

        var finder = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(farGoal), 2);
        var result = finder.compute(world, start, new GoalBlock(farGoal));

        assertFalse(result.reachedGoal(), "2 nodes cannot reach a 29-cell goal");
        assertTrue(
                result.waypoints().isEmpty(),
                "low-confidence PARTIAL must downgrade to FAILED, " + "not surface a doomed prefix");
        assertEquals(0.0, result.confidence(), "FAILED carries zero confidence by definition");
    }

    /**
     * Confidence of 1.0 is reserved for reachedGoal; FAILED carries
     * 0.0. The clamp in the PathResult constructor rejects NaN and
     * out-of-range values to keep the contract machine-checkable.
     */
    @Test
    void pathResultConfidenceIsValidated() {
        try {
            new AStarPathFinder.PathResult(java.util.List.of(), 0, true, 1.5);
            org.junit.jupiter.api.Assertions.fail("confidence > 1.0 must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("confidence"));
        }
        try {
            new AStarPathFinder.PathResult(java.util.List.of(), 0, true, Double.NaN);
            org.junit.jupiter.api.Assertions.fail("NaN confidence must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("confidence"));
        }
    }
}
