package com.mcbot.mcbotserver.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Offline gates for {@link RecipeView}: the pure recipe description a
 * data-driven planner consumes. Pure Java — zero MC imports.
 *
 * <p>Contract: see boundaries.md section A (api purity) and workplan
 * Phase 3. The invariants under test: pattern-relative coordinates
 * (row-major within patternWidth), tag-expanded accepted-id lists,
 * immutability against caller-held maps, and the 2x2 fit predicate
 * that decides which surface to open.
 */
class RecipeViewTest {

    /** Sticks: one column, two planks — fits the 2x2 inventory grid.
     * Pattern coords (width 1): the planks sit at positions 0 and 1;
     * they only become grid slots 0 and 3 once mapped onto a 3x3. */
    private static RecipeView sticks() {
        return new RecipeView(
                "minecraft:sticks",
                "minecraft:stick",
                4,
                1,
                Map.of(
                        0,
                        List.of("minecraft:oak_planks", "minecraft:birch_planks"),
                        1,
                        List.of("minecraft:oak_planks", "minecraft:birch_planks")));
    }

    @Test
    void patternCoordinatesAreRowMajorWithinTheWidth() {
        RecipeView view = sticks();
        // Width 1: positions 0 and 1 are rows 0 and 1, column 0.
        assertTrue(view.fitsInventoryGrid());
        assertEquals(2, view.placements().size());
        assertEquals(
                List.of("minecraft:oak_planks", "minecraft:birch_planks"),
                view.placements().get(0));
    }

    @Test
    void threeByThreePatternsDoNotFitTheInventoryGrid() {
        Map<Integer, List<String>> full = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            full.put(i, List.of("minecraft:diamond"));
        }
        RecipeView block = new RecipeView("minecraft:diamond_block", "minecraft:diamond_block", 1, 3, full);
        assertFalse(block.fitsInventoryGrid());
        assertEquals(9, block.placements().size());
    }

    @Test
    void twoWidePatternWithBottomRowStillFitsTwoByTwo() {
        // 2 wide, top row only — columns 0..1, row 0.
        RecipeView torch = new RecipeView("test:x", "test:y", 1, 2, Map.of(0, List.of("a"), 1, List.of("b")));
        assertTrue(torch.fitsInventoryGrid());
        // Position 4 is pattern row 2 (width 2) → needs the table.
        RecipeView tall = new RecipeView("test:x", "test:y", 1, 2, Map.of(0, List.of("a"), 4, List.of("b")));
        assertFalse(tall.fitsInventoryGrid());
    }

    @Test
    void placementsAreDefensivelyCopied() {
        Map<Integer, List<String>> mutable = new HashMap<>();
        mutable.put(0, new java.util.ArrayList<>(List.of("minecraft:oak_planks")));
        RecipeView view = new RecipeView("test:x", "test:y", 1, 1, mutable);

        mutable.put(5, List.of("sneaky"));
        mutable.get(0).add("sneaky");
        assertFalse(view.placements().containsKey(5), "later caller mutation must not leak into the view");
        assertNotSame(mutable.get(0), view.placements().get(0));
        assertEquals(List.of("minecraft:oak_planks"), view.placements().get(0));
        assertThrows(
                UnsupportedOperationException.class, () -> view.placements().remove(0));
    }

    @Test
    void malformedDescriptionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecipeView(null, "r", 1, 1, Map.of(0, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView(" ", "r", 1, 1, Map.of(0, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", null, 1, 1, Map.of(0, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 0, 1, Map.of(0, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 1, 4, Map.of(0, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 1, 1, Map.of(9, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 1, 1, Map.of(-1, List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 1, 1, Map.of(0, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new RecipeView("t:r", "r", 1, 1, Map.of()));
    }
}
