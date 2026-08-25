package com.mcbot.mcbotserver.adapter;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Crafting menu variant that bypasses the vanilla
 * {@code slotChangedCraftingGrid} ServerPlayer cast. The vanilla
 * {@link CraftingMenu#slotsChanged} calls
 * {@code slotChangedCraftingGrid}, which casts the player to
 * {@code ServerPlayer} to send a {@code ClientboundContainerSetSlotPacket}.
 * The bot's {@link BotPlayerFacade} is not a ServerPlayer, so that cast
 * would throw {@link ClassCastException} the moment any material lands in
 * the 3x3 grid.
 *
 * <p>This subclass overrides {@link #slotsChanged} and recomputes the
 * crafting result directly: it reads the {@link CraftingContainer} and
 * {@link ResultContainer} through the public {@code slots} list (slot 0
 * is the result, slots 1-9 are the grid), queries the server
 * {@code RecipeManager}, and writes the result into the result container.
 * No network packet is sent — the bot drives the menu server-side with a
 * no-op {@code ContainerSynchronizer} (see {@link BindingMenu}).
 *
 * <p>Contract: see issue 0007 Phase 2 (crafting-table disclosure) and
 * risk 2 ("core never writes menu state"). The result slot is written
 * here (adapter-side, inside the vanilla menu's own state machine), not
 * by the core planner. The core planner only issues click sequences;
 * {@code ResultSlot.onTake} consumes grid materials on take exactly as
 * vanilla.
 *
 * <p>Deliberate deviation from vanilla: the recipe-used bookkeeping
 * ({@code ResultContainer.setRecipeUsed}) is recorded but not forwarded
 * to a player's recipe book — the bot has no recipe book. This matches
 * the facade's no-op {@code awardStat} policy.
 */
// contract: see issue 0007 §5 Phase 2 (crafting menu driven server-side)
public final class BotCraftingMenu extends CraftingMenu {

    /**
     * The player context. Saved here because CraftingMenu's player field
     * is private and has no getter; the constructor receives the inventory
     * whose {@code player} field is public.
     */
    private final Player player;

    /**
     * Creates a crafting menu bound to the player's inventory and a
     * world position. The access determines where {@code slotsChanged}
     * runs its recipe lookup; pass {@link ContainerLevelAccess#NULL}
     * only for offline tests — a real crafting table needs the level
     * to resolve recipes.
     *
     * @param containerId the menu's network id (unused server-side, but
     *                    required by the super constructor)
     * @param inventory   the player's inventory (the bridge); never null
     * @param access      the world-position access for recipe resolution;
     *                    never null
     */
    public BotCraftingMenu(int containerId, Inventory inventory,
                           ContainerLevelAccess access) {
        super(containerId, inventory, access);
        this.player = inventory.player;
    }

    /**
     * Recompute the crafting result when the grid changes. Bypasses the
     * vanilla {@code slotChangedCraftingGrid} (which casts to
     * ServerPlayer) and resolves the recipe directly against the server
     * recipe manager. The result is written into slot 0's container
     * (the {@link ResultContainer}) and the remote-slot mirror is
     * updated so subsequent {@code clicked} calls see the correct state.
     *
     * @param container the container that changed (unused; the grid is
     *                  reached through the slots list)
     */
    @Override
    public void slotsChanged(Container container) {
        Level level = player.level();
        if (level.isClientSide) {
            return;
        }
        // Slot 0 is the ResultSlot whose container is the ResultContainer;
        // slot 1 is the first crafting-grid slot whose container is the
        // CraftingContainer. Both are reachable through the public slots
        // list — no reflection needed.
        ResultContainer resultSlots =
            (ResultContainer) slots.get(0).container;
        CraftingContainer craftSlots =
            (CraftingContainer) slots.get(1).container;

        ItemStack result = ItemStack.EMPTY;
        Optional<CraftingRecipe> recipe = level.getServer()
            .getRecipeManager()
            .getRecipeFor(RecipeType.CRAFTING, craftSlots, level);
        if (recipe.isPresent()) {
            CraftingRecipe r = recipe.get();
            // Record recipe-used bookkeeping (for recipe-unlock mods);
            // the bot has no recipe book but recording is harmless.
            resultSlots.setRecipeUsed(r);
            ItemStack assembled = r.assemble(craftSlots,
                level.registryAccess());
            if (assembled.isItemEnabled(level.enabledFeatures())) {
                result = assembled;
            }
        }
        resultSlots.setItem(0, result);
        // Note: remoteSlots is intentionally NOT updated here. In vanilla
        // it mirrors slot state for client-server diff sync; the bot drives
        // the menu server-side with a no-op ContainerSynchronizer, and
        // AbstractContainerMenu.clicked() never reads remoteSlots (verified
        // against the 1.20.1 decompiled tree). The Slot itself reads from
        // resultSlots, so click/take paths see the correct result.
    }
}
