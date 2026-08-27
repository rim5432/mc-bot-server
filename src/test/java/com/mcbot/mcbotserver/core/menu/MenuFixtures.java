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
