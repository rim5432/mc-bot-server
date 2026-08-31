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
// TooManyMethods exempted: the class IS the fixture catalog - one
// flat table of per-menu-kind builders mirroring MenuSlotLayouts;
// splitting it would hide the mirror.
@SuppressWarnings("PMD.TooManyMethods")
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
     * Fills {@code from..to} inclusive with {@code role} unless the
     * scenario already placed an explicit role there.
     */
    private static void fillRange(Builder b, int from, int to, SlotRole role) {
        for (int i = from; i <= to; i++) {
            b.roles.putIfAbsent(i, role);
        }
    }

    /**
     * Freezes the builder into a kind-tagged snapshot of {@code size}
     * slots; every slot must carry a role by now.
     */
    private static MenuView freeze(String kind, Builder b, int size) {
        List<SlotView> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.get(i)));
        }
        return new MenuView(kind, null, ItemView.EMPTY, size, slots, null);
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
        fillRange(b, 1, 9, SlotRole.GRID);
        fillRange(b, 10, 36, SlotRole.MAIN);
        fillRange(b, 37, 45, SlotRole.HOTBAR);
        return CraftingView.of(freeze("crafting_table", b, 46));
    }

    /** Inventory-menu fake: result 0, grid 1..4 (2x2), MAIN implicit,
     * HOTBAR 36..44. */
    static CraftingView inventoryGridMenu(Builder b) {
        b.roles.put(0, SlotRole.RESULT);
        fillRange(b, 1, 4, SlotRole.GRID);
        fillRange(b, 36, 44, SlotRole.HOTBAR);
        // MAIN is the implicit default here, unlike every other
        // fixture whose ranges cover the full slot space.
        List<SlotView> slots = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            slots.add(new SlotView(i, b.items.getOrDefault(i, ItemView.EMPTY), b.roles.getOrDefault(i, SlotRole.MAIN)));
        }
        return CraftingView.of(new MenuView("inventory", null, ItemView.EMPTY, 45, slots, null));
    }

    /** Chest-menu fake: CONTAINER 0..26, MAIN 27..53, HOTBAR 54..62. */
    static MenuView chest(Builder b) {
        fillRange(b, 0, 26, SlotRole.CONTAINER);
        fillRange(b, 27, 53, SlotRole.MAIN);
        fillRange(b, 54, 62, SlotRole.HOTBAR);
        return freeze("chest", b, 63);
    }

    /** Furnace-shaped snapshot: INPUT 0, FUEL 1, OUTPUT 2, MAIN
     * 3..29, HOTBAR 30..38 - vanilla AbstractFurnaceMenu mirrored. */
    static MenuView furnace(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.FUEL);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        fillRange(b, 3, 29, SlotRole.MAIN);
        fillRange(b, 30, 38, SlotRole.HOTBAR);
        return freeze("furnace", b, 39);
    }

    /** Anvil-shaped snapshot: INPUT 0 (target) and 1 (material),
     * OUTPUT 2, MAIN 3..29, HOTBAR 30..38 - vanilla AnvilMenu
     * (ItemCombinerMenu) mirrored. Both inputs share INPUT in v1. */
    static MenuView anvil(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        fillRange(b, 3, 29, SlotRole.MAIN);
        fillRange(b, 30, 38, SlotRole.HOTBAR);
        return freeze("anvil", b, 39);
    }

    /** Brewing-stand-shaped snapshot: BOTTLE 0..2, INGREDIENT 3,
     * FUEL 4, MAIN 5..31, HOTBAR 32..40 - vanilla BrewingStandMenu
     * mirrored (41 slots total). */
    static MenuView brewingStand(Builder b) {
        fillRange(b, 0, 2, SlotRole.BOTTLE);
        b.roles.putIfAbsent(3, SlotRole.INGREDIENT);
        b.roles.putIfAbsent(4, SlotRole.FUEL);
        fillRange(b, 5, 31, SlotRole.MAIN);
        fillRange(b, 32, 40, SlotRole.HOTBAR);
        return freeze("brewing_stand", b, 41);
    }

    /** Grindstone-shaped snapshot: INPUT 0 and 1, OUTPUT 2, MAIN
     * 3..29, HOTBAR 30..38 - same 3-slot layout as anvil. */
    static MenuView grindstone(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        fillRange(b, 3, 29, SlotRole.MAIN);
        fillRange(b, 30, 38, SlotRole.HOTBAR);
        return freeze("grindstone", b, 39);
    }

    /** Stonecutter-shaped snapshot: INPUT 0, OUTPUT 1, MAIN 2..28,
     * HOTBAR 29..37 - vanilla StonecutterMenu mirrored (38 slots). */
    static MenuView stonecutter(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.OUTPUT);
        fillRange(b, 2, 28, SlotRole.MAIN);
        fillRange(b, 29, 37, SlotRole.HOTBAR);
        return freeze("stonecutter", b, 38);
    }

    /** Cartography-table-shaped snapshot: INPUT 0 and 1, OUTPUT 2,
     * MAIN 3..29, HOTBAR 30..38 - same 3-slot layout as anvil. */
    static MenuView cartographyTable(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        fillRange(b, 3, 29, SlotRole.MAIN);
        fillRange(b, 30, 38, SlotRole.HOTBAR);
        return freeze("cartography_table", b, 39);
    }

    /** Smithing-table-shaped snapshot: INPUT 0/1/2 (template/base/
     * additional), OUTPUT 3, MAIN 4..30, HOTBAR 31..39 - 40 slots. */
    static MenuView smithingTable(Builder b) {
        fillRange(b, 0, 2, SlotRole.INPUT);
        b.roles.putIfAbsent(3, SlotRole.OUTPUT);
        fillRange(b, 4, 30, SlotRole.MAIN);
        fillRange(b, 31, 39, SlotRole.HOTBAR);
        return freeze("smithing_table", b, 40);
    }

    /** Loom-shaped snapshot: INPUT 0/1/2 (banner/dye/pattern),
     * OUTPUT 3, MAIN 4..30, HOTBAR 31..39 - same 4-slot layout as
     * smithing table (40 slots). */
    static MenuView loom(Builder b) {
        fillRange(b, 0, 2, SlotRole.INPUT);
        b.roles.putIfAbsent(3, SlotRole.OUTPUT);
        fillRange(b, 4, 30, SlotRole.MAIN);
        fillRange(b, 31, 39, SlotRole.HOTBAR);
        return freeze("loom", b, 40);
    }

    /** Enchanting-table-shaped snapshot: INPUT 0 (item) and 1
     * (lapis), MAIN 2..28, HOTBAR 29..37 - no OUTPUT slot; the
     * enchanted item stays in slot 0 after a button click. */
    static MenuView enchantingTable(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        fillRange(b, 2, 28, SlotRole.MAIN);
        fillRange(b, 29, 37, SlotRole.HOTBAR);
        return freeze("enchanting_table", b, 38);
    }

    /** Beacon-shaped snapshot: INPUT 0 (payment ingot), MAIN 1..27,
     * HOTBAR 28..36 - no OUTPUT slot; effects are selected via the
     * beacon effect UI, not a slot transaction. */
    static MenuView beacon(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        fillRange(b, 1, 27, SlotRole.MAIN);
        fillRange(b, 28, 36, SlotRole.HOTBAR);
        return freeze("beacon", b, 37);
    }

    /** Merchant-shaped snapshot: INPUT 0 and 1 (buy items), OUTPUT 2
     * (trade result), MAIN 3..29, HOTBAR 30..38 - same 3-slot layout
     * as anvil (39 slots). */
    static MenuView merchant(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.INPUT);
        b.roles.putIfAbsent(1, SlotRole.INPUT);
        b.roles.putIfAbsent(2, SlotRole.OUTPUT);
        fillRange(b, 3, 29, SlotRole.MAIN);
        fillRange(b, 30, 38, SlotRole.HOTBAR);
        return freeze("merchant", b, 39);
    }

    /** Horse-shaped snapshot (plain horse, no chest): ARMOR 0,
     * CONTAINER 1 (saddle/carpet slot), MAIN 2..28, HOTBAR 29..37 -
     * 38 slots. Chested horses/donkeys/mules gain CONTAINER slots
     * between index 2 and the player region. */
    static MenuView horse(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.ARMOR);
        b.roles.putIfAbsent(1, SlotRole.CONTAINER);
        fillRange(b, 2, 28, SlotRole.MAIN);
        fillRange(b, 29, 37, SlotRole.HOTBAR);
        return freeze("horse", b, 38);
    }

    /** Own-inventory fake: result 0, grid 1..4, ARMOR 5..8 (head
     * first), MAIN 9..35, HOTBAR 36..44, OFFHAND 45 - the
     * MenuSlotLayouts survival layout mirrored. */
    static MenuView ownInventory(Builder b) {
        b.roles.putIfAbsent(0, SlotRole.RESULT);
        fillRange(b, 1, 4, SlotRole.GRID);
        fillRange(b, 5, 8, SlotRole.ARMOR);
        fillRange(b, 9, 35, SlotRole.MAIN);
        fillRange(b, 36, 44, SlotRole.HOTBAR);
        b.roles.putIfAbsent(45, SlotRole.OFFHAND);
        return freeze("inventory", b, 46);
    }
}
