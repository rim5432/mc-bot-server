package com.mcbot.mcbotserver.corepathing;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.pathing.AStarPathFinder;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                world.putBlock(new com.mcbot.mcbotserver.api.world
                    .BlockSnapshot(new CellPos(x, 64, z),
                        "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static AStarPathFinder finder() {
        return new AStarPathFinder(BasicMoves::from,
            Heuristic.euclideanTo(null));
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

        var result = new AStarPathFinder(BasicMoves::from,
            Heuristic.euclideanTo(goal)).compute(world, start,
            new GoalBlock(goal));

        assertTrue(result.reachedGoal());
        assertEquals(start, result.waypoints().get(0));
        assertEquals(goal, result.waypoints()
            .get(result.waypoints().size() - 1));
        for (CellPos c : result.waypoints()) {
            assertEquals(65, c.y(), "path left the walk plane at " + c);
        }
    }

    /** A wall on the direct line forces a legal detour around it. */
    @Test
    void wallForcesDetourAroundBlockedColumn() {
        MockWorldView world = flatGround(12, 3);
        CellPos blocked = new CellPos(4, 65, 0);
        world.putBlock(new com.mcbot.mcbotserver.api.world.BlockSnapshot(
            blocked, "minecraft:stone"));
        CellPos start = new CellPos(0, 65, 0);
        CellPos goal = new CellPos(8, 65, 0);

        var result = new AStarPathFinder(BasicMoves::from,
            Heuristic.euclideanTo(goal)).compute(world, start,
            new GoalBlock(goal));

        assertTrue(result.reachedGoal());
        assertFalse(result.waypoints().contains(blocked),
            "path must not traverse the wall cell");
        assertTrue(result.waypoints().size() >= 8,
            "path must still span the corridor");
    }

    /** No support chain to the goal: clean NO_PATH, empty waypoints. */
    @Test
    void unreachableGoalFailsCleanly() {
        MockWorldView world = flatGround(6, 2);
        CellPos start = new CellPos(0, 65, 0);
        CellPos floatingGoal = new CellPos(20, 80, 0);

        var result = new AStarPathFinder(BasicMoves::from,
            Heuristic.euclideanTo(floatingGoal)).compute(world, start,
            new GoalBlock(floatingGoal));

        assertFalse(result.reachedGoal());
        assertTrue(result.waypoints().isEmpty(),
            "no usable partial exists toward a floating goal");
    }

    /** The node budget is a hard safety net on pathological searches. */
    @Test
    void nodeBudgetCapsExpansion() {
        MockWorldView world = flatGround(60, 30);
        CellPos start = new CellPos(0, 65, 0);
        CellPos farGoal = new CellPos(55, 65, 25);

        var finder = new AStarPathFinder(BasicMoves::from,
            Heuristic.euclideanTo(farGoal), 50);
        var result = finder.compute(world, start, new GoalBlock(farGoal));

        assertFalse(result.reachedGoal(),
            "50 nodes cannot reach 40+ blocks away");
        assertTrue(result.expandedNodes() <= 50,
            "budget must cap expansions");
    }
}
