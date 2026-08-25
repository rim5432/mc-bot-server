package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.core.world.MapBlockTraitsRegistry;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock-side shape / traits injection gate: the offline mock can
 * carry per-cell shape overrides, per-cell trait overrides, and a
 * fallback trait registry. Defaults are air -> empty, solid ->
 * full cube; the registry is empty by default.
 *
 * <p>Contract: see boundaries.md decision 19 and the
 * MockWorldView doc. Without an explicit shape, the mock answers
 * shape from the blockId (air -> empty, anything else -> full
 * cube). Tests that care about partial blocks inject
 * {@code putShape}.
 */
class MockWorldViewShapeGateTest {

    private static final CellPos POS = new CellPos(0, 64, 0);

    @Test
    void airByDefaultHasEmptyShape() {
        MockWorldView w = new MockWorldView();
        assertSame(CollisionShape.Kind.EMPTY,
            w.getCollisionShape(POS, ViewMode.LIVE).kind());
        assertTrue(w.getCollisionShape(POS, ViewMode.LIVE).passable());
    }

    @Test
    void solidBlockByDefaultHasFullCubeShape() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(POS, "minecraft:stone"));
        assertSame(CollisionShape.Kind.FULL_CUBE,
            w.getCollisionShape(POS, ViewMode.LIVE).kind());
        assertFalse(w.getCollisionShape(POS, ViewMode.LIVE).passable());
        assertTrue(w.getCollisionShape(POS, ViewMode.LIVE).walkableTop());
    }

    @Test
    void putShapeOverridesDefault() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(POS, "minecraft:stone"));
        // Even though the blockId says stone, the explicit partial
        // shape wins.
        w.putShape(POS, CollisionShape.partial(
            new Box(0, 0, 0, 1, 0.5, 1)));
        assertSame(CollisionShape.Kind.PARTIAL,
            w.getCollisionShape(POS, ViewMode.LIVE).kind());
        assertTrue(w.getCollisionShape(POS, ViewMode.LIVE).passable());
    }

    @Test
    void unloadedCellFallsBackToFullCube() {
        MockWorldView w = new MockWorldView();
        w.markUnloaded(POS);
        // Unloaded = "do not know"; the default answers
        // FULL_CUBE - blocking. The planner must not route through
        // unrendered terrain.
        assertSame(CollisionShape.Kind.FULL_CUBE,
            w.getCollisionShape(POS, ViewMode.LIVE).kind());
    }

    @Test
    void traitsDefaultToSafeNone() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(POS, "minecraft:stone"));
        assertSame(BlockTraits.NONE,
            w.getBlockTraits(POS, ViewMode.LIVE));
    }

    @Test
    void putTraitsOverridesDefault() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(POS, "minecraft:ladder"));
        w.putTraits(POS, BlockTraits.climbableOnly());
        assertTrue(w.getBlockTraits(POS, ViewMode.LIVE).climbable());
    }

    @Test
    void registryResolvesByBlockId() {
        MockWorldView w = new MockWorldView();
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:ladder", BlockTraits.climbableOnly());
        w.setTraitsRegistry(r);
        w.putBlock(new BlockSnapshot(POS, "minecraft:ladder"));
        assertTrue(w.getBlockTraits(POS, ViewMode.LIVE).climbable());
        // A cell with a different id gets the registry's default
        // answer, which is the empty default.
        w.putBlock(new BlockSnapshot(new CellPos(1, 64, 0),
            "minecraft:stone"));
        assertSame(BlockTraits.NONE,
            w.getBlockTraits(new CellPos(1, 64, 0), ViewMode.LIVE));
    }

    @Test
    void perCellTraitsWinOverRegistry() {
        MockWorldView w = new MockWorldView();
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:vine", BlockTraits.climbableOnly());
        w.setTraitsRegistry(r);
        w.putBlock(new BlockSnapshot(POS, "minecraft:vine"));
        // The per-cell override marks this one vine as damaging,
        // beating the registry's climbable answer for the same id.
        w.putTraits(POS, BlockTraits.damagingOnly());
        assertTrue(w.getBlockTraits(POS, ViewMode.LIVE).damaging());
        assertFalse(w.getBlockTraits(POS, ViewMode.LIVE).climbable());
    }

    @Test
    void nullArgumentsAreRejected() {
        MockWorldView w = new MockWorldView();
        assertThrows(IllegalArgumentException.class,
            () -> w.putShape(null, CollisionShape.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> w.putShape(POS, null));
        assertThrows(IllegalArgumentException.class,
            () -> w.putTraits(null, BlockTraits.climbableOnly()));
        assertThrows(IllegalArgumentException.class,
            () -> w.putTraits(POS, null));
        assertThrows(IllegalArgumentException.class,
            () -> w.setTraitsRegistry(null));
    }
}
