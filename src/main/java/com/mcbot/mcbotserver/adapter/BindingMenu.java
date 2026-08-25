package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotView;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter binding for an open {@link AbstractContainerMenu}: produces
 * read-only {@link MenuView} snapshots and delegates click operations to
 * the vanilla menu's {@code clicked()} method. The menu is driven
 * directly — no network connection, no client screen — with a no-op
 * {@link ContainerSynchronizer} so slot updates do not try to send
 * packets (issue 0007 Phase 2, Path A: "menus are driven directly
 * (menu.clicked(...) with a no-op synchronizer)").
 *
 * <p>Contract: see boundaries.md section A (all world mutation behind
 * the actor/menu surface) and issue 0007 risk 2 ("core never writes
 * menu state"). The core planner reads {@link #snapshot()} and calls
 * {@link #click(int, int, ClickType)}; it never touches slot state
 * directly. {@code ResultSlot.onTake} consumes crafting-grid materials
 * on take — bypassing {@code clicked()} would duplicate items.
 *
 * <p>Thread safety: server tick thread only, same as the body and the
 * facade. The menu holds mutable state (slots, carried item, data
 * values); concurrent access would corrupt it.
 */
// contract: see issue 0007 §5 Phase 2 (menu system driven directly)
public final class BindingMenu {

    /** The wrapped vanilla menu. */
    private final AbstractContainerMenu menu;

    /** The player context for click operations. */
    private final Player player;

    /** Menu kind identifier for the snapshot. */
    private final String type;

    /**
     * Creates a binding around an open menu. Installs a no-op
     * synchronizer immediately — the menu may try to send slot updates
     * during construction or the first click, and a null synchronizer
     * would NPE.
     *
     * @param menu   the open vanilla menu; never null
     * @param player the player context (the facade); never null
     * @param type   menu kind identifier for snapshots (e.g. "inventory",
     *               "crafting_table", "chest"); never null or blank
     */
    public BindingMenu(AbstractContainerMenu menu, Player player,
                       String type) {
        this.menu = Objects.requireNonNull(menu, "menu");
        this.player = Objects.requireNonNull(player, "player");
        this.type = Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        menu.setSynchronizer(NO_OP_SYNCHRONIZER);
    }

    /**
     * Snapshot the menu's current state: every slot plus the carried
     * (cursor) item. The returned {@link MenuView} is immutable — the
     * planner reads it to decide click sequences but cannot mutate the
     * menu through it.
     *
     * @return fresh snapshot; never null
     */
    public MenuView snapshot() {
        int size = menu.slots.size();
        List<SlotView> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new SlotView(i, toView(menu.slots.get(i).getItem())));
        }
        return new MenuView(type, size, slots);
    }

    /**
     * The item currently carried on the cursor (the "ghost" stack
     * between clicks). Relevant for click-sequence planning: a planner
     * must know what it is holding before it decides where to click.
     *
     * @return the carried item view; {@link ItemView#EMPTY} if nothing
     *         is carried
     */
    public ItemView getCarried() {
        return toView(menu.getCarried());
    }

    /**
     * Delegate a click to the vanilla menu. This is the only write
     * surface — all slot mutations (pickup, place, swap, quick-move,
     * craft-take) go through here so the menu's internal state machine
     * (including {@code ResultSlot.onTake} material consumption) stays
     * consistent.
     *
     * @param slot     the clicked slot index (menu's flat index, -1 for
     *                 "clicked outside the window" / drop carried)
     * @param button   the mouse button (0 = left, 1 = right; for
     *                 {@link ClickType#SWAP} this is the hotbar index 0..8)
     * @param clickType the click type; never null
     */
    public void click(int slot, int button, ClickType clickType) {
        menu.clicked(slot, button, clickType, player);
    }

    /**
     * The wrapped vanilla menu. Exposed for advanced operations that
     * need direct access (e.g. checking a specific slot's mayPlace
     * predicate). Prefer {@link #snapshot()} and {@link #click} for
     * normal planning.
     *
     * @return the vanilla menu; never null
     */
    public AbstractContainerMenu rawMenu() {
        return menu;
    }

    /**
     * Converts one engine ItemStack into the api's read-only shape.
     * Mirrors {@link BindingInventory}'s conversion (same registry key
     * + NBT digest logic) so inventory and menu snapshots are consistent.
     *
     * @param stack the engine stack; never null
     * @return read-only view; never null
     */
    private static ItemView toView(ItemStack stack) {
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

    /**
     * No-op container synchronizer: the bot drives menus server-side
     * with no client connection, so slot/carried/data updates are
     * silently discarded. A null synchronizer would NPE inside
     * {@code AbstractContainerMenu}'s broadcast methods.
     */
    private static final ContainerSynchronizer NO_OP_SYNCHRONIZER =
        new ContainerSynchronizer() {
            @Override
            public void sendInitialData(AbstractContainerMenu menu,
                                         NonNullList<ItemStack> slots,
                                         ItemStack carried,
                                         int[] dataValues) {
                // no-op: no client to broadcast to
            }

            @Override
            public void sendSlotChange(AbstractContainerMenu menu,
                                        int slotId, ItemStack stack) {
                // no-op
            }

            @Override
            public void sendCarriedChange(AbstractContainerMenu menu,
                                           ItemStack stack) {
                // no-op
            }

            @Override
            public void sendDataChange(AbstractContainerMenu menu,
                                        int dataId, int value) {
                // no-op
            }
        };
}
