package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared menu-snapshot fakes for the planner gate family: mutable
 * {@link Builder} placements frozen into role-table-mirrored
 * snapshots — crafting table (result 0 / grid 1..9 / MAIN 10..36 /
 * HOTBAR 37..45), inventory 2x2 grid, chest (CONTAINER 0..26 /
 * MAIN 27..53 / HOTBAR 54..62), furnace (INPUT/FUEL/OUTPUT + MAIN
 * 3..29 / HOTBAR 30..38).
 *
 * <p>Shared test infrastructure only; zero MC imports by design
 * (boundaries.md section A).
 */
final class MenuFixtures {

    /** Accept-kind pair shared by recipe and chest scenarios. */
    static final String OAK = "minecraft:oak_planks";

    static final String BIRCH = "minecraft:birch_planks";

    private MenuFixtures() {}

    /** Mutable placement fixture before freezing into a snapshot. */
    static final class Builder {
        private final Map<Integer, SlotRole> roles = new HashMap<>();
        private final Map<Integer, ItemView> items = new HashMap<>();

        Builder put(SlotRole role, int flat, String itemId, int count) {
            roles.put(flat, role);
            items.put(flat, new ItemView(itemId, count));
            return this;
        }
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * Sticks-shaped recipe: width 1, two plank cells accepting oak
     * or birch.
     */
    static RecipeView sticksRecipe() {
        return new RecipeView(
                "minecraft:stick", "minecraft:stick", 4, 1, Map.of(0, List.of(OAK, BIRCH), 1, List.of(OAK, BIRCH)));
    }

    /** Planks-shapeless recipe: one log ingredient, index encoding. */
    static RecipeView planksRecipe() {
        return new RecipeView(
                "minecraft:oak_planks",
                "minecraft:oak_planks",
                4,
                3,
                Map.of(0, List.of("minecraft:oak_log", "minecraft:birch_log")),
                true);
    }

    /**
     * Stew-shapeless recipe: three distinct ingredients (index
     * encoding) - fits the inventory 2x2 under the vanilla count
     * rule.
     */
    static RecipeView stewRecipe() {
        return new RecipeView(
                "minecraft:mushroom_stew",
                "minecraft:mushroom_stew",
                1,
                3,
                Map.of(
                        0, List.of("minecraft:red_mushroom"),
                        1, List.of("minecraft:brown_mushroom"),
                        2, List.of("minecraft:bowl")),
                true);
    }

    /**
     * Five-ingredient shapeless recipe: exceeds the inventory 2x2
     * count rule (four), fits a table.
     */
    static RecipeView fiveWayShapeless() {
        Map<Integer, List<String>> placements = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            placements.put(i, List.of("test:sugar"));
        }
        return new RecipeView("test:five_sugar", "test:bundle", 1, 3, placements, true);
    }

    /** Crafting-table-shaped snapshot, mirror of the adapter roles. */
    static CraftingView table(Builder b) {
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
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        MenuView view = new MenuView("crafting_table", null, ItemView.EMPTY, 46, slots);
        return CraftingView.of(view);
    }

    /** Inventory-menu fake: result 0, grid 1..4 (2x2), MAIN implicit,
     * HOTBAR 36..44. */
    static CraftingView inventoryGridMenu(Builder b) {
        b.roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= 4; i++) {
            b.roles.putIfAbsent(i, SlotRole.GRID);
        }
        for (int i = 36; i <= 44; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.getOrDefault(i, SlotRole.MAIN)));
        }
        return CraftingView.of(new MenuView("inventory", null, ItemView.EMPTY, 45, slots));
    }

    /** Chest-menu fake: CONTAINER 0..26, MAIN 27..53, HOTBAR 54..62. */
    static MenuView chest(Builder b) {
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
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("chest", null, ItemView.EMPTY, 63, slots);
    }

    /** Furnace-shaped snapshot: INPUT 0, FUEL 1, OUTPUT 2, MAIN
     * 3..29, HOTBAR 30..38 - vanilla AbstractFurnaceMenu mirrored. */
    static MenuView furnace(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.FUEL);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        for (int i = 3; i <= 29; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 30; i <= 38; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(39);
        for (int i = 0; i < 39; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("furnace", null, ItemView.EMPTY, 39, slots);
    }

    /** Anvil-shaped snapshot: INPUT 0 (target) and 1 (material),
     * OUTPUT 2, MAIN 3..29, HOTBAR 30..38 - vanilla AnvilMenu
     * (ItemCombinerMenu) mirrored. Both inputs share INPUT in v1. */
    static MenuView anvil(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        for (int i = 3; i <= 29; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 30; i <= 38; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(39);
        for (int i = 0; i < 39; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("anvil", null, ItemView.EMPTY, 39, slots);
    }

    /** Brewing-stand-shaped snapshot: BOTTLE 0..2, INGREDIENT 3,
     * FUEL 4, MAIN 5..31, HOTBAR 32..40 - vanilla BrewingStandMenu
     * mirrored (41 slots total). */
    static MenuView brewingStand(Builder b) {
        for (int i = 0; i <= 2; i++) {
            b.roles.putIfAbsent(i, SlotRole.BOTTLE);
        }
        b.roles.putIfAbsent(3, SlotRole.INGREDIENT);
        b.roles.putIfAbsent(4, SlotRole.FUEL);
        for (int i = 5; i <= 31; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 32; i <= 40; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(41);
        for (int i = 0; i < 41; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("brewing_stand", null, ItemView.EMPTY, 41, slots);
    }

    /** Grindstone-shaped snapshot: INPUT 0 and 1, OUTPUT 2, MAIN
     * 3..29, HOTBAR 30..38 - same 3-slot layout as anvil. */
    static MenuView grindstone(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        for (int i = 3; i <= 29; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 30; i <= 38; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(39);
        for (int i = 0; i < 39; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("grindstone", null, ItemView.EMPTY, 39, slots);
    }

    /** Stonecutter-shaped snapshot: INPUT 0, OUTPUT 1, MAIN 2..28,
     * HOTBAR 29..37 - vanilla StonecutterMenu mirrored (38 slots). */
    static MenuView stonecutter(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.OUTPUT);
        for (int i = 2; i <= 28; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 29; i <= 37; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        List<SlotView> slots = new ArrayList<>(38);
        for (int i = 0; i < 38; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("stonecutter", null, ItemView.EMPTY, 38, slots);
    }

    /** Own-inventory fake: result 0, grid 1..4, ARMOR 5..8 (head
     * first), MAIN 9..35, HOTBAR 36..44, OFFHAND 45 - the
     * MenuSlotLayouts survival layout mirrored. */
    static MenuView ownInventory(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.RESULT);
        for (int i = 1; i <= 4; i++) {
            b.roles.putIfAbsent(i, SlotRole.GRID);
        }
        for (int i = 5; i <= 8; i++) {
            b.roles.putIfAbsent(i, SlotRole.ARMOR);
        }
        for (int i = 9; i <= 35; i++) {
            b.roles.putIfAbsent(i, SlotRole.MAIN);
        }
        for (int i = 36; i <= 44; i++) {
            b.roles.putIfAbsent(i, SlotRole.HOTBAR);
        }
        b.roles.putIfAbsent(45, SlotRole.OFFHAND);
        List<SlotView> slots = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView("inventory", null, ItemView.EMPTY, 46, slots);
    }
}
