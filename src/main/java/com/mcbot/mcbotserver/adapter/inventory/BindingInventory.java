package com.mcbot.mcbotserver.adapter.inventory;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Adapter-side inventory binding: a {@link SimpleContainer} of 41 slots
 * (36 main + 4 armor + 1 offhand) that produces boundary-A-safe
 * {@link InventoryView} snapshots for the perception layer.
 *
 * <p>Contract: see boundaries.md section A (WorldView is read-only) and
 * issue 0007 Phase 1 (inventory sense). This class is the write side of
 * the bot's inventory (item pickup, drop, menu mutations land here); the
 * read side is {@link #snapshot()}, which is called from
 * {@link BindingWorldView#getInventory()} on the perception tick.
 *
 * <p>Slot layout (matches vanilla {@code Inventory.getContainerSize()}=41):
 * <ul>
 * <li>0-8: hotbar</li>
 * <li>9-35: backpack</li>
 * <li>36-39: armor (head, chest, legs, feet)</li>
 * <li>40: offhand</li>
 * </ul>
 *
 * <p>Why SimpleContainer and not vanilla Inventory: the vanilla
 * {@code Inventory} constructor demands a {@code Player} (issue 0007 §3),
 * which does not exist on a PathfinderMob carrier until Phase 2's
 * BotPlayerFacade. {@code SimpleContainer} needs no player and covers the
 * Phase 1 sense requirement; attribute modifiers now flow through the
 * body's equipment mirror (Phase 4 combat/wear slices), while
 * {@code getDestroySpeed} (enchantment-aware dig speed) stays deferred.
 *
 * <p>Implementation note: server tick thread only. The snapshot is
 * immutable once produced; off-thread consumers (Stage 2 planner) read it
 * without locks.
 */
// contract: see boundaries.md section A (WorldView is read-only) and
//            issue 0007 Phase 1 (inventory sense)
public final class BindingInventory {

    /** Total slots: 36 main + 4 armor + 1 offhand. */
    public static final int CONTAINER_SIZE = InventoryView.MAIN_SIZE + InventoryView.ARMOR_SIZE + 1;

    /** Slot index of the first armor slot (head). */
    public static final int ARMOR_START = InventoryView.MAIN_SIZE;

    /** Slot index of the offhand. */
    public static final int OFFHAND_SLOT = CONTAINER_SIZE - 1;

    private final SimpleContainer container;
    private int selectedSlot;

    /**
     * Creates an empty inventory with selected slot 0.
     */
    public BindingInventory() {
        this.container = new SimpleContainer(CONTAINER_SIZE);
        this.selectedSlot = 0;
    }

    /**
     * The underlying container. Exposed so item-pickup wiring and menu
     * mutations can write directly; callers must not hold a reference
     * across ticks for reading — use {@link #snapshot()} instead.
     *
     * @return the live container; never null
     */
    public SimpleContainer container() {
        return container;
    }

    /**
     * Sets the selected hotbar slot. Mirrors the carrier's
     * {@code selectedSlot} field; the binding is the source of truth for
     * inventory reads.
     *
     * @param slot hotbar index 0-8
     * @throws IllegalArgumentException if slot is out of range
     */
    public void setSelectedSlot(int slot) {
        if (slot < 0 || slot >= InventoryView.HOTBAR_SIZE) {
            throw new IllegalArgumentException(
                    "selectedSlot must be 0.." + (InventoryView.HOTBAR_SIZE - 1) + ", was " + slot);
        }
        this.selectedSlot = slot;
    }

    /**
     * Produces an immutable snapshot of the current inventory state. Call
     * once per perception tick; the returned view is safe to read off the
     * server thread.
     *
     * @return fresh inventory snapshot; never null
     */
    public InventoryView snapshot() {
        List<ItemView> mainSlots = new ArrayList<>(InventoryView.MAIN_SIZE);
        for (int i = 0; i < InventoryView.MAIN_SIZE; i++) {
            mainSlots.add(toView(container.getItem(i)));
        }
        List<ItemView> armorSlots = new ArrayList<>(InventoryView.ARMOR_SIZE);
        for (int i = 0; i < InventoryView.ARMOR_SIZE; i++) {
            armorSlots.add(toView(container.getItem(ARMOR_START + i)));
        }
        ItemView offhand = toView(container.getItem(OFFHAND_SLOT));
        return new InventoryView(mainSlots, selectedSlot, armorSlots, offhand);
    }

    /**
     * Converts one engine ItemStack into the api's read-only shape. Empty
     * stacks normalize to {@link ItemView#EMPTY}; the registry key is the
     * canonical id style ({@code minecraft:diamond}).
     *
     * @param stack the engine stack; never null
     * @return read-only view; never null
     */
    /**
     * Converts one engine ItemStack into the api's read-only shape.
     * Shared with {@link BindingMenu} so inventory and menu snapshots
     * stay consistent by construction.
     *
     * @param stack the engine stack; never null
     * @return read-only view; never null
     */
    public static ItemView toView(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            return ItemView.EMPTY;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String id = key != null ? key.toString() : "unknown";
        String digest = stack.hasTag() && stack.getTag() != null
                ? Integer.toHexString(stack.getTag().hashCode())
                : "";
        return new ItemView(id, stack.getCount(), digest);
    }
}
