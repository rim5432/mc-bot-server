package com.mcbot.mcbotserver.api.menu;

import java.util.List;

/**
 * Read-only snapshot of an open menu. Pure data — the adapter produces
 * this from an {@code AbstractContainerMenu}'s current state, and the
 * core planner reads it to decide click sequences. The core holds no
 * menu state (issue 0007 risk 2): every mutation goes through the
 * adapter's {@code menu.clicked()}, so {@code ResultSlot.onTake}
 * consumes grid materials correctly.
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 2. The {@code type} is a plain string (not an enum) because menu
 * kinds grow as the facade covers more blocks (inventory, crafting_table,
 * chest, furnace, anvil, …); a frozen enum would require a Stage 3 review
 * for every new kind. The {@code slots} list is defensive-copied and
 * immutable — the planner cannot mutate the snapshot.
 *
 * @param type          menu kind identifier (e.g. "inventory",
 *                      "crafting_table", "chest"); never null or blank
 * @param containerSize total number of slots in the menu
 * @param slots         flat slot list, index 0..containerSize-1;
 *                      never null, immutable after construction
 */
public record MenuView(String type, int containerSize, List<SlotView> slots) {

    /**
     * Creates a validated, immutable menu snapshot.
     *
     * @param type          menu kind; must not be null or blank
     * @param containerSize total slot count; must be positive
     * @param slots         slot list; must not be null and its size must
     *                      match containerSize
     */
    public MenuView {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "menu type must not be null or blank");
        }
        if (containerSize <= 0) {
            throw new IllegalArgumentException(
                "containerSize must be positive, got " + containerSize);
        }
        if (slots == null) {
            throw new IllegalArgumentException("slots must not be null");
        }
        if (slots.size() != containerSize) {
            throw new IllegalArgumentException(
                "slots size " + slots.size()
                    + " does not match containerSize " + containerSize);
        }
        // Defensive copy: the caller's list may be mutable; the snapshot
        // must be immutable so the planner cannot reach back into the
        // adapter's menu state.
        slots = List.copyOf(slots);
    }

    /**
     * Get a slot by its flat menu index.
     *
     * @param index slot index 0..containerSize-1
     * @return the slot view; never null
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public SlotView slot(int index) {
        return slots.get(index);
    }

    /**
     * Whether any slot in the menu holds the given item id.
     *
     * @param itemId the item id to search for; never null
     * @return true if at least one non-empty slot matches
     */
    public boolean contains(String itemId) {
        for (SlotView slot : slots) {
            if (!slot.isEmpty() && slot.item().itemId().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Count total items of the given id across all slots.
     *
     * @param itemId the item id to count; never null
     * @return total count (0 if not present)
     */
    public int countOf(String itemId) {
        int total = 0;
        for (SlotView slot : slots) {
            if (!slot.isEmpty() && slot.item().itemId().equals(itemId)) {
                total += slot.item().count();
            }
        }
        return total;
    }
}
