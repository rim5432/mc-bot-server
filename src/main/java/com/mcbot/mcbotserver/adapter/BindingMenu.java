package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuProgress;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

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
 * {@link #click(int, int, MenuClick)}; it never touches slot state
 * directly. {@code ResultSlot.onTake} consumes crafting-grid materials
 * on take — bypassing {@code clicked()} would duplicate items.
 *
 * <p>Lifecycle: open (constructor) -> snapshot/click at will ->
 * {@link #close()} once; close is terminal and idempotent. The
 * constructor attaches the binding to the facade as its open menu, so
 * {@code MenuOpener} can close the previous menu before opening a new
 * one (vanilla parity — {@code ServerPlayer.openMenu} closes first).
 * Every click re-runs the menu's own {@code stillValid} predicate
 * against the freshly synced facade position: vanilla enforces that
 * predicate every tick on the server, but the facade is never ticked,
 * so the gate lives at the only write surface instead.
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

    /** The world block this menu is bound to; null for the bot's own
     * inventory menu. */
    private final CellPos sourcePos;

    /** Reflective handle to {@link AbstractFurnaceMenu#data} — the
     * four-value ContainerData (litTime, litDuration, cookProgress,
     * cookTotal) is private with no public getter in 1.20.1, and the
     * scaled helpers (getLitProgress / getBurnProgress) lose the raw
     * tick values. The adapter layer is the only place allowed to reach
     * across the MC visibility boundary (boundaries.md §A: adapter/ may
     * import net.minecraft.*). Null when the field is absent in a future
     * mapping — progress disclosure degrades to null, never crashes. */
    private static final java.lang.reflect.Field FURNACE_DATA;

    static {
        java.lang.reflect.Field f = null;
        try {
            f = AbstractFurnaceMenu.class.getDeclaredField("data");
            f.setAccessible(true);
        } catch (NoSuchFieldException e) {
            f = null;
        }
        FURNACE_DATA = f;
    }

    /**
     * Terminal-state latch: set by {@link #close()}, never cleared.
     * Clicks and snapshots on a closed menu are programming errors —
     * the underlying container references still resolve, so without
     * the latch a stale binding would keep "working" silently against
     * a menu the world considers closed.
     */
    private boolean closed;

    /**
     * Creates a binding around an open menu. Installs a no-op
     * synchronizer immediately — the menu may try to send slot updates
     * during construction or the first click, and a null synchronizer
     * would NPE. When the player is a {@link BotPlayerFacade}, the
     * binding also registers itself as the facade's open menu, so a
     * later {@code MenuOpener.open} closes this one first (vanilla
     * parity).
     *
     * @param menu      the open vanilla menu; never null
     * @param player    the player context (the facade); never null
     * @param type      menu kind identifier for snapshots (e.g.
     *                  "inventory", "crafting_table", "chest"); never
     *                  null or blank
     * @param sourcePos the world block the menu is bound to; null for
     *                  the bot's own inventory menu
     */
    public BindingMenu(AbstractContainerMenu menu, Player player, String type, CellPos sourcePos) {
        this.menu = Objects.requireNonNull(menu, "menu");
        this.player = Objects.requireNonNull(player, "player");
        this.type = Objects.requireNonNull(type, "type");
        this.sourcePos = sourcePos;
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        menu.setSynchronizer(NO_OP_SYNCHRONIZER);
        if (player instanceof BotPlayerFacade facade) {
            facade.setOpenMenu(this);
        }
    }

    /**
     * Snapshot the menu's current state: every slot with its role, the
     * carried (cursor) stack, and the source block. The returned
     * {@link MenuView} is immutable — the planner reads it to decide
     * click sequences but cannot mutate the menu through it.
     *
     * @return fresh snapshot; never null
     * @throws IllegalStateException if the menu is closed
     */
    public MenuView snapshot() {
        ensureOpen();
        int size = menu.slots.size();
        List<SlotView> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new SlotView(i, BindingInventory.toView(menu.slots.get(i).getItem()), roleOf(i, size)));
        }
        return new MenuView(
                type, sourcePos, BindingInventory.toView(menu.getCarried()), size, slots, readFurnaceProgress());
    }

    /**
     * Read the four raw tick values from an open furnace-family menu's
     * private ContainerData. Returns null for every other menu kind, or
     * when the reflective handle is unavailable (future mapping drift).
     *
     * @return the furnace progress, or null when not applicable
     */
    private MenuProgress readFurnaceProgress() {
        if (!(menu instanceof AbstractFurnaceMenu) || FURNACE_DATA == null) {
            return null;
        }
        try {
            ContainerData data = (ContainerData) FURNACE_DATA.get(menu);
            return new MenuProgress(data.get(0), data.get(1), data.get(2), data.get(3));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Delegate a click to the vanilla menu. This is the only write
     * surface — all slot mutations (pickup, place, swap, quick-move,
     * craft-take) go through here so the menu's internal state machine
     * (including {@code ResultSlot.onTake} material consumption) stays
     * consistent. Takes the api's {@link MenuClick} vocabulary; the
     * engine click type never crosses into caller code.
     *
     * @param slot      the clicked slot index (menu's flat index, -1 for
     *                  "clicked outside the window" / drop carried)
     * @param button    the mouse button (0 = left, 1 = right); per-kind
     *                  semantics are documented on {@link MenuClick}
     * @param clickType the click kind; never null
     * @throws IllegalStateException if the menu is closed, or if the
     *         menu's stillValid predicate fails (bot out of the vanilla
     *         keep-open range, or the source block changed or broke)
     */
    public void click(int slot, int button, MenuClick clickType) {
        ensureOpen();
        ensureStillValid();
        menu.clicked(slot, button, toEngine(clickType), player);
    }

    /**
     * Click a non-slot button in the open menu (enchantment option,
     * beacon effect selection, stonecutter recipe list). Delegates to
     * {@code AbstractContainerMenu.clickMenuButton(player, id)} — menus
     * that override this method (EnchantmentMenu, StonecutterMenu)
     * consume the id; menus that don't return false and the click is a
     * no-op.
     *
     * @param id the button id as defined by the menu subclass (0-based)
     * @return true if the menu consumed the button click
     */
    public boolean clickButton(int id) {
        ensureOpen();
        ensureStillValid();
        return menu.clickMenuButton(player, id);
    }

    /**
     * Set the beacon's primary and secondary effects. BeaconMenu does not
     * override clickMenuButton — effects are applied directly via
     * {@code updateEffects}, which consumes one item from the payment slot
     * and sets the beacon's effect levels. Pass null for an effect to leave
     * it unchanged (empty Optional).
     *
     * @param primaryKey   the primary effect registry key (e.g.
     *                     "minecraft:speed"), or null for none
     * @param secondaryKey the secondary effect registry key (e.g.
     *                     "minecraft:regeneration"), or null for none
     * @throws IllegalStateException when the open menu is not a beacon menu
     */
    public void setBeaconEffects(String primaryKey, String secondaryKey) {
        ensureOpen();
        ensureStillValid();
        if (!(menu instanceof BeaconMenu beacon)) {
            throw new IllegalStateException("setBeaconEffects requires an open beacon menu");
        }
        Optional<MobEffect> primary = resolveEffect(primaryKey);
        Optional<MobEffect> secondary = resolveEffect(secondaryKey);
        beacon.updateEffects(primary, secondary);
    }

    /**
     * Set the anvil rename text for the item in the left input slot.
     * AnvilMenu.setItemName updates the rename field and recomputes the
     * result (a rename alone costs 1 level; combined with repair or
     * enchant transfer the cost stacks). Pass null or blank to clear the
     * rename (reverts to the item's default name).
     *
     * @param name the new item name, or null/blank to clear
     * @throws IllegalStateException when the open menu is not an anvil menu
     */
    public void setAnvilName(String name) {
        ensureOpen();
        ensureStillValid();
        if (!(menu instanceof AnvilMenu anvil)) {
            throw new IllegalStateException("setAnvilName requires an open anvil menu");
        }
        anvil.setItemName(name == null || name.isBlank() ? "" : name);
    }

    private static Optional<MobEffect> resolveEffect(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(ResourceLocation.parse(key));
        return Optional.ofNullable(effect);
    }

    /**
     * Engine-click translation for {@link #click}. The two excluded
     * engine kinds (CLONE, QUICK_CRAFT) have no api vocabulary — see
     * the {@link MenuClick} deviation note.
     */
    private static ClickType toEngine(MenuClick type) {
        return switch (type) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case THROW -> ClickType.THROW;
        };
    }

    /**
     * The role of one flat slot - delegated to the menu-kind table.
     * Callers address slots by role, never by per-type flat
     * arithmetic; each new kind extends the MenuSlotLayouts table in
     * the same change that adds it.
     */
    private SlotRole roleOf(int index, int size) {
        return MenuSlotLayouts.roleOf(menu, index, size);
    }

    /**
     * Close this menu and revert the facade to its inventory menu.
     * Terminal and idempotent: a second call is a no-op, so an
     * at-least-once harness retry cannot double-run
     * {@code container.stopOpen} (chest lid counter underflow).
     *
     * <p>Why this exists: vanilla closes menus through the ServerPlayer
     * network path, which triggers {@code containerMenu.removed(player)}.
     * BotPlayerFacade is not a ServerPlayer, so that path never runs.
     * Furthermore, both {@link AbstractContainerMenu#removed} and the
     * crafting-grid returns inside {@code CraftingMenu.removed} /
     * {@code InventoryMenu.removed} gate item return on
     * {@code player instanceof ServerPlayer}; for a non-ServerPlayer the
     * carried item and grid materials are silently lost. This method
     * performs the return manually before calling removed().
     *
     * <p>Order: (1) latch closed, (2) save carried, (3) return grid
     * materials manually, (4) call menu.removed() (clears carried,
     * stops chest open), (5) return carried to inventory, (6) detach
     * from the facade and revert to the inventory menu.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        ItemStack carried = menu.getCarried().copy();

        returnGridToInventory();

        // contract: see ADR-0004 (menu is request-response; close is the
        // terminal request). removed() clears carried and calls
        // container.stopOpen (chest lid). Its ServerPlayer-gated returns
        // are no-ops here because the returns ran manually above.
        menu.removed(player);

        if (!carried.isEmpty()) {
            // placeItemBackInInventory works for non-ServerPlayer (it
            // only skips the network packet, which we don't need with
            // the no-op synchronizer). A full inventory drops the
            // remainder at the body via the facade's drop override.
            player.getInventory().placeItemBackInInventory(carried);
        }

        if (player instanceof BotPlayerFacade facade) {
            facade.menuClosed(this);
            facade.closeContainer();
        }
    }

    /**
     * Extract all items from the crafting grid and place them in the
     * player inventory, before menu.removed() runs. Both grid menus
     * gate their vanilla return on ServerPlayer, so the bridge returns
     * them manually: CraftingMenu (3x3, menu slots 1-9) and the
     * facade's InventoryMenu (2x2, menu slots 1-4). Slot 0 is the
     * (virtual) result in both layouts and is discarded by removed(),
     * matching vanilla. removeItemNoUpdate avoids slotsChanged (no
     * mid-close recipe recompute).
     */
    private void returnGridToInventory() {
        int lastGridSlot = menu instanceof CraftingMenu ? 9 : menu instanceof InventoryMenu ? 4 : 0;
        for (int i = 1; i <= lastGridSlot; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack removed = slot.container.removeItemNoUpdate(slot.getContainerSlot());
            if (!removed.isEmpty()) {
                player.getInventory().placeItemBackInInventory(removed);
            }
        }
    }

    /**
     * Guard: operations on a closed menu are programming errors. The
     * menu's container references still resolve after close, so a stale
     * binding would keep "working" silently against a menu the world
     * considers closed — the latch turns that into a loud failure.
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("menu is closed; open a new menu instead");
        }
    }

    /**
     * Guard: the menu's own stillValid predicate (vanilla distance /
     * block-state check), evaluated against the freshly synced facade
     * position. In vanilla the server runs this predicate every tick
     * and closes the menu when it fails; the facade is never ticked,
     * so the gate runs here instead — per click, the only write
     * surface. The facade carries the default player BLOCK_REACH
     * attribute, so the gate distance is the vanilla 8.0 blocks for
     * container menus (open is separately gated by ReachPolicy in
     * MenuOpener; the asymmetry mirrors vanilla open-reach vs
     * keep-open tolerance).
     */
    private void ensureStillValid() {
        if (player instanceof BotPlayerFacade facade) {
            facade.syncPosition();
            if (!menu.stillValid(facade)) {
                throw new IllegalStateException(
                        "menu no longer valid (bot out of range or block " + "changed); close and reopen");
            }
        }
    }

    /**
     * The wrapped vanilla menu. Exposed for advanced operations that
     * need direct access (e.g. reading a specific slot's container
     * while setting up a test). Prefer {@link #snapshot()} and
     * {@link #click} for normal planning — this bypasses the open and
     * validity guards.
     *
     * @return the vanilla menu; never null
     */
    public AbstractContainerMenu rawMenu() {
        return menu;
    }

    /**
     * No-op container synchronizer: the bot drives menus server-side
     * with no client connection, so slot/carried/data updates are
     * silently discarded. A null synchronizer would NPE inside
     * {@code AbstractContainerMenu}'s broadcast methods.
     */
    private static final ContainerSynchronizer NO_OP_SYNCHRONIZER = new ContainerSynchronizer() {
        @Override
        public void sendInitialData(
                AbstractContainerMenu menu, NonNullList<ItemStack> slots, ItemStack carried, int[] dataValues) {
            // no-op: no client to broadcast to
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu menu, int slotId, ItemStack stack) {
            // no-op
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {
            // no-op
        }

        @Override
        public void sendDataChange(AbstractContainerMenu menu, int dataId, int value) {
            // no-op
        }
    };
}
