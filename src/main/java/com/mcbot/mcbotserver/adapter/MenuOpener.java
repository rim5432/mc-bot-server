package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   <li>{@code chest} (single) — {@link ChestMenu#threeRows} backed by
 *       the chest's {@link ChestBlockEntity} container. Double chests
 *       and trapped chests are not yet handled (Phase 2 follow-up).</li>
 * </ul>
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
     * the block is not a supported menu kind or its block entity is
     * missing. The facade's position is synced before the menu is created
     * so any reach or sound checks inside the menu see the body's live
     * position.
     *
     * @param pos the target block position (world-absolute); never null
     * @return the opened menu binding, or empty if unsupported
     */
    public Optional<BindingMenu> open(BlockPos pos) {
        Level level = facade.body().level();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof CraftingTableBlock) {
            return Optional.of(openCraftingTable(pos));
        }
        if (block instanceof ChestBlock) {
            return openChest(pos);
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
        facade.syncPosition();
        var menu = facade.facadeInventoryMenu();
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "inventory");
    }

    // ---- Internal openers ----

    private BindingMenu openCraftingTable(BlockPos pos) {
        facade.syncPosition();
        ContainerLevelAccess access = ContainerLevelAccess.create(
            facade.body().level(), pos);
        Inventory inv = facade.getInventory();
        BotCraftingMenu menu = new BotCraftingMenu(
            NEXT_ID.getAndIncrement(), inv, access);
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "crafting_table");
    }

    private Optional<BindingMenu> openChest(BlockPos pos) {
        Level level = facade.body().level();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChestBlockEntity chest)) {
            // Block says chest but the entity is gone (exploded, /setblock
            // without entity) — cannot open.
            return Optional.empty();
        }
        facade.syncPosition();
        Inventory inv = facade.getInventory();
        ChestMenu menu = ChestMenu.threeRows(
            NEXT_ID.getAndIncrement(), inv, chest);
        facade.containerMenu = menu;
        return Optional.of(new BindingMenu(menu, facade, "chest"));
    }

    /**
     * The body this opener's facade is bound to. Exposed for callers that
     * need to verify reach or position before opening.
     *
     * @return the body; never null
     */
    public BotBodyEntity body() {
        return facade.body();
    }
}
