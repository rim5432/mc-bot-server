package com.mcbot.mcbotserver.api.menu;

import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.List;

/**
 * Read-only snapshot of an open menu. Pure data — the adapter produces
 * this from an {@code AbstractContainerMenu}'s current state, and the
 * core planner reads it to decide click sequences. The core holds no
 * menu state (issue 0007 risk 2): every mutation goes through the
 * adapter's menu click surface, so {@code ResultSlot.onTake}
 * consumes grid materials correctly.
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 2 (disclosure shape: carried is model-relevant and exposed;
 * sourcePos correlates a snapshot with the block that opened it — two
 * chest menus are otherwise indistinguishable). The {@code type} is a
 * plain string (not an enum) because menu
 * kinds grow as the facade covers more blocks (inventory, crafting_table,
 * chest, furnace, anvil, …); a frozen enum would turn every new menu
 * kind into a wire-vocabulary change that reopens the
 * harness-interaction audit. The {@code slots} list is defensive-copied and
 * immutable — the planner cannot mutate the snapshot.
 *
 * @param type          menu kind identifier (e.g. "inventory",
 *                      "crafting_table", "chest"); never null or blank
 * @param sourcePos     the world block this menu is bound to; null
 *                      when the menu is the bot's own inventory
 * @param carried       the ghost stack on the cursor between clicks;
 *                      {@link ItemView#EMPTY} when nothing is carried
 * @param containerSize total number of slots in the menu
 * @param slots         flat slot list, index 0..containerSize-1;
 *                      never null, immutable after construction
 * @param progress      furnace-family burn/cook progress in raw ticks;
 *                      null for every other menu kind (the serializer
 *                      omits the key when null)
 */
public record MenuView(
        String type,
        CellPos sourcePos,
        ItemView carried,
        int containerSize,
        List<SlotView> slots,
        MenuProgress progress) {

    /**
     * Creates a validated, immutable menu snapshot.
     *
     * @param type          menu kind; must not be null or blank
     * @param sourcePos     source block position; null for the bot's
     *                      own inventory menu
     * @param carried       the carried (cursor) stack; must not be
     *                      null ({@link ItemView#EMPTY} when nothing
     *                      is carried)
     * @param containerSize total slot count; must be positive
     * @param slots         slot list; must not be null and its size must
     *                      match containerSize
     */
    public MenuView {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("menu type must not be null or blank");
        }
        if (carried == null) {
            throw new IllegalArgumentException("carried must not be null");
        }
        if (containerSize <= 0) {
            throw new IllegalArgumentException("containerSize must be positive, got " + containerSize);
        }
        if (slots == null) {
            throw new IllegalArgumentException("slots must not be null");
        }
        if (slots.size() != containerSize) {
            throw new IllegalArgumentException(
                    "slots size " + slots.size() + " does not match containerSize " + containerSize);
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
}
