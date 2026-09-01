package com.mcbot.mcbotserver.adapter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Opens vanilla menus against the bot's {@link BotPlayerFacade} and
 * wraps them in {@link BindingMenu}. The opener identifies the target
 * block, creates the appropriate {@code AbstractContainerMenu}, sets it
 * as the facade's active container, and returns a binding that the core
 * planner can snapshot and click.
 *
 * <p>Contract: see issue 0007 Phase 2 (menu system) and §6.2 (menu
 * transactions are request-response, not per-tick claims). The opener is
 * the "open" half of the transaction; {@link BindingMenu#click} is the
 * "click at will" half; closing is {@code facade.closeContainer()} (which
 * reverts to the inventory menu).
 *
 * <p>Supported block kinds (Phase 2 baseline):
 * <ul>
 *   <li>{@code crafting_table} — {@link BotCraftingMenu} (3x3 grid,
 *       bypasses the vanilla ServerPlayer cast in slotsChanged).</li>
 *   <li>{@code chest} (single, double, trapped) —
 *       {@link ChestMenu#threeRows} backed by the vanilla
 *       {@code ChestBlock.getContainer} merge: a double chest opens
 *       as the full 54-slot CompoundContainer, never one half; a
 *       blocked chest (solid block or cat above a half) is rejected
 *       exactly like vanilla.</li>
 * </ul>
 *
 * <p>Distance policy: the open gate is
 * {@link ReachPolicy#BLOCK_REACH_BLOCKS} (the project interaction
 * reach); once open, the menu's vanilla stillValid predicate (8
 * blocks for container menus) is re-checked per click by
 * {@link BindingMenu}. The asymmetry is intentional — it mirrors
 * vanilla's open-reach vs keep-open tolerance.
 *
 * <p>Thread safety: server tick thread only. The opener mutates the
 * facade's {@code containerMenu} field and reads block entities — both
 * require the server thread.
 */
// contract: see issue 0007 §5 Phase 2 (menu opener per block kind)
public final class MenuOpener {

    /** Monotonic container id counter. The id is unused server-side
     * (no network packets with the no-op synchronizer) but required by
     * every AbstractContainerMenu constructor. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    /** The facade whose inventory and player context back every menu. */
    private final BotPlayerFacade facade;

    /**
     * Creates an opener bound to one facade.
     *
     * @param facade the player facade; never null
     */
    public MenuOpener(BotPlayerFacade facade) {
        this.facade = facade;
    }

    /**
     * Open a menu for the block at the given position. Returns empty when
     * the block is not a supported menu kind or its container is missing
     * or blocked (solid block or cat above a chest half). Double chests
     * open as the full vanilla CompoundContainer merge. The facade's
     * position is synced before the menu is created so any reach or
     * sound checks inside the menu see the body's live position. Any
     * menu the facade currently has open is closed first (grid
     * materials returned, chest lid lowered) — vanilla parity:
     * ServerPlayer.openMenu closes the previous container before
     * opening the new one.
     *
     * @param pos the target block position (world-absolute); never null
     * @return the opened menu binding, or empty if unsupported
     */
    public Optional<BindingMenu> open(BlockPos pos) {
        closeCurrentMenu();
        Level level = facade.body().level();

        // Reach gate: measured from the body's eye position to the block
        // center. Without this the bot could open a menu on any loaded
        // chunk regardless of distance.
        facade.syncPosition();
        if (!ReachPolicy.withinReach(facade.getEyePosition(), pos)) {
            return Optional.empty();
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // SmithingTableBlock extends CraftingTableBlock, so it must be
        // checked first — otherwise the instanceof below routes it to
        // openCraftingTable and the snapshot type comes out "crafting_table"
        // instead of "smithing_table". The smithing table carries its own
        // MenuProvider that returns a proper SmithingMenu.
        if (block instanceof SmithingTableBlock) {
            MenuProvider smithingProvider = state.getMenuProvider(level, pos);
            if (smithingProvider != null) {
                facade.syncPosition();
                var smithingMenu = smithingProvider.createMenu(NEXT_ID.getAndIncrement(), facade.getInventory(), facade);
                if (smithingMenu != null) {
                    facade.containerMenu = smithingMenu;
                    return Optional.of(new BindingMenu(smithingMenu, facade, "smithing_table", toCellPos(pos)));
                }
            }
            return Optional.empty();
        }
        if (block instanceof CraftingTableBlock) {
            return Optional.of(openCraftingTable(pos));
        }
        if (block instanceof ChestBlock chestBlock) {
            return openChest(chestBlock, state, pos);
        }
        // Generic fallback (issue 0012 D0): every standard
        // MenuProvider block - furnace, blast furnace, smoker, barrel,
        // dispenser, ... - opens through the block's own menu provider
        // with zero per-kind code. The snapshot type is the block's
        // registry key tail ("furnace", "blast_furnace", ...), which
        // is also the harness's station-path vocabulary. Blocks with
        // no UI return a null provider and stay unsupported.
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider != null) {
            facade.syncPosition();
            var menu = provider.createMenu(NEXT_ID.getAndIncrement(), facade.getInventory(), facade);
            if (menu != null) {
                facade.containerMenu = menu;
                String type = String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
                return Optional.of(
                        new BindingMenu(menu, facade, type.substring(type.indexOf(':') + 1), toCellPos(pos)));
            }
        }
        return Optional.empty();
    }

    /**
     * Open the player's own inventory menu (the 2x2 crafting grid +
     * full inventory). This does not target a world block — it is the
     * default menu the facade falls back to after any other menu closes.
     *
     * @return the inventory menu binding; never null
     */
    public BindingMenu openInventory() {
        closeCurrentMenu();
        facade.syncPosition();
        var menu = facade.facadeInventoryMenu();
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "inventory", null);
    }

    /**
     * Open a menu backed by a world entity. Currently supports
     * {@link Merchant} entities (villager trading) and
     * {@link AbstractHorse} entities (horse inventory — must be tamed).
     * The facade's position is synced before the menu is created so any
     * reach or state checks inside the menu see the body's live position.
     * Any menu the facade currently has open is closed first (vanilla
     * parity).
     *
     * @param entityId the vanilla runtime entity id (as returned by
     *                 /entities scan)
     * @return the opened menu binding, or empty if the entity is not
     *         found, out of reach, untamed, or an unsupported kind
     */
    public Optional<BindingMenu> openEntity(int entityId) {
        closeCurrentMenu();
        Level level = facade.body().level();
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return Optional.empty();
        }

        facade.syncPosition();
        if (!ReachPolicy.withinReach(facade.getEyePosition(), entity.blockPosition())) {
            return Optional.empty();
        }

        if (entity instanceof Merchant merchant) {
            merchant.setTradingPlayer(facade);
            var menu = new MerchantMenu(NEXT_ID.getAndIncrement(), facade.getInventory(), merchant);
            facade.containerMenu = menu;
            return Optional.of(new BindingMenu(menu, facade, "merchant", toCellPos(entity.blockPosition())));
        }

        if (entity instanceof AbstractHorse horse) {
            // openCustomInventoryScreen gates on tamed + not vehicle (or
            // bot is the passenger); it calls facade.openHorseInventory
            // which creates the HorseInventoryMenu and sets containerMenu.
            horse.openCustomInventoryScreen(facade);
            var menu = facade.containerMenu;
            if (menu instanceof HorseInventoryMenu) {
                return Optional.of(new BindingMenu(menu, facade, "horse", toCellPos(entity.blockPosition())));
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * Close the facade's currently open menu before a new one opens.
     * Without this, opening over an existing menu would orphan the old
     * binding's crafting-grid materials and never run its
     * {@code removed()} (chest lid counter leak). Idempotent by
     * delegation — close() itself is terminal and repeat-safe.
     */
    private void closeCurrentMenu() {
        BindingMenu current = facade.openMenu();
        if (current != null) {
            current.close();
        }
    }

    /**
     * The facade's currently open menu binding, if any. The actor's
     * menu transaction methods read this to answer menuSnapshot and
     * to route clicks.
     *
     * @return the open binding, or null when no menu is open
     */
    BindingMenu currentMenu() {
        return facade.openMenu();
    }

    // ---- Internal openers ----

    private BindingMenu openCraftingTable(BlockPos pos) {
        facade.syncPosition();
        ContainerLevelAccess access = ContainerLevelAccess.create(facade.body().level(), pos);
        Inventory inv = facade.getInventory();
        BotCraftingMenu menu = new BotCraftingMenu(NEXT_ID.getAndIncrement(), inv, access);
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "crafting_table", toCellPos(pos));
    }

    private Optional<BindingMenu> openChest(ChestBlock block, BlockState state, BlockPos pos) {
        Level level = facade.body().level();
        // Vanilla's own merge (the same helper ChestBlock.use routes
        // through): CompoundContainer for a double pair, the single
        // block entity otherwise, null when blocked (solid block or
        // cat above a half). validate=false keeps the blocked check
        // ACTIVE, matching the vanilla open path.
        Container chest = ChestBlock.getContainer(block, state, level, pos, false);
        if (chest == null) {
            return Optional.empty();
        }
        facade.syncPosition();
        Inventory inv = facade.getInventory();
        // Menu width follows the container (vanilla parity: a double
        // chest opens the six-row GUI). Only 27/54 exist today; other
        // widths mean a different menu kind entirely.
        ChestMenu menu = chest.getContainerSize() == 54
                ? ChestMenu.sixRows(NEXT_ID.getAndIncrement(), inv, chest)
                : ChestMenu.threeRows(NEXT_ID.getAndIncrement(), inv, chest);
        facade.containerMenu = menu;
        return Optional.of(new BindingMenu(menu, facade, "chest", toCellPos(pos)));
    }

    /**
     * Convert an engine block position to the api position type for
     * snapshots (the api never sees engine types).
     */
    private static com.mcbot.mcbotserver.api.types.CellPos toCellPos(BlockPos pos) {
        return new com.mcbot.mcbotserver.api.types.CellPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
