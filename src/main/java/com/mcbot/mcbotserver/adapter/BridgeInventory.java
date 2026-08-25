package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory bridge that delegates all slot reads/writes to the body's
 * {@link BindingInventory} (a 41-slot {@code SimpleContainer}: 36 main
 * + 4 armor + 1 offhand). Extends {@link Inventory} so vanilla menu
 * constructors and the {@code Slot} call graph accept it, but the
 * Inventory's own internal lists ({@code items}, {@code armor},
 * {@code offhand}) are never used — every accessor overrides to the
 * binding container.
 *
 * <p>Contract: see issue 0007 §4 Path A and §7 risk. The slot layout
 * matches exactly: Inventory's compartment iteration is
 * {@code [items(36), armor(4), offhand(1)]} = flat indices 0..40,
 * which is identical to BindingInventory's SimpleContainer layout. So
 * a flat index {@code i} maps 1:1 to the binding slot — no translation
 * needed.
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
     * Read a slot from the binding container. Flat index 0..40 maps
     * 1:1 to BindingInventory slots (36 main + 4 armor + 1 offhand).
     *
     * @param slot flat slot index 0..40
     * @return the item in that slot; never null (empty stack for empty)
     */
    @Override
    public ItemStack getItem(int slot) {
        return body.getInventory().container().getItem(slot);
    }

    /**
     * Write a slot to the binding container.
     *
     * @param slot  flat slot index 0..40
     * @param stack the item to set; never null
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        body.getInventory().container().setItem(slot, stack);
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
}
