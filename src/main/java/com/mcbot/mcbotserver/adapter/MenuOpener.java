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
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

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
 *   <li>{@code chest} (single, including trapped) —
 *       {@link ChestMenu#threeRows} backed by the chest's
 *       {@link ChestBlockEntity} container. Double chests are
 *       explicitly rejected (empty result): vanilla merges the two
 *       halves into a CompoundContainer, and binding one half would
 *       silently expose 27 of 54 slots. CompoundContainer support is
 *       a follow-up.</li>
 * </ul>
 *
 * <p>Distance policy: the open gate is 4.5 blocks (the project
 * interaction reach, matching DigExecutor /
 * InteractBlockExecutor); once open, the menu's vanilla stillValid
 * predicate (8 blocks for container menus) is re-checked per click
 * by {@link BindingMenu}. The asymmetry is intentional — it mirrors
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

    /** Maximum interaction reach in blocks. Matches the reach used by
     * {@code DigExecutor} and {@code InteractBlockExecutor} (4.5 blocks,
     * the vanilla survival player reach). Squared for comparison. */
    private static final double REACH_BLOCKS_SQ = 4.5 * 4.5;

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
     * the block is not a supported menu kind, the block entity is
     * missing, or the block is a double chest. The facade's position is
     * synced before the menu is created so any reach or sound checks
     * inside the menu see the body's live position. Any menu the facade
     * currently has open is closed first (grid materials returned,
     * chest lid lowered) — vanilla parity: ServerPlayer.openMenu closes
     * the previous container before opening the new one.
     *
     * @param pos the target block position (world-absolute); never null
     * @return the opened menu binding, or empty if unsupported
     */
    public Optional<BindingMenu> open(BlockPos pos) {
        closeCurrentMenu();
        Level level = facade.body().level();

        // Reach gate: measured from the body's eye position to the block
        // center. Matches DigExecutor / InteractBlockExecutor (4.5 blocks).
        // Without this the bot could open a menu on any loaded chunk
        // regardless of distance.
        facade.syncPosition();
        double distSq = facade.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
        if (distSq > REACH_BLOCKS_SQ) {
            return Optional.empty();
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof CraftingTableBlock) {
            return Optional.of(openCraftingTable(pos));
        }
        if (block instanceof ChestBlock) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                // double chest: needs the vanilla CompoundContainer
                // merge; opening one half would silently expose 27 of
                // 54 slots with no error to the caller
                return Optional.empty();
            }
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
        closeCurrentMenu();
        facade.syncPosition();
        var menu = facade.facadeInventoryMenu();
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "inventory", null);
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

    // ---- Internal openers ----

    private BindingMenu openCraftingTable(BlockPos pos) {
        facade.syncPosition();
        ContainerLevelAccess access = ContainerLevelAccess.create(
            facade.body().level(), pos);
        Inventory inv = facade.getInventory();
        BotCraftingMenu menu = new BotCraftingMenu(
            NEXT_ID.getAndIncrement(), inv, access);
        facade.containerMenu = menu;
        return new BindingMenu(menu, facade, "crafting_table",
            toCellPos(pos));
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
        return Optional.of(new BindingMenu(menu, facade, "chest",
            toCellPos(pos)));
    }

    /**
     * Convert an engine block position to the api position type for
     * snapshots (the api never sees engine types).
     */
    private static com.mcbot.mcbotserver.api.types.CellPos toCellPos(
            BlockPos pos) {
        return new com.mcbot.mcbotserver.api.types.CellPos(
            pos.getX(), pos.getY(), pos.getZ());
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
