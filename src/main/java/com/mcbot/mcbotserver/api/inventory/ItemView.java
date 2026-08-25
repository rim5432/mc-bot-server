package com.mcbot.mcbotserver.api.inventory;

/**
 * Read-only identity of one inventory slot. Pure data — carrying it out
 * of a {@link com.mcbot.mcbotserver.api.world.WorldView} must never
 * have side effects.
 *
 * <p>Contract: see boundaries.md section A (WorldView is read-only) and
 * issue 0007 Phase 1 (inventory sense). Item ids use the registry key
 * style ({@code minecraft:diamond}) so data-driven planners and rule
 * tables can reference items without engine types. The NBT digest is a
 * lossy summary — enough to distinguish a sharpness-III sword from a
 * plain one, not enough to reconstruct the stack. Full NBT stays in the
 * engine; the api layer never carries CompoundTag.
 *
 * @param itemId    registry key of the item; never null; empty slots use
 *                  {@link #EMPTY} rather than a null id
 * @param count     stack size; zero for an empty slot, positive otherwise
 * @param nbtDigest lossy summary of the stack's NBT, or {@code ""} when
 *                  the stack has no NBT; never null
 */
public record ItemView(String itemId, int count, String nbtDigest) {

    /** The canonical empty slot — no item, zero count, no NBT. */
    public static final ItemView EMPTY = new ItemView("", 0, "");

    /**
     * Creates a validated item view.
     *
     * @param itemId    must not be null
     * @param count     must not be negative
     * @param nbtDigest must not be null
     */
    public ItemView {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId must not be null");
        }
        if (count < 0) {
            throw new IllegalArgumentException(
                "count must not be negative: " + count);
        }
        if (nbtDigest == null) {
            throw new IllegalArgumentException("nbtDigest must not be null");
        }
    }

    /**
     * Convenience constructor for NBT-free stacks.
     *
     * @param itemId registry key; never null
     * @param count  stack size; non-negative
     */
    public ItemView(String itemId, int count) {
        this(itemId, count, "");
    }

    /**
     * Whether this slot holds nothing.
     *
     * @return true when count is zero (the itemId is then "" per
     *         {@link #EMPTY})
     */
    public boolean isEmpty() {
        return count == 0;
    }
}
