package com.mcbot.mcbotserver.adapter;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Inventory-menu variant that bypasses the vanilla
 * {@code InventoryMenu.slotsChanged} ServerPlayer cast. The vanilla
 * override routes through {@code CraftingMenu.slotChangedCraftingGrid},
 * which casts the player to {@code ServerPlayer} to send a container
 * packet — {@link BotPlayerFacade} is not a ServerPlayer, so any
 * material entering the 2x2 grid (direct {@code setItem} or a click's
 * safe-insert) would throw {@code ClassCastException}. Same disease and
 * same cure as {@link BotCraftingMenu} (3x3), one menu kind over.
 *
 * <p>Contract: see issue 0007 §5 Phase 2 (menus driven server-side,
 * no network path). The result recomputation is shared:
 * {@link BotCraftingMenu#recomputeResult} assumes slot 0 = result and
 * slot 1 = first grid slot, which holds for InventoryMenu's layout
 * too.
 *
 * <p>Thread safety: server tick thread only, same as the facade that
 * owns it. The menu is created once per facade and reverted to after
 * every world menu closes.
 */
// contract: see issue 0007 §5 Phase 2 (facade inventory menu without
//            the ServerPlayer cast)
public final class BotInventoryMenu extends InventoryMenu {

    /**
     * The player context. Saved here because InventoryMenu's owner
     * field is private; the constructor receives the inventory whose
     * {@code player} field is public.
     */
    private final Player player;

    /**
     * Creates the facade's inventory menu (2x2 grid + full inventory)
     * backed by the bridge inventory.
     *
     * @param inventory the bridge inventory; never null
     * @param active    server-side flag ({@code !level.isClientSide});
     *                  the facade is always server-side
     * @param owner     the owning facade; never null
     */
    public BotInventoryMenu(Inventory inventory, boolean active, Player owner) {
        super(inventory, active, owner);
        this.player = inventory.player;
    }

    /**
     * Recompute the 2x2 crafting result when the grid changes,
     * bypassing the vanilla ServerPlayer cast. See
     * {@link BotCraftingMenu#recomputeResult} for the shared logic
     * and the remote-slots note.
     *
     * @param container the container that changed (unused; the grid is
     *                  reached through the slots list)
     */
    @Override
    public void slotsChanged(Container container) {
        BotCraftingMenu.recomputeResult(this, player);
    }
}
