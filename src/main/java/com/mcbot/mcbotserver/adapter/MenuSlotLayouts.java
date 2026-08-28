package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.SlotRole;

/**
 * Slot-role layouts for the vanilla menu kinds the bridge opens:
 * pure index->role arithmetic, no menu instance touched.
 *
 * <p>Extracted from BindingMenu so the write-half bridge stops
 * carrying layout tables (god-class paydown, 2026-08-27).
 */
final class MenuSlotLayouts {

    private MenuSlotLayouts() {}

    /** CraftingTable layout: slot 0 result, 1-9 grid, then player region. */
    public static SlotRole craftingRole(int index, int size) {
        if (index == 0) {
            return SlotRole.RESULT;
        }
        if (index <= 9) {
            return SlotRole.GRID;
        }
        return index <= size - 9 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Survival-inventory layout: 0 result, 1-4 grid, 5-8 armor,
     * last offhand; the rest is the standard main/hotbar split.
     */
    public static SlotRole inventoryRole(int index, int size) {
        if (index == 0) {
            return SlotRole.RESULT;
        }
        if (index <= 4) {
            return SlotRole.GRID;
        }
        if (index <= 8) {
            return SlotRole.ARMOR;
        }
        if (index == size - 1) {
            return SlotRole.OFFHAND;
        }
        return index <= 35 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Furnace family (furnace / blast_furnace / smoker): vanilla
     * AbstractFurnaceMenu adds slot 0 = input, 1 = fuel, 2 =
     * result, then the standard 27 main + 9 hotbar player region.
     */
    public static SlotRole furnaceRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        if (index == 1) {
            return SlotRole.FUEL;
        }
        if (index == 2) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Anvil layout (ItemCombinerMenu): vanilla slots 0 = left input
     * (target item), 1 = right input (material / enchantment book),
     * 2 = result, then the standard 27 main + 9 hotbar. Both inputs
     * share the INPUT role in v1 — the harness addresses the right
     * input by flat index when it needs material-specific placement.
     */
    public static SlotRole anvilRole(int index, int size) {
        if (index == 0 || index == 1) {
            return SlotRole.INPUT;
        }
        if (index == 2) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Brewing stand layout: vanilla BrewingStandMenu adds slots 0-2 =
     * bottles (input water bottle, output finished potion - same slot),
     * 3 = ingredient (nether wart, glowstone, redstone, ...), 4 = fuel
     * (blaze powder), then the standard 27 main + 9 hotbar.
     */
    public static SlotRole brewingRole(int index, int size) {
        if (index <= 2) {
            return SlotRole.BOTTLE;
        }
        if (index == 3) {
            return SlotRole.INGREDIENT;
        }
        if (index == 4) {
            return SlotRole.FUEL;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Stonecutter layout: vanilla StonecutterMenu adds slot 0 = input
     * (stone material), slot 1 = result (cut block), then the standard
     * 27 main + 9 hotbar. The recipe list is a UI-side selection, not
     * a slot — the harness picks the recipe by clicking the result after
     * the input is placed.
     */
    public static SlotRole stonecutterRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        if (index == 1) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Three-input-one-output layout shared by SmithingMenu (template,
     * base, additional -> result) and LoomMenu (banner, dye, pattern
     * -> result). Slots 0-2 = INPUT, slot 3 = OUTPUT, then the
     * standard 27 main + 9 hotbar.
     */
    public static SlotRole threeInputRole(int index, int size) {
        if (index <= 2) {
            return SlotRole.INPUT;
        }
        if (index == 3) {
            return SlotRole.OUTPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Enchanting table layout: vanilla EnchantmentMenu adds slot 0 =
     * item (enchant target), slot 1 = lapis lazuli, then the standard
     * 27 main + 9 hotbar. No output slot — the enchanted item stays in
     * slot 0 after a button click (enchantment id 0/1/2).
     */
    public static SlotRole enchantingRole(int index, int size) {
        if (index == 0 || index == 1) {
            return SlotRole.INPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Beacon layout: vanilla BeaconMenu adds slot 0 = payment (iron/
     * gold/diamond/emerald/netherite ingot), then the standard 27 main
     * + 9 hotbar. No output slot — effects are selected via the beacon
     * effect UI, not a slot transaction.
     */
    public static SlotRole beaconRole(int index, int size) {
        if (index == 0) {
            return SlotRole.INPUT;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }

    /**
     * Horse inventory layout: vanilla HorseInventoryMenu adds the
     * horse's own slots first (slot 0 = armor, slot 1 = carpet/saddle
     * or empty, slots 2+ = chest slots for donkeys/mules/llamas with
     * chests), then the standard 27 main + 9 hotbar. The horse slot
     * count = size - 36, so chest slots run from index 2 to size-37
     * (empty for plain horses).
     */
    public static SlotRole horseRole(int index, int size) {
        if (index == 0) {
            return SlotRole.ARMOR;
        }
        if (index == 1) {
            return SlotRole.CONTAINER;
        }
        if (index <= size - 37) {
            return SlotRole.CONTAINER;
        }
        return index <= size - 10 ? SlotRole.MAIN : SlotRole.HOTBAR;
    }
}
