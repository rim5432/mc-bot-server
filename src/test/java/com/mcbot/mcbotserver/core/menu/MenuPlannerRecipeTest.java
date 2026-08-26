package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
            .put(SlotRole.HOTBAR, 37, MenuFixtures.OAK, 64));
        // Pattern rows 0..1 map onto the 3x3 as grid cells 0 and 3.
        assertEquals(List.of(
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(menu, MenuFixtures.sticksRecipe()));
    }

    @Test
    void recipeFallsBackToLaterAcceptedKinds() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
            .put(SlotRole.HOTBAR, 38, MenuFixtures.BIRCH, 64));
        assertEquals(List.of(
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
        assertEquals(List.of(
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
            MenuFixtures.inventoryGridMenu(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 36, MenuFixtures.OAK, 64));
        // Pattern rows 0..1 map onto the 2x2 as grid cells 0 and 2;
        // the 62-item remainder returns to its hotbar source.
        assertEquals(List.of(
            new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(36, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(inv2x2,
                MenuFixtures.sticksRecipe()));
    }

    @Test
    void tableOnlyRecipeRefusesTheInventoryGrid() {
        Map<Integer, List<String>> full = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            full.put(i, List.of("minecraft:diamond"));
        }
        RecipeView block = new RecipeView("t:block",
            "minecraft:diamond_block", 1, 3, full);
        CraftingView inv2x2 =
            MenuFixtures.inventoryGridMenu(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 36, "minecraft:diamond", 64));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planRecipe(inv2x2, block));

        // Sanity: the same recipe plans fine on the table surface -
        // one lift, nine deposits, remainder return.
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
            .put(SlotRole.HOTBAR, 37, "minecraft:diamond", 64));
        assertEquals(11,
            MenuPlanner.planRecipe(menu, block).size());
    }

    @Test
    void unsatisfiableRecipeCellFailsBeforeAnyStep() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
            .put(SlotRole.HOTBAR, 37, MenuFixtures.OAK, 1));
        // One oak covers cell 0 only; cell 1 has no source left.
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planRecipe(menu,
                MenuFixtures.sticksRecipe()));
    }
}
