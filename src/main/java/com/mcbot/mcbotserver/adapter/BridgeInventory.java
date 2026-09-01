package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory bridge that delegates slot storage to the body's
 * {@link BindingInventory} (a 41-slot {@code SimpleContainer}: 36 main
 * + 4 armor + 1 offhand). Extends {@link Inventory} so vanilla menu
 * constructors and the {@code Slot} call graph accept it, but the
 * Inventory's own internal lists ({@code items}, {@code armor},
 * {@code offhand}) are never used — they are phantom compartments
 * created by the super constructor and stay empty forever. Every slot
 * accessor below delegates to the binding container; the phantom lists
 * are only reachable if someone bypasses these overrides (no 1.20.1
 * menu code does).
 *
 * <p>Phantom compartments: the Inventory's own internal lists
 * ({@code items}, {@code armor}, {@code offhand}) are created empty by
 * the super constructor and never used for storage. Every accessor
 * that vanilla slot-search or item-return logic can reach through the
 * facade is overridden below to delegate to the binding container:
 * the slot accessors, the remove family, the search family
 * ({@link #getFreeSlot}, {@link #getSlotWithRemainingSpace}), and
 * {@link #add(int, ItemStack)}. Leaving any of them inherited lets
 * vanilla read the phantom lists: the search family returning 0 /
 * -1 unconditionally made {@code placeItemBackInInventory} merge
 * returned items into an occupied real slot without an item-identity
 * check (corruption) and made its "inventory full -> drop" branch
 * unreachable (infinite loop on the server tick thread).
 *
 * <p>Contract: see issue 0007 §4 Path A and §7 (armor order ruling).
 * Flat index mapping: vanilla Inventory compartments are
 * {@code [items(36), armor(4), offhand(1)]}. Main slots 0..35 and the
 * offhand 40 map 1:1 to binding slots, but the armor compartment is
 * REVERSED: vanilla flat 36..39 runs feet..head (the armor list index
 * is {@code EquipmentSlot.getIndex()}, FEET=0; InventoryMenu addresses
 * container slots 39..36 for head..feet) while BindingInventory and
 * InventoryView run head..feet. Every flat-index accessor below
 * translates armor slots at this seam, so menu armor slots read and
 * write the correct equipment piece without either api surface
 * changing its own order.
 *
 * <p>Selected slot: {@code Inventory.selected} is a public field that
 * defaults to 0 and is never written by the bridge. Instead,
 * {@link #getSelected()} reads the live {@code body.selectedSlot} — the
 * SLOT channel claim is the source of truth, and any code that calls
 * {@code getSelected()} (e.g. {@code Player.getMainHandItem()}) gets
 * the current hotbar selection. Code that reads {@code inventory.selected}
 * directly would see 0; no menu code in the 1.20.1 call graph does this
 * (menus use slot indices, not the selected field).
 *
 * <p>Thread safety: same as BindingInventory — server tick thread only.
 * The bridge holds no mutable state; it is a pure delegation layer.
 */
// contract: see issue 0007 §4 Path A (inventory bridge on SimpleContainer)
public final class BridgeInventory extends Inventory {

    /** Main-slot count (hotbar + backpack), matching vanilla's
     * {@code Inventory.items} compartment that the search family
     * scans. Armor and offhand are excluded from auto-fill, same as
     * vanilla: equipment slots only change through explicit slot
     * operations. */
    private static final int MAIN_SLOTS = 36;

    /** Flat index of the offhand slot in the binding container. */
    private static final int OFFHAND_SLOT = 40;

    /** First vanilla armor flat index (feet in vanilla order). */
    private static final int ARMOR_FLAT_FIRST = 36;

    /** Last vanilla armor flat index (head in vanilla order). */
    private static final int ARMOR_FLAT_LAST = 39;

    private final BotBodyEntity body;

    /**
     * Creates a bridge backed by the body's BindingInventory.
     *
     * @param player the owning facade (passed to Inventory super); never
     *               null
     * @param body   the physical carrier whose BindingInventory is the
     *               real storage; never null
     */
    public BridgeInventory(BotPlayerFacade player, BotBodyEntity body) {
        super(player);
        this.body = body;
    }

    /**
     * Read a slot from the binding container. Main slots 0..35 and the
     * offhand 40 map 1:1; armor flat 36..39 is reversed at this seam
     * (vanilla feet..head vs binding head..feet — see the class
     * Javadoc).
     *
     * @param slot flat slot index 0..40
     * @return the item in that slot; never null (empty stack for empty)
     */
    @Override
    public ItemStack getItem(int slot) {
        return body.getInventory().container().getItem(toBindingSlot(slot));
    }

    /**
     * Write a slot to the binding container (armor flat indices
     * reversed — see {@link #getItem}).
     *
     * @param slot  flat slot index 0..40
     * @param stack the item to set; never null
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        body.getInventory().container().setItem(toBindingSlot(slot), stack);
    }

    /**
     * Total slot count: 36 main + 4 armor + 1 offhand = 41.
     *
     * @return 41
     */
    @Override
    public int getContainerSize() {
        return 41;
    }

    /**
     * The currently selected hotbar item, read live from
     * {@code body.selectedSlot}. The Inventory's own {@code selected}
     * field is stale (defaults to 0) and is never the source of truth.
     *
     * @return the item in the selected hotbar slot; empty if the
     *         selection is out of range (should not happen)
     */
    @Override
    public ItemStack getSelected() {
        int slot = body.selectedSlot;
        if (slot < 0 || slot > 8) {
            return ItemStack.EMPTY;
        }
        return body.getInventory().container().getItem(slot);
    }

    /**
     * Clear all slots in the binding container.
     */
    @Override
    public void clearContent() {
        body.getInventory().container().clearContent();
    }

    /**
     * Remove and return up to {@code amount} items from the binding
     * slot. Overridden because the inherited {@code Inventory.removeItem}
     * operates on the phantom {@code items} list and would return EMPTY
     * for every slot — the vanilla click extraction path
     * ({@code Slot.tryRemove} -> {@code Slot.remove} ->
     * {@code container.removeItem}) would silently pick up nothing.
     *
     * @param slot   flat slot index 0..40
     * @param amount maximum items to remove
     * @return the removed stack; never null (EMPTY if the slot was empty
     *         or amount was zero)
     */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        return body.getInventory().container().removeItem(toBindingSlot(slot), amount);
    }

    /**
     * Remove and return the entire stack from the binding slot, without
     * triggering a {@code setChanged} notification. Overridden for the
     * same phantom-list reason as {@link #removeItem}.
     *
     * @param slot flat slot index 0..40
     * @return the removed stack; never null (EMPTY if the slot was empty)
     */
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return body.getInventory().container().removeItemNoUpdate(toBindingSlot(slot));
    }

    /**
     * Map a vanilla Inventory flat index to the binding slot. Main
     * (0..35) and offhand (40) map 1:1; armor 36..39 is reversed
     * because vanilla runs feet..head while the binding runs
     * head..feet (see the class Javadoc).
     *
     * @param slot vanilla flat index 0..40
     * @return the binding container slot index
     */
    private static int toBindingSlot(int slot) {
        if (slot >= ARMOR_FLAT_FIRST && slot <= ARMOR_FLAT_LAST) {
            return ARMOR_FLAT_FIRST + ARMOR_FLAT_LAST - slot;
        }
        return slot;
    }

    /**
     * First free main slot (0..35) in the binding container. Overridden
     * because the inherited scan reads the phantom {@code items} list,
     * which is always empty, and would return 0 unconditionally — even
     * with every real main slot occupied, so the caller's "full -> drop"
     * fallback never fires.
     *
     * @return the first empty main slot, or -1 when 0..35 are occupied
     */
    @Override
    public int getFreeSlot() {
        SimpleContainer container = body.getInventory().container();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (container.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * First slot with room to merge the stack: the live selected slot,
     * the offhand, then main slots 0..35 in order — vanilla's search
     * order. Overridden because the inherited main loop reads the
     * phantom {@code items} list and never finds real merge space;
     * {@code placeItemBackInInventory} would then fall through to
     * {@link #getFreeSlot} and merge into an occupied slot without an
     * item-identity check (the identity check lives in this search, not
     * in the merge itself).
     *
     * @param stack the stack to find merge space for; never null
     * @return a slot index, or -1 when no main/offhand slot can absorb
     */
    @Override
    public int getSlotWithRemainingSpace(ItemStack stack) {
        SimpleContainer container = body.getInventory().container();
        int selected = body.selectedSlot;
        if (hasRemainingSpace(container.getItem(selected), stack, container)) {
            return selected;
        }
        if (hasRemainingSpace(container.getItem(OFFHAND_SLOT), stack, container)) {
            return OFFHAND_SLOT;
        }
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (hasRemainingSpace(container.getItem(i), stack, container)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Vanilla {@code Inventory.hasRemainingSpaceForItem} semantics:
     * same item and tags, stackable, and below both the per-stack and
     * the container maxima.
     *
     * @param slot      the candidate slot stack; never null
     * @param stack     the stack seeking merge space; never null
     * @param container the binding container supplying the container
     *                  max stack size
     * @return true when the candidate slot can absorb from the stack
     */
    private static boolean hasRemainingSpace(ItemStack slot, ItemStack stack, SimpleContainer container) {
        return !slot.isEmpty()
                && ItemStack.isSameItemSameTags(slot, stack)
                && slot.isStackable()
                && slot.getCount() < slot.getMaxStackSize()
                && slot.getCount() < container.getMaxStackSize();
    }

    /**
     * Add a stack, merging into a specific slot (or the first
     * mergeable slot when {@code slot} is -1). The inherited undamaged
     * path is safe once the search family is overridden — it merges
     * through the overridden {@link #getItem} / {@link #setItem}. The
     * damaged path is overridden because vanilla writes damaged tools
     * straight into the phantom {@code items} list; popTime (the client
     * pickup animation) is skipped, the device has no client.
     *
     * @param slot  target slot, or -1 for "find space"
     * @param stack the stack to add; never null
     * @return true when at least part of the stack was placed
     */
    @Override
    public boolean add(int slot, ItemStack stack) {
        if (!stack.isDamaged()) {
            return super.add(slot, stack);
        }
        if (slot == -1) {
            slot = getFreeSlot();
        }
        if (slot < 0) {
            return false;
        }
        setItem(slot, stack.copyAndClear());
        return true;
    }

    /**
     * Whether every binding slot is empty. Overridden because the
     * inherited {@code Inventory.isEmpty} checks the phantom compartments
     * (always empty) and would return true even when the bot carries
     * items.
     *
     * @return true when no slot holds an item
     */
    @Override
    public boolean isEmpty() {
        return body.getInventory().container().isEmpty();
    }

    /**
     * Whether the player may still interact with this inventory. A
     * player's own inventory is always valid (no distance check), so this
     * returns true unconditionally — same as the vanilla
     * {@code Inventory.stillValid} default.
     *
     * @param player the interacting player; unused
     * @return true always
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
