package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Ladder-column route gate: the offline twin of the
 * {@code climbsLadderToPlatform} gametest geometry. The planner must
 * route walk-under -> ladder column -> ClimbUp run -> walk-off onto
 * the platform. This pins the whole planner-side chain - the
 * climb-trait plate exception in BasicMoves.passable (a 3/16 flush
 * plate is a route only on a climbable cell), the ClimbUp edge
 * chain, and the top-rung exit into air - so any broken conjunct
 * answers here before the engine run.
 */
class AStarLadderColumnGateTest {

    /** Engine-true ladder plate: facing=west, 3/16 deep on the east edge. */
    private static final CollisionShape LADDER_PLATE = CollisionShape.partial(new Box(0.8125, 0, 0, 1, 1, 1));

    /**
     * The gametest geometry, cell-local: floor at y=0, wall at x=10
     * (z=7..9, y=1..6), ladders at (9, 1..5, 8), platform top surface
     * at y=5 (x=4..8, z=7..9).
     *
     * @param withClimbTrait false strips the climbable trait while
     *                       keeping the plate shapes - the door-plate
     *                       scenario: geometrically identical cells
     *                       that must stay walls (trait gate).
     */
    private static MockWorldView ladderToPlatform(boolean withClimbTrait) {
        MockWorldView w = new MockWorldView();
        // Floor across the full 16x16 template, as the rig paves it.
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                w.putBlock(new BlockSnapshot(new CellPos(x, 0, z), "minecraft:smooth_stone"));
            }
        }
        // Support wall behind the ladder.
        for (int z = 7; z <= 9; z++) {
            for (int y = 1; y <= 6; y++) {
                w.putBlock(new BlockSnapshot(new CellPos(10, y, z), "minecraft:smooth_stone"));
            }
        }
        // Ladder column on the wall's west face.
        for (int y = 1; y <= 5; y++) {
            CellPos rung = new CellPos(9, y, 8);
            w.putBlock(new BlockSnapshot(rung, withClimbTrait ? "minecraft:ladder" : "minecraft:oak_door"));
            if (withClimbTrait) {
                w.putTraits(rung, BlockTraits.climbableOnly());
            }
            w.putShape(rung, LADDER_PLATE);
        }
        // Platform: standable from y=6 upward.
        for (int x = 4; x <= 8; x++) {
            for (int z = 7; z <= 9; z++) {
                w.putBlock(new BlockSnapshot(new CellPos(x, 5, z), "minecraft:smooth_stone"));
            }
        }
        return w;
    }

    @Test
    void plannerRoutesThroughTheLadderColumnToThePlatform() {
        MockWorldView world = ladderToPlatform(true);
        CellPos start = new CellPos(4, 1, 8);
        CellPos goal = new CellPos(6, 6, 8);

        var result = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(goal))
                .compute(world, start, new GoalBlock(goal));
        assertTrue(result.reachedGoal(), "the ladder route must exist: " + result);
        assertEquals(start, result.waypoints().get(0));
        assertEquals(goal, result.waypoints().get(result.waypoints().size() - 1));
    }

    @Test
    void plateColumnWithoutTheClimbTraitIsUnreachable() {
        // Door-plate twin: identical 3/16 flush plates, no climbable
        // trait. The geometric plate clause alone must NOT open a
        // route - a plan through closed doors is a plan into walls.
        MockWorldView world = ladderToPlatform(false);
        CellPos start = new CellPos(4, 1, 8);
        CellPos goal = new CellPos(6, 6, 8);

        var result = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(goal))
                .compute(world, start, new GoalBlock(goal));

        assertFalse(result.reachedGoal(), "a traitless plate column must stay unreachable");
    }
}
