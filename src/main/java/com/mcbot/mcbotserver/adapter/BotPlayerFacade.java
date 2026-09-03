package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
 * <p>PRIVATE-FIELD TRAP MAP (surveyed 2026-08-30, 35 this.inventory
 * reads in decompiled Player): vanilla Player bypasses the
 * overridable accessors on internal paths. Bridged and green:
 * getInventory (menus), getMainHandItem/getItemBySlot (the use
 * chain, Item.use contexts), getProjectile (bow ammo),
 * closeContainer's menu revert. LATENT - never call these on the
 * facade, use the stack-level forms instead:
 * hasCorrectToolForDrops (reads this.inventory.getSelected(),
 * Player:788 - call stack.isCorrectToolForDrops(state)) and
 * getDestroySpeed (Player:753 - call stack.getDestroySpeed(state)).
 * New Player-surface calls must be checked against this map before
 * landing.
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
     * The binding for the menu this facade currently has open, or null
     * when only the inventory menu is active. Owned by the facade the
     * same way ServerPlayer owns {@code containerMenu}: MenuOpener
     * closes this binding before opening a new menu, mirroring
     * vanilla's openMenu (which closes the previous container first).
     */
    private BindingMenu openMenu;

    /**
     * Creates a facade bound to one body. The facade is not spawned —
     * it is a context object for menu operations.
     *
     * @param body the physical carrier; never null
     */
    public BotPlayerFacade(BotBodyEntity body) {
        super(body.level(), body.blockPosition(), body.getYRot(), createProfile(body));
        this.body = body;
        // NOTE: Entity.eyeHeight is private and getEyeHeight() is final,
        // so this facade retains the Player default eyeHeight (1.62) even
        // though the carrier PathfinderMob is shorter (~1.53). The 0.09
        // discrepancy is a known design choice (issue 0007 §7 risk 1):
        // menu reach checks via getEyePosition() use 1.62, which is
        // slightly more permissive than the body's actual eye line. This
        // is acceptable for device semantics and must not be "fixed" by
        // future editors without re-calibrating all interaction geometry.
        this.bridgeInventory = new BridgeInventory(this, body);
        // active=true on the server side (mirrors InventoryMenu's
        // !level.isClientSide flag in the Player constructor).
        // BotInventoryMenu bypasses the vanilla slotsChanged
        // ServerPlayer cast — see its class Javadoc.
        this.facadeInventoryMenu = new BotInventoryMenu(bridgeInventory, true, this);
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
     * Extended block reach for the device facade. The vanilla survival
     * reach (4.5) is too short for the MLG water-bucket reflex: the rule
     * fires while the body is still 5-6 blocks above the sensed ground,
     * and the {@code BucketItem.use} POV raycast misses the floor, so
     * the water lands too late to cancel the fall. 6.0 covers the
     * deepest ground-cell scan (7 blocks from the feet minus eye height)
     * without reaching into creative-mode range. Menu reach checks and
     * the InteractBlockExecutor gate use their own policies, so this only
     * affects air-use raycasts (bucket, rod).
     *
     * @return 6.0 blocks
     */
    @Override
    public double getBlockReach() {
        return 6.0;
    }

    /**
     * The held item the vanilla placement call graph reads. {@code Player}
     * resolves {@code getMainHandItem()} through {@code getItemBySlot},
     * which reads the {@code private final inventory} field (the empty
     * default inventory), not {@link #getInventory()}. Without this
     * override, {@code UseOnContext} carries {@code ItemStack.EMPTY} and
     * {@code BlockItem.place} shrinks the wrong stack — the body's hotbar
     * never loses a placed block.
     *
     * @return the body's selected hotbar stack, never null
     */
    @Override
    public ItemStack getMainHandItem() {
        return bridgeInventory.getSelected();
    }

    /**
     * Hands and armor resolve through the body's equipment mirror.
     * Vanilla's use chain reads held items through
     * {@code getItemBySlot} ({@code LivingEntity.getItemInHand}),
     * NOT through {@code getInventory()} or {@code getMainHandItem}
     * - without this override, {@code startUsingItem} reads the empty
     * private hands list and silently no-ops, killing every
     * facade-held draw (bow charge first, any hold item next) at its
     * first guard.
     *
     * @param slot the vanilla equipment slot; never null
     * @return the body's mirrored stack for that slot; never null
     */
    @Override
    public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return body.getItemBySlot(slot);
    }

    /**
     * Projectile ammo resolution over the bridged inventory. Vanilla
     * Player.getProjectile's fallback scan iterates the PRIVATE
     * {@code this.inventory} field (empty default on this facade),
     * so a bow release with arrows only in the backpack silently
     * found nothing and never fired - same trap family as
     * {@code getMainHandItem}. Hands first (offhand quivers work
     * through the equipment mirror), then the full bridged
     * container.
     *
     * @param shootable the weapon stack; never null
     * @return the ammo stack, or empty when none is carried
     */
    @Override
    public ItemStack getProjectile(ItemStack shootable) {
        if (!(shootable.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem weapon)) {
            return ItemStack.EMPTY;
        }
        java.util.function.Predicate<ItemStack> ammo = weapon.getAllSupportedProjectiles();
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            ItemStack held = getItemInHand(hand);
            if (ammo.test(held)) {
                return held;
            }
        }
        for (int i = 0; i < getInventory().getContainerSize(); i++) {
            ItemStack stack = getInventory().getItem(i);
            if (ammo.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
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
     * Attach a freshly constructed menu binding as the facade's open
     * menu. Package-private: only {@link BindingMenu}'s constructor
     * calls this, at attach time — the opener closes the previous
     * binding before constructing the new one.
     *
     * @param menu the new open binding; never null
     */
    void setOpenMenu(BindingMenu menu) {
        this.openMenu = menu;
    }

    /**
     * The currently open menu binding, if any.
     *
     * @return the open binding, or null when no world menu is open
     */
    BindingMenu openMenu() {
        return openMenu;
    }

    /**
     * Detach a closed binding. Only clears when the closed binding is
     * still the current one — a stale binding closed after a newer
     * menu opened must not detach the live one.
     *
     * @param menu the binding that just closed; never null
     */
    void menuClosed(BindingMenu menu) {
        if (this.openMenu == menu) {
            this.openMenu = null;
        }
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
    public net.minecraft.world.entity.item.ItemEntity drop(ItemStack stack, boolean throwRandomly) {
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
     * final on Entity. Also syncs experience: anvil and enchanting menus
     * read the public {@code experienceLevel} field directly for the
     * affordability check, and the facade is never player-ticked so its
     * field stays at the construction default (0) without this sync.
     */
    public void syncPosition() {
        setPos(body.getX(), body.getY(), body.getZ());
        setYRot(body.getYRot());
        setXRot(body.getXRot());
        syncExperience();
    }

    /**
     * Copy the body's experience state into the facade's Player fields.
     * AnvilMenu and EnchantmentMenu access {@code experienceLevel} as a
     * public field (not a getter), so delegation via override is
     * impossible — the fields must be written before the menu reads
     * them. Called from {@link #syncPosition()} so every menu operation
     * sees the body's live level.
     */
    private void syncExperience() {
        this.experienceLevel = body.experience().getLevel();
        this.experienceProgress = body.experience().getProgress();
        this.totalExperience = body.experience().getTotal();
    }

    /**
     * Deduct or grant levels on the body, then re-sync so the facade
     * field stays consistent. AnvilMenu calls
     * {@code player.giveExperienceLevels(-cost)} after a successful
     * result take; overriding here routes the cost to the body's XP
     * store instead of the facade's dead field.
     *
     * @param levels levels to add (positive) or subtract (negative)
     */
    @Override
    public void giveExperienceLevels(int levels) {
        body.experience().addLevels(levels);
        syncExperience();
    }

    /**
     * Deduct or grant experience points on the body, then re-sync.
     * Mirror of {@link #giveExperienceLevels(int)} for the points-based
     * path (enchanting tables use levels, but some mod menus use points).
     *
     * @param points XP to add (positive) or subtract (negative)
     */
    @Override
    public void giveExperiencePoints(int points) {
        body.experience().addPoints(points);
        syncExperience();
    }

    /**
     * Drive the use-item loop one tick (bow draw charge, rod hold).
     * The facade is never player-ticked, so the charge accumulator
     * vanilla advances from LivingEntity.tick needs an explicit pump -
     * {@code releaseUsingItem} reads the accumulated duration to scale
     * the released action. Syncs position first: the loop reads the
     * entity position every tick while drawing.
     */
    public void tickUseLoop() {
        syncPosition();
        updateUsingItem(this.useItem);
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
    public void awardStat(net.minecraft.resources.ResourceLocation stat, int amount) {
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
