package com.mcbot.mcbotserver.world;

import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry-derivation gate: the shape's box is the source of truth
 * for {@code passable} and {@code walkableTop}. Every common block
 * shape gets a case so a refactor that drops a boundary fails here
 * instead of inside the planner.
 *
 * <p>Contract: see boundaries.md decision 19. The box is in
 * cell-local {@code [0,1]} units; {@link CollisionShape#STEP_HEIGHT}
 * is the threshold for both predicates.
 */
class CollisionShapeGateTest {

    @Test
    void emptyCellIsPassableAndTopIsNotWalkable() {
        CollisionShape s = CollisionShape.empty();
        assertSame(CollisionShape.Kind.EMPTY, s.kind());
        assertTrue(s.passable(),
            "an empty cell must let a body through");
        assertFalse(s.walkableTop(),
            "an empty cell has no top to stand on");
    }

    @Test
    void fullCubeIsImpassableAndTopIsWalkable() {
        CollisionShape s = CollisionShape.fullCube();
        assertTrue(s.walkableTop());
        assertFalse(s.passable());
    }

    @Test
    @Disabled("see doc/architecture/issues/0002-world-collision-slab-fence.md; "
        + "re-enabled when the slab/fence default shape is fixed")
    void lowerSlabIsStandable() {
        // Bottom-half slab: top at y=0.5, just below step height
        // (0.625). The body can stand on the slab (top is at the
        // step-up height) and the upper half of the cell is body
        // passable (top below step height). This is the canonical
        // "is the half-slab actually walkable" case that the old
        // isAir() check could not answer without a per-id table.
        CollisionShape s = CollisionShape.partial(
            new Box(0, 0, 0, 1, 0.5, 1));
        assertTrue(s.walkableTop());
        assertTrue(s.passable(),
            "upper half of a bottom slab must be body-passable");
    }

    @Test
    void upperSlabIsStandableAndNotPassable() {
        // Top-half slab: top at y=1, bottom at y=0.5. Body can
        // stand on it but the body cannot pass through the cell -
        // the entire volume below the head is solid.
        CollisionShape s = CollisionShape.partial(
            new Box(0, 0.5, 0, 1, 1, 1));
        assertTrue(s.walkableTop());
        assertFalse(s.passable());
    }

    @Test
    @Disabled("see doc/architecture/issues/0002-world-collision-slab-fence.md; "
        + "re-enabled when the fence default shape is fixed")
    void fenceIsNotStandableAndPassableAround() {
        // Fence: thin post at center, top at y=1.5. walkableTop
        // requires top to be at the step height AND below a jump
        // - a 1.5-tall post fails the second. Body cannot pass
        // through the post itself but the empty box around it
        // makes the cell passable overall.
        CollisionShape s = CollisionShape.partial(
            new Box(0.4, 0, 0.4, 0.6, 1.5, 0.6));
        assertFalse(s.walkableTop(),
            "fence post is too tall to step up onto");
        assertTrue(s.passable(),
            "the cell around the fence is body-passable");
    }

    @Test
    void thinFloorIsNotStandable() {
        // A 0.1-tall pressure plate: top below step height, the
        // body would clip the body box at the top. Not walkable.
        CollisionShape s = CollisionShape.partial(
            new Box(0, 0, 0, 1, 0.1, 1));
        assertFalse(s.walkableTop(),
            "floor thinner than step height is not walkable");
    }

    @Test
    void boxRejectsOutOfRangeBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> new Box(-0.1, 0, 0, 1, 1, 1),
            "minX below 0 must throw");
        assertThrows(IllegalArgumentException.class,
            () -> new Box(0, 0, 0, 1, 1.1, 1),
            "maxY above 1 must throw");
        assertThrows(IllegalArgumentException.class,
            () -> new Box(0.5, 0, 0, 0.3, 0.5, 1),
            "minX > maxX must throw");
    }

    @Test
    void boxEmptyAndFullAreCanonical() {
        assertEquals(0, Box.empty().maxX() - Box.empty().minX(),
            "empty box has zero extent");
        Box f = Box.full();
        assertEquals(1.0, f.maxX() - f.minX(), 1e-9);
        assertEquals(1.0, f.maxY() - f.minY(), 1e-9);
        assertEquals(1.0, f.maxZ() - f.minZ(), 1e-9);
    }
}
