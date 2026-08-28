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
}
