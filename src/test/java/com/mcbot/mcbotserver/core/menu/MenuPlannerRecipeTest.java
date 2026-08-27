package com.mcbot.mcbotserver.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Recipe-driven fill gate for {@link MenuPlanner#planRecipe}:
 * pattern cells resolve first-fit against accepted kinds, anchor
 * top-left on whatever surface is open, fall back across kinds when
 * one runs out, map differently on the 2x2 inventory grid, refuse
 * table-only patterns there, and fail before any step when a cell
 * cannot be supplied.
 *
 * <p>Contract: see boundaries.md section A and issue 0007 Phase 3.
 */
class MenuPlannerRecipeTest {

    @Test
    void recipeResolvesAndAnchorsTopLeftOnATable() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, MenuFixtures.OAK, 64));
        // Pattern rows 0..1 map onto the 3x3 as grid cells 0 and 3.
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(menu, MenuFixtures.sticksRecipe()));
    }

    @Test
    void recipeFallsBackToLaterAcceptedKinds() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, MenuFixtures.BIRCH, 64));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(menu, MenuFixtures.sticksRecipe()));
    }

    @Test
    void recipeSplitsAcrossKindsWhenFirstRunsOut() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 37, MenuFixtures.OAK, 1)
                .put(SlotRole.HOTBAR, 38, MenuFixtures.BIRCH, 64));
        // Cell 0 takes the last oak; cell 1 falls to birch. Two
        // single-material sub-plans chained, cursor clean between.
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(menu, MenuFixtures.sticksRecipe()));
    }

    @Test
    void recipeOnTheTwoByTwoInventoryGridMapsDifferently() {
        CraftingView inv2x2 =
                MenuFixtures.inventoryGridMenu(MenuFixtures.builder().put(SlotRole.HOTBAR, 36, MenuFixtures.OAK, 64));
        // Pattern rows 0..1 map onto the 2x2 as grid cells 0 and 2;
        // the 62-item remainder returns to its hotbar source.
        assertEquals(
                List.of(
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(inv2x2, MenuFixtures.sticksRecipe()));
    }

    @Test
    void tableOnlyRecipeRefusesTheInventoryGrid() {
        Map<Integer, List<String>> full = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            full.put(i, List.of("minecraft:diamond"));
        }
        RecipeView block = new RecipeView("t:block", "minecraft:diamond_block", 1, 3, full);
        CraftingView inv2x2 = MenuFixtures.inventoryGridMenu(
                MenuFixtures.builder().put(SlotRole.HOTBAR, 36, "minecraft:diamond", 64));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planRecipe(inv2x2, block));

        // Sanity: the same recipe plans fine on the table surface -
        // one lift, nine deposits, remainder return.
        CraftingView menu =
                MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, "minecraft:diamond", 64));
        assertEquals(11, MenuPlanner.planRecipe(menu, block).size());
    }

    @Test
    void unsatisfiableRecipeCellFailsBeforeAnyStep() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, MenuFixtures.OAK, 1));
        // One oak covers cell 0 only; cell 1 has no source left.
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planRecipe(menu, MenuFixtures.sticksRecipe()));
    }

    @Test
    void shapelessFillsFirstFlatCellsOnTheInventoryGrid() {
        // Three ingredients fit the 2x2 under the vanilla count rule;
        // indices 0..2 map onto flat grid cells 1..3, one material
        // group per ingredient in recipe order.
        CraftingView menu = MenuFixtures.inventoryGridMenu(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 36, "minecraft:red_mushroom", 64)
                .put(SlotRole.HOTBAR, 37, "minecraft:brown_mushroom", 64)
                .put(SlotRole.HOTBAR, 38, "minecraft:bowl", 64));
        RecipeView stew = MenuFixtures.stewRecipe();
        assertTrue(stew.fitsInventoryGrid(), "3 ingredients fit a 2x2");
        assertEquals(
                List.of(
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(2, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(menu, stew));
    }

    @Test
    void shapelessMoreThanFourIngredientsNeedsATable() {
        RecipeView five = MenuFixtures.fiveWayShapeless();
        assertFalse(five.fitsInventoryGrid(), "5 ingredients exceed a 2x2");
        CraftingView inv2x2 =
                MenuFixtures.inventoryGridMenu(MenuFixtures.builder().put(SlotRole.HOTBAR, 36, "test:sugar", 5));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planRecipe(inv2x2, five));

        // On the table the five indices fill flat cells 1..5 from one
        // exhausted lift - no remainder return.
        CraftingView table = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 36, "test:sugar", 5));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(2, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(5, 1, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(table, five));
    }

    @Test
    void shapelessNestedAcceptanceSpendsTheScarceCellFirst() {
        // Index 0 accepts oak-or-birch, index 1 only oak; one oak
        // left. Scarce-first resolves index 1 (width 1) before the
        // wide cell, so the oak lands where only oak works and the
        // wide cell falls to birch - the vanilla multiset matcher's
        // assignment, mirrored in supply decrements.
        RecipeView recipe = new RecipeView(
                "test:pair",
                "test:bundle",
                1,
                3,
                Map.of(0, List.of(MenuFixtures.OAK, MenuFixtures.BIRCH), 1, List.of(MenuFixtures.OAK)),
                true);
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 36, MenuFixtures.OAK, 1)
                .put(SlotRole.HOTBAR, 37, MenuFixtures.BIRCH, 64));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(2, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                MenuPlanner.planRecipe(menu, recipe));
    }

    @Test
    void shapelessPositionsMustBeCompactIndices() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecipeView("t:x", "test:out", 1, 3, Map.of(1, List.of(MenuFixtures.OAK)), true));
    }
}
