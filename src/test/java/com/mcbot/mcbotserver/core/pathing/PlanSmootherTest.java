package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PlanSmoother merge gates (issue 0005 P1.2/P1.3): collinear runs
 * collapse to endpoints, zigzag corners merge over clear ground, and
 * a wall on the diagonal keeps the corner waypoint. Rule 1 (run
 * collapse) is the uniform-staircase hop-cadence pin: the smoothed
 * chain leaves one far-above waypoint for the whole climb.
 */
class PlanSmootherTest {

    private static MockWorldView floor(int xLen, int zLen) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = 0; z <= zLen; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static List<CellPos> plan(CellPos... cells) {
        return List.of(cells);
    }

    /**
     * A straight walk run collapses to its endpoints. This is the
     * flat-ground payoff: no more per-cell steering stops.
     */
    @Test
    void straightRunCollapsesToEndpoints() {
        List<CellPos> smoothed = PlanSmoother.smooth(
                floor(4, 1),
                plan(
                        new CellPos(0, 64, 0),
                        new CellPos(1, 64, 0),
                        new CellPos(2, 64, 0),
                        new CellPos(3, 64, 0),
                        new CellPos(4, 64, 0)));
        assertEquals(List.of(new CellPos(0, 64, 0), new CellPos(4, 64, 0)), smoothed);
    }

    /**
     * A uniform staircase collapses to its endpoints (P1.3): rule 1
     * needs no world read - every edge was A*-validated - and the
     * survivor leaves the steer waypoint above the floor for the
     * whole climb, which is what turns per-step JumpUp waits into a
     * held-thrust hop cadence.
     */
    @Test
    void uniformStaircaseCollapsesToEndpoints() {
        List<CellPos> smoothed = PlanSmoother.smooth(
                floor(3, 1),
                plan(new CellPos(0, 64, 0), new CellPos(1, 65, 0), new CellPos(2, 66, 0), new CellPos(3, 67, 0)));
        assertEquals(List.of(new CellPos(0, 64, 0), new CellPos(3, 67, 0)), smoothed);
    }

    /**
     * An east/north staircase over open ground merges across the
     * corners into one diagonal segment: the zigzag read is the
     * thing P1.2 exists to remove.
     */
    @Test
    void zigzagCornersMergeOverClearGround() {
        List<CellPos> smoothed = PlanSmoother.smooth(
                floor(5, 2),
                plan(
                        new CellPos(0, 64, 0),
                        new CellPos(1, 64, 0),
                        new CellPos(2, 64, 0),
                        new CellPos(2, 64, 1),
                        new CellPos(3, 64, 1),
                        new CellPos(4, 64, 1),
                        new CellPos(4, 64, 2),
                        new CellPos(5, 64, 2)));
        assertEquals(List.of(new CellPos(0, 64, 0), new CellPos(5, 64, 2)), smoothed);
    }

    /**
     * The same plan with a wall on the diagonal keeps the corner
     * waypoint: line-of-sight merge must consult the world, not just
     * the plan topology.
     */
    @Test
    void wallOnDiagonalKeepsCornerWaypoint() {
        MockWorldView world = floor(5, 2);
        for (int x = 0; x <= 3; x++) {
            world.putBlock(new BlockSnapshot(new CellPos(x, 64, 1), "minecraft:smooth_stone"));
        }
        List<CellPos> smoothed = PlanSmoother.smooth(
                world,
                plan(
                        new CellPos(0, 64, 0),
                        new CellPos(1, 64, 0),
                        new CellPos(2, 64, 0),
                        new CellPos(2, 64, 1),
                        new CellPos(3, 64, 1),
                        new CellPos(4, 64, 1),
                        new CellPos(4, 64, 2),
                        new CellPos(5, 64, 2)));
        // Every cross-wall shortcut is rejected; the collinear
        // sub-runs along z=0 and z=1 still merge. The corner cells
        // at the wall survive as steering waypoints.
        assertEquals(
                List.of(
                        new CellPos(0, 64, 0),
                        new CellPos(2, 64, 0),
                        new CellPos(2, 64, 1),
                        new CellPos(4, 64, 1),
                        new CellPos(5, 64, 2)),
                smoothed);
    }

    /**
     * Chains of two cells or fewer pass through untouched - index 0
     * is the standing cell, and there is nothing to merge.
     */
    @Test
    void shortChainPassesThrough() {
        List<CellPos> two = plan(new CellPos(0, 64, 0), new CellPos(1, 64, 0));
        assertEquals(two, PlanSmoother.smooth(floor(1, 1), two));
    }
}
