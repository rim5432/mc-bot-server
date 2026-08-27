package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Shape-driven viability gate: the move predicates answer from the
 * CollisionShape, not from the blockId. Half-slabs, fences, and
 * pressure plates each get a case so a refactor that re-introduces
 * a per-id branch fails here instead of in the planner.
 *
 * <p>Contract: see boundaries.md decision 19. The blockId is
 * <em>never</em> read by these predicates; the test deliberately
 * labels all blocks "minecraft:stone" so a future regression that
 * tries to special-case stone would be visible.
 */
class BasicMovesShapeGateTest {

    private static final int FLOOR_Y = 63;
    private static final int BODY_Y = 64;
    private static final CellPos SRC = new CellPos(0, BODY_Y, 0);
    private static final CellPos DST = new CellPos(1, BODY_Y, 0);

    /**
     * Floor of one full cube under (0, BODY_Y, 0); air everywhere
     * else. Tests call putShape to install partial geometry.
     */
    private static MockWorldView floor() {
        MockWorldView w = new MockWorldView();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                w.putBlock(new BlockSnapshot(new CellPos(x, FLOOR_Y, z), "minecraft:stone"));
            }
        }
        return w;
    }

    @Test
    void walkAcrossLowerSlabIsViable() {
        MockWorldView w = floor();
        // Mark the floor as a bottom-half slab. Body can stand on
        // the slab (top at 0.5, the standability threshold) and walk
        // across.
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                w.putShape(new CellPos(x, FLOOR_Y, z), CollisionShape.partial(new Box(0, 0, 0, 1, 0.5, 1)));
            }
        }
        assertTrue(new BasicMoves.Walk(SRC, DST).isViable(w), "lower slab is standable: walk must be viable");
    }

    @Test
    void walkOnFenceFloorIsNotViable() {
        MockWorldView w = floor();
        // Fence posts as the floor: tops are high enough but far too
        // thin to bear the body's footprint, so no cell is supported
        // and the walk is not viable. (Vanilla-aligned: a fence cell
        // is a wall, and its top is never a routing surface.)
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                w.putShape(new CellPos(x, FLOOR_Y, z), CollisionShape.partial(new Box(0.4, 0, 0.4, 0.6, 1, 0.6)));
            }
        }
        assertFalse(new BasicMoves.Walk(SRC, DST).isViable(w), "fence post top is too thin to stand on");
    }

    @Test
    void walkOnThinPressurePlateIsNotViable() {
        MockWorldView w = floor();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                w.putShape(new CellPos(x, FLOOR_Y, z), CollisionShape.partial(new Box(0, 0, 0, 1, 0.1, 1)));
            }
        }
        assertFalse(new BasicMoves.Walk(SRC, DST).isViable(w), "plate thinner than step height is not walkable");
    }

    @Test
    void walkOnUpperSlabIsViable() {
        MockWorldView w = floor();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                w.putShape(new CellPos(x, FLOOR_Y, z), CollisionShape.partial(new Box(0, 0.5, 0, 1, 1, 1)));
            }
        }
        assertTrue(new BasicMoves.Walk(SRC, DST).isViable(w), "upper slab top is at y=1, body can stand on it");
    }

    @Test
    void jumpOntoUpperSlabPlatformIsViable() {
        // Stair-step case: an upper-slab platform sits in the
        // (1, BODY_Y, 0) cell, its top at y=BODY_Y+1. The jump-up
        // destination foot cell is (1, BODY_Y+1, 0) - empty and
        // body-clear - the head cell above it is empty, and the
        // cell below the foot carries the platform's walkable top.
        MockWorldView w = floor();
        w.putShape(new CellPos(1, BODY_Y, 0), CollisionShape.partial(new Box(0, 0.5, 0, 1, 1, 1)));
        assertTrue(
                new BasicMoves.JumpUp(SRC, new CellPos(1, BODY_Y + 1, 0)).isViable(w),
                "jump onto an upper-slab platform must be viable");
    }

    @Test
    void blockIdIsNotConsultedByViability() {
        // Same geometry under three different block ids must give
        // the same answer. The test that a future regression
        // special-casing "minecraft:stone" or any other id is
        // caught here, not inside the planner.
        for (String id : new String[] {"minecraft:stone", "minecraft:dirt", "totally:not-a-real-block"}) {
            MockWorldView w = new MockWorldView();
            for (int x = -2; x <= 3; x++) {
                for (int z = -2; z <= 3; z++) {
                    w.putBlock(new BlockSnapshot(new CellPos(x, FLOOR_Y, z), id));
                }
            }
            assertTrue(new BasicMoves.Walk(SRC, DST).isViable(w), "id " + id + ": same geometry, same answer");
        }
    }
}
