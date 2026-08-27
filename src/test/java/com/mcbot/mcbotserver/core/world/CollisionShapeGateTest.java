package com.mcbot.mcbotserver.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;
import org.junit.jupiter.api.Test;

/**
 * Geometry-derivation gate: the shape's box is the source of truth
 * for {@code passable} and {@code walkableTop}. Every common block
 * shape gets a case so a refactor that drops a boundary fails here
 * instead of inside the planner.
 *
 * <p>Contract: see boundaries.md decision 19. The box is in
 * cell-local {@code [0,1]} units; {@link CollisionShape#STEP_UP_REACH}
 * gates passability, {@link CollisionShape#STANDABLE_THRESHOLD} plus
 * the {@link CollisionShape#BODY_WIDTH} footprint rule gate
 * standability.
 */
class CollisionShapeGateTest {

    @Test
    void emptyCellIsPassableAndTopIsNotWalkable() {
        CollisionShape s = CollisionShape.empty();
        assertSame(CollisionShape.Kind.EMPTY, s.kind());
        assertTrue(s.passable(), "an empty cell must let a body through");
        assertFalse(s.walkableTop(), "an empty cell has no top to stand on");
    }

    @Test
    void fullCubeIsImpassableAndTopIsWalkable() {
        CollisionShape s = CollisionShape.fullCube();
        assertTrue(s.walkableTop());
        assertFalse(s.passable());
    }

    @Test
    void lowerSlabIsStandable() {
        // Bottom-half slab: top at y=0.5, exactly the standability
        // threshold and below step-up reach. The body stands on it
        // and sweeps through the upper half of the cell. This is the
        // canonical "is the half-slab actually walkable" case that
        // the old isAir() check could not answer without a per-id
        // table.
        CollisionShape s = CollisionShape.partial(new Box(0, 0, 0, 1, 0.5, 1));
        assertTrue(s.walkableTop());
        assertTrue(s.passable(), "upper half of a bottom slab must be body-passable");
    }

    @Test
    void upperSlabIsStandableAndNotPassable() {
        // Top-half slab: top at y=1, bottom at y=0.5. Body can
        // stand on it but the body cannot pass through the cell -
        // the entire volume below the head is solid.
        CollisionShape s = CollisionShape.partial(new Box(0, 0.5, 0, 1, 1, 1));
        assertTrue(s.walkableTop());
        assertFalse(s.passable());
    }

    @Test
    void fencePostCellIsWallAndNotStandable() {
        // Fence post: thin column through the cell center, top at
        // y=1. passable() answers false - the cell is a wall. This
        // is vanilla-aligned: a 0.6-wide body cannot fit through the
        // 0.4 slot a centered post leaves, and real fences fill
        // their cell near-full width; the way past a fence is the
        // missing-post cell, which is EMPTY. walkableTop() answers
        // false via the footprint rule - the top is high enough but
        // 0.2 wide, nothing to balance on.
        CollisionShape s = CollisionShape.partial(new Box(0.4, 0, 0.4, 0.6, 1, 0.6));
        assertFalse(s.passable(), "a tall post fills the body's sweep; the cell is a wall");
        assertFalse(s.walkableTop(), "fence post is too thin to stand on");
    }

    @Test
    void thinFloorIsNotStandable() {
        // A 0.1-tall pressure plate: top below step height, the
        // body would clip the body box at the top. Not walkable.
        CollisionShape s = CollisionShape.partial(new Box(0, 0, 0, 1, 0.1, 1));
        assertFalse(s.walkableTop(), "floor thinner than step height is not walkable");
    }

    @Test
    void boxRejectsOutOfRangeBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Box(-0.1, 0, 0, 1, 1, 1), "minX below 0 must throw");
        assertThrows(IllegalArgumentException.class, () -> new Box(0, 0, 0, 1, 1.1, 1), "maxY above 1 must throw");
        assertThrows(IllegalArgumentException.class, () -> new Box(0.5, 0, 0, 0.3, 0.5, 1), "minX > maxX must throw");
    }

    @Test
    void boxEmptyAndFullAreCanonical() {
        assertEquals(0, Box.empty().maxX() - Box.empty().minX(), "empty box has zero extent");
        Box f = Box.full();
        assertEquals(1.0, f.maxX() - f.minX(), 1e-9);
        assertEquals(1.0, f.maxY() - f.minY(), 1e-9);
        assertEquals(1.0, f.maxZ() - f.minZ(), 1e-9);
    }
}
