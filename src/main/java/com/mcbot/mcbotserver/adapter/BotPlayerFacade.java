package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Minimal Player facade for menu and crafting operations (issue 0007
 * Phase 2, Path A). Extends {@link Player} so vanilla menu constructors
 * and the {@code AbstractContainerMenu} call graph accept it, but
 * delegates every physical property to the {@link BotBodyEntity} carrier.
 * The facade is never spawned in the world — it is a context object that
 * exists only while a menu is open, exactly the way
 * {@code ServerPlayerGameMode.useItemOn} needs a Player but the bot's
 * real body is a PathfinderMob.
 *
 * <p>Contract: see issue 0007 §4 (Path A) and §7 risk "facade blast
 * radius". Player declares exactly two abstract methods
 * ({@code isSpectator}, {@code isCreative}); the risk is not "implement
 * 150 methods" but default-method delegation reaching null fields
 * ({@code getGameProfile}, {@code connection}, {@code inventory}). This
 * facade overrides the methods the menu call graph touches and leaves the
 * rest to Player defaults — if a new menu path reaches an un-overridden
 * method and NPEs, the fix is one override here, not an architecture
 * change.
 *
 * <p>Inventory bridge: {@code Player.inventory} is a {@code private final}
 * field initialized in the super constructor to an empty {@link Inventory}.
 * The facade cannot replace it, so it overrides {@link #getInventory()}
 * to return a {@link BridgeInventory} backed by the body's
 * {@link BindingInventory}, and creates its own {@link InventoryMenu}
 * from that bridge. The super's {@code inventoryMenu} field is ignored —
 * it is {@code final} but only referenced by {@code closeContainer()},
 * which this facade overrides.
 *
 * <p>Thread safety: the facade is created and used on the server tick
 * thread only, same as the body. It holds no mutable state beyond the
 * bridge inventory and the menu reference.
 */
// contract: see issue 0007 §4 Path A (facade on PathfinderMob carrier)
public final class BotPlayerFacade extends Player {

    /** The physical carrier this facade delegates to. */
    private final BotBodyEntity body;

    /** Inventory bridge backed by the body's BindingInventory. */
    private final BridgeInventory bridgeInventory;

    /**
     * The facade's own inventory menu, backed by {@link #bridgeInventory}.
     * The super's {@code inventoryMenu} is backed by the empty default
     * inventory and is never used.
     */
    private final InventoryMenu facadeInventoryMenu;

    /**
     * Creates a facade bound to one body. The facade is not spawned —
     * it is a context object for menu operations.
     *
     * @param body the physical carrier; never null
     */
    public BotPlayerFacade(BotBodyEntity body) {
        super(body.level(), body.blockPosition(), body.getYRot(),
            createProfile(body));
        this.body = body;
        this.bridgeInventory = new BridgeInventory(this, body);
        // active=true on the server side (mirrors InventoryMenu's
        // !level.isClientSide flag in the Player constructor).
        this.facadeInventoryMenu = new InventoryMenu(
            bridgeInventory, true, this);
        this.containerMenu = facadeInventoryMenu;
    }

    /**
     * The body this facade delegates to.
     *
     * @return the carrier; never null
     */
    public BotBodyEntity body() {
        return body;
    }

    /**
     * The bridge inventory backed by the body's BindingInventory.
     *
     * @return the bridge; never null
     */
    public BridgeInventory bridgeInventory() {
        return bridgeInventory;
    }

    /**
     * The facade's inventory menu, backed by the bridge inventory. Use
     * this instead of {@code Player.inventoryMenu} (which is backed by
     * the empty default inventory).
     *
     * @return the inventory menu; never null
     */
    public InventoryMenu facadeInventoryMenu() {
        return facadeInventoryMenu;
    }

    @Override
    public Inventory getInventory() {
        return bridgeInventory;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    /**
     * Close the current menu and revert to the inventory menu. Overrides
     * the default which reverts to the super's (empty) inventoryMenu.
     */
    @Override
    public void closeContainer() {
        this.containerMenu = this.facadeInventoryMenu;
    }

    /**
     * Drop an item stack into the world. Delegates to the body's
     * {@code spawnAtLocation} — the facade has no physical presence.
     * The {@code throwRandomly} flag is ignored; the bot always drops
     * at the body location with the standard y-offset.
     *
     * @param stack         the stack to drop; never null
     * @param throwRandomly ignored (bot has no aim direction)
     * @return the spawned item entity, or null if the stack was empty
     */
    @Override
    public net.minecraft.world.entity.item.ItemEntity drop(
            ItemStack stack, boolean throwRandomly) {
        if (stack.isEmpty()) {
            return null;
        }
        return body.spawnAtLocation(stack, 0.5f);
    }

    // ---- Position / level delegation ----
    // The facade is never spawned; its super-entity position is stale
    // from construction. Entity.getX/Y/Z and getEyePosition are final
    // and cannot be overridden, so the facade syncs its position via
    // {@link #syncPosition()} (called before menu operations) which
    // uses the non-final setPos. Menu code that reads position gets the
    // synced value; code that reads getEyePosition gets the synced
    // position plus the entity's eyeHeight (correct for a player-height
    // body).

    @Override
    public Level level() {
        return body.level();
    }

    /**
     * Sync the facade's entity position to the body's current position.
     * Call before any menu operation that may read position (reach
     * checks, sound positioning). Uses {@code setPos} which is not
     * final on Entity.
     */
    public void syncPosition() {
        setPos(body.getX(), body.getY(), body.getZ());
        setYRot(body.getYRot());
        setXRot(body.getXRot());
    }

    @Override
    public UUID getUUID() {
        return body.getUUID();
    }

    @Override
    public String getStringUUID() {
        return body.getStringUUID();
    }

    /**
     * No-op: the bot has no statistics to award. Menu code may call
     * this after crafting or smelting; silently ignoring is correct
     * for a device with no advancement/stat system.
     */
    @Override
    public void awardStat(net.minecraft.resources.ResourceLocation stat) {
        // no-op
    }

    /**
     * No-op overload for integer-stat awards.
     */
    @Override
    public void awardStat(net.minecraft.resources.ResourceLocation stat,
                           int amount) {
        // no-op
    }

    /**
     * Creates a GameProfile from the body's identity. The profile's UUID
     * matches the body's UUID so any UUID-based lookup (e.g. scoreboard
     * teams) resolves consistently. The name is the modid — the bot has
     * no display name, and menu code does not read the profile name.
     */
    private static GameProfile createProfile(BotBodyEntity body) {
        return new GameProfile(body.getUUID(), "mcbotserver");
    }
}
