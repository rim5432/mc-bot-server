package com.mcbot.mcbotserver.api.menu;

import com.mcbot.mcbotserver.api.inventory.ItemView;

/**
 * Read-only snapshot of a single menu slot. Pure data — the adapter
 * produces these from an {@code AbstractContainerMenu}'s slot list,
 * and the core planner reads them to decide click sequences. The core
 * never writes slots directly (issue 0007 risk: "core never writes menu
 * state" — {@code ResultSlot.onTake} consumes grid materials on take,
 * and a core-side writer bypasses that and duplicates items).
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 2. The slot index is the menu's flat index (0..containerSize-1),
 * not the inventory compartment index — the menu's slot layout differs
 * from the raw inventory (crafting grid, result, armor, main, hotbar are
 * all interleaved by the menu's {@code addSlot} order). The role says
 * what the slot IS so planners never hardcode per-type flat layouts
 * (the gametest-era "menu slot 37 = hotbar 0" knowledge now lives in
 * the adapter's role table alone).
 *
 * @param index the menu's flat slot index
 * @param item  the item in this slot; {@link ItemView#EMPTY} for empty
 * @param role  what this slot is; never null
 */
public record SlotView(int index, ItemView item, SlotRole role) {

    /**
     * Creates a validated slot view.
     *
     * @param index must not be negative
     * @param item  must not be null
     * @param role  must not be null
     */
    public SlotView {
        if (index < 0) {
            throw new IllegalArgumentException("slot index must not be negative, got " + index);
        }
        if (item == null) {
            throw new IllegalArgumentException("slot item must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("slot role must not be null");
        }
    }

    /**
     * Whether this slot holds no item.
     *
     * @return true when the item is empty
     */
    public boolean isEmpty() {
        return item.isEmpty();
    }
}
