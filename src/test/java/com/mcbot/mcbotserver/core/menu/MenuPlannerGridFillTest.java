package com.mcbot.mcbotserver.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grid-fill economics gate for {@link MenuPlanner}: one whole-stack
 * lift per source, right-click deposits exactly one item per cell,
 * remainder returns to its source, and an unsatisfiable or malformed
 * demand fails before any step exists. Also the result-slot take.
 *
 * <p>Contract: see boundaries.md section A (core holds no menu
 * state) and issue 0007 Phase 2.
 */
class MenuPlannerGridFillTest {

    private static final String DIAMOND = "minecraft:diamond";

    @Test
    void exactStackFillsNineCellsWithNoReturn() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, DIAMOND, 9));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu, DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(37, 0, MenuClick.PICKUP));
        for (int p = 0; p < 9; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1, MenuClick.PICKUP));
        }
        assertEquals(expected, plan);
    }

    @Test
    void oversizedStackReturnsItsRemainder() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, DIAMOND, 64));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu, DIAMOND, 4, 8);

        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(5, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(9, 1, MenuClick.PICKUP),
                        // 62 diamonds go back where they came from.
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void demandSpansMultipleSources() {
        CraftingView menu = MenuFixtures.table(
                MenuFixtures.builder().put(SlotRole.MAIN, 10, DIAMOND, 5).put(SlotRole.HOTBAR, 40, DIAMOND, 7));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu, DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(10, 0, MenuClick.PICKUP));
        for (int p = 0; p < 5; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1, MenuClick.PICKUP));
        }
        expected.add(new MenuPlanner.Step(40, 0, MenuClick.PICKUP));
        for (int p = 5; p < 9; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1, MenuClick.PICKUP));
        }
        // 3 left on the cursor return to the second source.
        expected.add(new MenuPlanner.Step(40, 0, MenuClick.PICKUP));
        assertEquals(expected, plan);
    }

    @Test
    void mixedRecipeComposesFromSingleMaterialPlans() {
        String plank = "minecraft:oak_planks";
        String stick = "minecraft:stick";
        CraftingView menu = MenuFixtures.table(
                MenuFixtures.builder().put(SlotRole.HOTBAR, 37, plank, 12).put(SlotRole.HOTBAR, 38, stick, 8));

        // Wooden pickaxe column by column: each sub-plan leaves the
        // cursor empty, so the next one starts clean.
        List<MenuPlanner.Step> planks = MenuPlanner.planGridFill(menu, plank, 0, 3, 6);
        List<MenuPlanner.Step> sticks = MenuPlanner.planGridFill(menu, stick, 1, 4);

        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(7, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                planks);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(2, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(5, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                sticks);
    }

    @Test
    void insufficientSupplyFailsBeforeAnyStepExists() {
        CraftingView menu = MenuFixtures.table(
                MenuFixtures.builder().put(SlotRole.MAIN, 10, DIAMOND, 3).put(SlotRole.HOTBAR, 37, DIAMOND, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuPlanner.planGridFill(menu, DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void refusesToOverwriteAForeignItemInTheGrid() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder()
                .put(SlotRole.HOTBAR, 37, DIAMOND, 9)
                .put(SlotRole.GRID, 4, "minecraft:stick", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuPlanner.planGridFill(menu, DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void malformedArgumentsAreRejected() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, DIAMOND, 9));

        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, null, 0));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, "  ", 0));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, DIAMOND));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, DIAMOND, 9));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, DIAMOND, -1));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planGridFill(menu, DIAMOND, 2, 2));
    }

    @Test
    void takeResultShiftClicksTheProductAway() {
        CraftingView menu =
                MenuFixtures.table(MenuFixtures.builder().put(SlotRole.RESULT, 0, "minecraft:diamond_block", 1));
        assertEquals(List.of(new MenuPlanner.Step(0, 0, MenuClick.QUICK_MOVE)), MenuPlanner.planTakeResult(menu));
    }

    @Test
    void takeResultRefusesAnUnresolvedGrid() {
        CraftingView menu = MenuFixtures.table(MenuFixtures.builder());
        assertThrows(IllegalStateException.class, () -> MenuPlanner.planTakeResult(menu));
    }
}
