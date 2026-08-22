package com.mcbot.mcbotserver.world;

import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import com.mcbot.mcbotserver.core.world.MapBlockTraitsRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trait-registry gate: keys are state-aware, the bare-id fallback
 * matches any state, unknown keys return defaults, and a sealed
 * registry freezes its contents for runtime.
 *
 * <p>Contract: see boundaries.md decision 19. The state suffix is
 * what makes the registry honest about slabs, stairs, and other
 * "same id, different physics" blocks.
 */
class BlockTraitsRegistryGateTest {

    @Test
    void exactKeyMatchWins() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:ladder", BlockTraits.climbableOnly());
        assertTrue(r.traitsFor("minecraft:ladder").climbable());
        assertFalse(r.traitsFor("minecraft:ladder").liquid());
    }

    @Test
    void stateAwareKeyDistinguishesSlabHalves() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        // The hypothetical case: a bottom-half slab is climbable
        // (think scaffolded platforms), the top-half is not. The
        // state suffix is the only thing that lets the registry
        // tell them apart.
        r.register("minecraft:stone_slab[type=bottom]",
            BlockTraits.climbableOnly());
        r.register("minecraft:stone_slab[type=top]",
            BlockTraits.defaults());
        assertTrue(r.traitsFor(
            "minecraft:stone_slab[type=bottom]").climbable());
        assertFalse(r.traitsFor(
            "minecraft:stone_slab[type=top]").climbable());
    }

    @Test
    void bareIdFallbackMatchesWhenNoStateEntry() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:vine", BlockTraits.climbableOnly());
        // No state-suffixed entry exists, but the bare-id one
        // matches; the registry returns the bare answer.
        BlockTraits t = r.traitsFor("minecraft:vine[age=5]");
        assertTrue(t.climbable(),
            "bare-id fallback when no exact state entry exists");
    }

    @Test
    void exactStateKeyWinsOverBareIdFallback() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:vine", BlockTraits.climbableOnly());
        r.register("minecraft:vine[age=25]",
            BlockTraits.damagingOnly());
        // The exact [age=25] key must beat the bare "vine" entry.
        assertTrue(r.traitsFor(
            "minecraft:vine[age=25]").damaging());
        assertFalse(r.traitsFor(
            "minecraft:vine[age=25]").climbable());
    }

    @Test
    void unknownKeyReturnsDefaults() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:ladder", BlockTraits.climbableOnly());
        BlockTraits t = r.traitsFor("minecraft:stone");
        assertSame(BlockTraits.NONE, t,
            "unknown key must return the safe default");
    }

    @Test
    void emptyRegistryReturnsDefaults() {
        BlockTraitsRegistry r = BlockTraitsRegistry.empty();
        assertSame(BlockTraits.NONE,
            r.traitsFor("minecraft:anything"));
        // The empty registry is immutable.
        assertThrows(UnsupportedOperationException.class,
            () -> r.register("minecraft:ladder", BlockTraits.climbableOnly()));
    }

    @Test
    void sealedRegistryRejectsFurtherRegister() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry()
            .seal();
        assertThrows(IllegalStateException.class,
            () -> r.register("minecraft:ladder", BlockTraits.climbableOnly()));
    }

    @Test
    void blankKeyIsRejected() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        assertThrows(IllegalArgumentException.class,
            () -> r.register("", BlockTraits.climbableOnly()),
            "blank key must be rejected at write time");
        assertThrows(IllegalArgumentException.class,
            () -> r.traitsFor(""),
            "blank key must be rejected at read time");
    }

    @Test
    void factoryPresetsAreCorrect() {
        assertFalse(BlockTraits.NONE.climbable());
        assertFalse(BlockTraits.NONE.liquid());
        assertFalse(BlockTraits.NONE.damaging());

        assertTrue(BlockTraits.climbableOnly().climbable());
        assertFalse(BlockTraits.climbableOnly().liquid());
        assertFalse(BlockTraits.climbableOnly().damaging());

        assertTrue(BlockTraits.liquidOnly().liquid());
        assertFalse(BlockTraits.liquidOnly().climbable());

        assertTrue(BlockTraits.damagingOnly().damaging());
        assertFalse(BlockTraits.damagingOnly().liquid());

        BlockTraits lava = BlockTraits.dangerousLiquid();
        assertTrue(lava.liquid());
        assertTrue(lava.damaging());
    }

    @Test
    void entriesViewIsUnmodifiable() {
        MapBlockTraitsRegistry r = new MapBlockTraitsRegistry();
        r.register("minecraft:ladder", BlockTraits.climbableOnly());
        var view = r.entriesView();
        assertEquals(1, view.size());
        assertThrows(UnsupportedOperationException.class,
            () -> view.put("minecraft:water", BlockTraits.liquidOnly()));
    }
}
