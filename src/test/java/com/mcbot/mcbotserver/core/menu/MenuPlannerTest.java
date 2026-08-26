package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Offline gates for {@link MenuPlanner}: the click-sequence plans a
 * core process emits before handing them to the transaction surface.
 * Pure Java — zero MC imports; the fakes mirror the adapter's
 * crafting-table role table (result 0, grid 1..9, MAIN 10..36, HOTBAR
 * 37..45).
 *
 * <p>Contract: see boundaries.md section A (core holds no menu state)
 * and issue 0007 Phase 2. The economics under test: one whole-stack
 * lift per source, right-click deposits exactly one item per cell,
 * remainder returns to its source, and an unsatisfiable demand fails
 * before any step exists.
 */
class MenuPlannerTest {

    private static final String DIAMOND = "minecraft:diamond";

    @Test
    void exactStackFillsNineCellsWithNoReturn() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, DIAMOND, 9));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu,
            DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(37, 0, MenuClick.PICKUP));
        for (int p = 0; p < 9; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1,
                MenuClick.PICKUP));
        }
        assertEquals(expected, plan);
    }

    @Test
    void oversizedStackReturnsItsRemainder() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, DIAMOND, 64));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu,
            DIAMOND, 4, 8);

        assertEquals(List.of(
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(5, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(9, 1, MenuClick.PICKUP),
            // 62 diamonds go back where they came from.
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
            plan);
    }

    @Test
    void demandSpansMultipleSources() {
        CraftingView menu = table(builder()
            .put(SlotRole.MAIN, 10, DIAMOND, 5)
            .put(SlotRole.HOTBAR, 40, DIAMOND, 7));
        List<MenuPlanner.Step> plan = MenuPlanner.planGridFill(menu,
            DIAMOND, 0, 1, 2, 3, 4, 5, 6, 7, 8);

        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(10, 0, MenuClick.PICKUP));
        for (int p = 0; p < 5; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1,
                MenuClick.PICKUP));
        }
        expected.add(new MenuPlanner.Step(40, 0, MenuClick.PICKUP));
        for (int p = 5; p < 9; p++) {
            expected.add(new MenuPlanner.Step(p + 1, 1,
                MenuClick.PICKUP));
        }
        // 3 left on the cursor return to the second source.
        expected.add(new MenuPlanner.Step(40, 0, MenuClick.PICKUP));
        assertEquals(expected, plan);
    }

    @Test
    void mixedRecipeComposesFromSingleMaterialPlans() {
        String PLANK = "minecraft:oak_planks";
        String STICK = "minecraft:stick";
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, PLANK, 12)
            .put(SlotRole.HOTBAR, 38, STICK, 8));

        // Wooden pickaxe column by column: each sub-plan leaves the
        // cursor empty, so the next one starts clean.
        List<MenuPlanner.Step> planks = MenuPlanner.planGridFill(menu,
            PLANK, 0, 3, 6);
        List<MenuPlanner.Step> sticks = MenuPlanner.planGridFill(menu,
            STICK, 1, 4);

        assertEquals(List.of(
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(7, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
            planks);
        assertEquals(List.of(
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(2, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(5, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
            sticks);
    }

    @Test
    void insufficientSupplyFailsBeforeAnyStepExists() {
        CraftingView menu = table(builder()
            .put(SlotRole.MAIN, 10, DIAMOND, 3)
            .put(SlotRole.HOTBAR, 37, DIAMOND, 5));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND,
                0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void refusesToOverwriteAForeignItemInTheGrid() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, DIAMOND, 9)
            .put(SlotRole.GRID, 4, "minecraft:stick", 1));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND,
                0, 1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    void malformedArgumentsAreRejected() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, DIAMOND, 9));

        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, null, 0));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, "  ", 0));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND, 9));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND, -1));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planGridFill(menu, DIAMOND, 2, 2));
    }

    @Test
    void takeResultShiftClicksTheProductAway() {
        CraftingView menu = table(builder()
            .put(SlotRole.RESULT, 0, "minecraft:diamond_block", 1));
        assertEquals(List.of(new MenuPlanner.Step(0, 0,
                MenuClick.QUICK_MOVE)),
            MenuPlanner.planTakeResult(menu));
    }

    @Test
    void takeResultRefusesAnUnresolvedGrid() {
        CraftingView menu = table(builder());
        assertThrows(IllegalStateException.class,
            () -> MenuPlanner.planTakeResult(menu));
    }

    // ── recipe-driven fill ───────────────────────────────────

    private static final String OAK = "minecraft:oak_planks";
    private static final String BIRCH = "minecraft:birch_planks";

    /** Sticks-shaped recipe: width 1, two plank cells (pattern
     * positions 0 and 1). */
    private static RecipeView sticksRecipe() {
        return new RecipeView("minecraft:stick", "minecraft:stick",
            4, 1, Map.of(0, List.of(OAK, BIRCH),
                1, List.of(OAK, BIRCH)));
    }

    @Test
    void recipeResolvesAndAnchorsTopLeftOnATable() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, OAK, 64));
        // Pattern rows 0..1 map onto the 3x3 as grid cells 0 and 3.
        assertEquals(List.of(
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(menu, sticksRecipe()));
    }

    @Test
    void recipeFallsBackToLaterAcceptedKinds() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 38, BIRCH, 64));
        assertEquals(List.of(
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(menu, sticksRecipe()));
    }

    @Test
    void recipeSplitsAcrossKindsWhenFirstRunsOut() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, OAK, 1)
            .put(SlotRole.HOTBAR, 38, BIRCH, 64));
        // Cell 0 takes the last oak; cell 1 falls to birch. Two
        // single-material sub-plans chained, cursor clean between.
        assertEquals(List.of(
            new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(menu, sticksRecipe()));
    }

    @Test
    void recipeOnTheTwoByTwoInventoryGridMapsDifferently() {
        CraftingView inv2x2 = inventoryGridMenu(builder()
            .put(SlotRole.HOTBAR, 36, OAK, 64));
        // Pattern rows 0..1 map onto the 2x2 as grid cells 0 and 2;
        // the 62-item remainder returns to its hotbar source.
        assertEquals(List.of(
            new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
            new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
            new MenuPlanner.Step(36, 0, MenuClick.PICKUP)),
            MenuPlanner.planRecipe(inv2x2, sticksRecipe()));
    }

    @Test
    void tableOnlyRecipeRefusesTheInventoryGrid() {
        Map<Integer, List<String>> full = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            full.put(i, List.of("minecraft:diamond"));
        }
        RecipeView block = new RecipeView("t:block",
            "minecraft:diamond_block", 1, 3, full);
        CraftingView inv2x2 = inventoryGridMenu(builder()
            .put(SlotRole.HOTBAR, 36, "minecraft:diamond", 64));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planRecipe(inv2x2, block));

        // Sanity: the same recipe plans fine on the table surface -
        // one lift, nine deposits, remainder return.
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, "minecraft:diamond", 64));
        assertEquals(11,
            MenuPlanner.planRecipe(menu, block).size());
    }

    @Test
    void unsatisfiableRecipeCellFailsBeforeAnyStep() {
        CraftingView menu = table(builder()
            .put(SlotRole.HOTBAR, 37, OAK, 1));
        // One oak covers cell 0 only; cell 1 has no source left.
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planRecipe(menu, sticksRecipe()));
    }

    // ── chest quick-move sequences ───────────────────────────

    /** Chest-menu fake: CONTAINER 0..26, MAIN 27..53, HOTBAR
     * 54..62 — the adapter's role table mirrored in pure data. */
    private static MenuView chest(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.CONTAINER);
        for (int i = 0; i <= 26; i++) {
            b.roles.putIfAbsent(i, SlotRole.CONTAINER);
        }
        for (int i = 27; i <= 53; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 54; i <= 62; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(63);
        for (int i = 0; i < 63; i++) {
            slots.add(new SlotView(i,
                b.items.getOrDefault(i, ItemView.EMPTY),
                b.roles.get(i)));
        }
        return new MenuView("chest", null, ItemView.EMPTY, 63, slots);
    }

    @Test
    void withdrawShiftsWholeStacksUntilDemandIsMet() {
        MenuView chest = chest(builder()
            .put(SlotRole.CONTAINER, 3, OAK, 5)
            .put(SlotRole.CONTAINER, 9, BIRCH, 10)
            .put(SlotRole.CONTAINER, 14, OAK, 10));

        // 5 from slot 3 already meets minCount 5 - one shift-click.
        assertEquals(List.of(
            new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, OAK, 5));

        // minCount 6 overshoots into the second oak stack.
        assertEquals(List.of(
            new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE),
            new MenuPlanner.Step(14, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, OAK, 6));

        // Other kinds are never touched.
        assertEquals(List.of(
            new MenuPlanner.Step(9, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, BIRCH, 1));
    }

    @Test
    void withdrawRejectsShortContainersAndBadArgs() {
        MenuView chest = chest(builder()
            .put(SlotRole.CONTAINER, 3, OAK, 5));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, OAK, 6));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, OAK, 0));
    }

    @Test
    void depositShiftsEveryPlayerStackOfTheKind() {
        MenuView chest = chest(builder()
            .put(SlotRole.MAIN, 30, "minecraft:stick", 4)
            .put(SlotRole.HOTBAR, 55, "minecraft:stick", 12)
            .put(SlotRole.HOTBAR, 56, OAK, 7));
        assertEquals(List.of(
            new MenuPlanner.Step(30, 0, MenuClick.QUICK_MOVE),
            new MenuPlanner.Step(55, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planDeposit(chest, "minecraft:stick"));
    }

    @Test
    void depositRefusesWhenNothingMatches() {
        MenuView chest = chest(builder()
            .put(SlotRole.CONTAINER, 0, "minecraft:stick", 4));
        assertThrows(IllegalStateException.class,
            () -> MenuPlanner.planDeposit(chest, "minecraft:stick"));
    }

    // ── fakes ────────────────────────────────────────────────

    /** Mutable fixture for item placements before freezing into a
     * snapshot. */
    private static final class Builder {
        private final Map<Integer, SlotRole> roles = new HashMap<>();
        private final Map<Integer, ItemView> items = new HashMap<>();

        Builder put(SlotRole role, int flat, String itemId, int count) {
            roles.put(flat, role);
            items.put(flat, new ItemView(itemId, count));
            return this;
        }
    }

    private static Builder builder() {
        return new Builder();
    }

    /** Crafting-table-shaped snapshot: result 0, grid 1..9, MAIN
     * 10..36, HOTBAR 37..45 — the adapter's role table mirrored in
     * pure data. */
    private static CraftingView table(Builder b) {
        b.roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= 9; i++) {
            b.roles.putIfAbsent(i, SlotRole.GRID);
        }
        for (int i = 10; i <= 36; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 37; i <= 45; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            slots.add(new SlotView(i,
                b.items.getOrDefault(i, ItemView.EMPTY),
                b.roles.get(i)));
        }
        MenuView view = new MenuView("crafting_table", null,
            ItemView.EMPTY, 46, slots);
        return CraftingView.of(view);
    }

    /** Inventory-menu fake: result 0, grid 1..4 (2x2), MAIN 9..35,
     * HOTBAR 36..44 — the adapter's role table mirrored in pure
     * data. */
    private static CraftingView inventoryGridMenu(Builder b) {
        b.roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= 4; i++) {
            b.roles.putIfAbsent(i, SlotRole.GRID);
        }
        for (int i = 36; i <= 44; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            slots.add(new SlotView(i,
                b.items.getOrDefault(i, ItemView.EMPTY),
                b.roles.getOrDefault(i, SlotRole.MAIN)));
        }
        return CraftingView.of(new MenuView("inventory", null,
            ItemView.EMPTY, 45, slots));
    }
}
