package com.mcbot.mcbotserver.adapter.entity;

import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The physical body: a vanilla-pathfinding-capable mob whose locomotion
 * is driven exclusively by per-tick input writes from the binding.
 *
 * <p>Carrier ratification (Stage 1 review; issue 0007 Path A): a
 * custom PathfinderMob carrier instead of a ServerPlayer subclass.
 * Rationale: engine-owned physics plus the accumulated calibration
 * surface (direct jumpFromGround / jumpInFluid calls, no-op control
 * swaps, drive constants) make a carrier rewrite prohibitively
 * expensive; player parity arrives on the interaction axis via
 * BotPlayerFacade (typed Player parameters, issue 0007 Phase 2)
 * rather than by making the body a real ServerPlayer. The threat axis
 * of the same ruling is already shipped (presence pass below); the
 * interaction axis is in active development (inventory sense first,
 * issue 0007 Phase 1).
 * Vanilla {@code travel()} remains the only thing that moves this body
 * — boundary A holds because every write below is an intent echo of an
 * Actor claim, never self-computed physics.
 *
 * <p>Why the default AI controls cannot coexist with input driving
 * (empirically confirmed via body-probe, do not re-derive from
 * memory): Mob.aiStep() runs goalSelector.tick(), navigation.tick(),
 * customServerAiStep() (our write point), THEN moveControl.tick(),
 * lookControl.tick(), jumpControl.tick(). Each default control sits
 * in a slot AFTER the binding's write and silently clobbers shared
 * state: MoveControl.WAIT does setZza(0); LookControl.resetXRotOnTick
 * forces setXRot(0) (wipes non-zero pitch); JumpControl does
 * setJumping(false). The constructor therefore swaps all three for
 * no-ops; the binding is the sole writer of locomotion, look, and
 * jump state. Stage 2 landmine: if A* or follow goals ever need
 * vanilla pathing again, all three swaps must be revisited as a
 * whole (drive through the controls instead of around them), not
 * patched by re-enabling one beside the binding.
 *
 * <p>Implementation note: constructed and ticked by the server only.
 *
 * <p>World treatment (user ruling 2026-08-25): the world treats the
 * bot as a player wherever the engine asks "is this a player".
 * Hostile targeting is one such place - vanilla sight-aggro goals
 * match {@code Player.class} entity scans, which structurally cannot
 * see a PathfinderMob. The presence pass below closes that gap from
 * the carrier side: monsters with line of sight and no other target
 * acquire this body exactly as they would acquire a player, at their
 * own follow range. Menu/interaction player-ness is the OTHER axis
 * and stays with the {@code BotPlayerFacade} plan (issue 0007 Path
 * A) - the facade answers typed parameters, never entity scans.
 */
// contract: see boundaries.md section A (Actor is intents-only) and
// decision 2 as amended by the entity-binding spike follow-up
// GodClass exempted: a vanilla entity subclass carries its ticking
// surfaces (hunger, pose sync, MLG helpers, carrier inventory hooks)
// as methods by engine shape - splitting them out would put engine
// lifecycle code outside the entity where it cannot override.
@SuppressWarnings("PMD.GodClass")
public final class BotBodyEntity extends PathfinderMob {

    private float driveForward;
    private float driveStrafe;
    private boolean driveJump;
    private boolean sneakLatch;
    private boolean hasPendingRotation;
    private float targetYaw;
    private float targetPitch;
    private int presenceCooldown;

    /**
     * Ticks between presence scans. The scan queries a wide entity
     * box and ray-traces one line of sight per candidate; hostile
     * awareness does not need per-tick latency (vanilla targeting
     * goals also re-scan on their own cadence).
     */
    public static final int PRESENCE_SCAN_INTERVAL_TICKS = 20;

    /**
     * Half-width of the presence scan box, in blocks. Above every
     * vanilla follow range that matters (zombies 35, base attribute
     * 32); the per-monster attribute check below is the authority.
     */
    private static final double PRESENCE_SCAN_HALF_WIDTH = 40.0;

    /**
     * The body's hunger state (Phase 4 eat slice, issue 0010): a
     * vanilla {@link net.minecraft.world.food.FoodData} owned and
     * ticked by the carrier - Path A facade, user ruling 2026-08-27.
     * FoodData has zero Player-field dependencies except its
     * {@code tick(Player)} signature; {@link HungerTicker#tick} carries
     * the same math with {@code this} in the player's seat
     * (decompiled FoodData.java, dossier 2026-08-27).
     */
    private final net.minecraft.world.food.FoodData foodData = new net.minecraft.world.food.FoodData();

    /** Player-shaped ground-item pickup into the container. */
    private final GroundPickup groundPickup = new GroundPickup(this);

    /** Hunger lifecycle engine (vanilla FoodData.tick clone). */
    private final HungerTicker hungerTicker = new HungerTicker();

    /**
     * The body's hunger state; nutrition/saturation/exhaustion in
     * vanilla FoodData semantics.
     *
     * @return the food data; never null
     */
    public net.minecraft.world.food.FoodData getFoodData() {
        return foodData;
    }

    /**
     * Hotbar selection echoed from the SLOT channel. A mob carrier has
     * no vanilla hotbar; the value is recorded state and mirrored to
     * {@link #inventory} on every SLOT claim write (issue 0007 Phase 1).
     */
    public int selectedSlot;

    /**
     * The bot's inventory container (41 slots: 36 main + 4 armor + 1
     * offhand). Owned by the body because it is physical carrier state,
     * same category as health or air supply. The perception layer reads
     * it via {@link BindingInventory#snapshot()}; item pickup and menu
     * mutations write through {@link BindingInventory#container()}.
     *
     * <p>Issue 0007 Phase 1: SimpleContainer-backed (no Player required);
     * armor semantics and vanilla Inventory parity arrive in later phases.
     */
    private final BindingInventory inventory = new BindingInventory();

    /**
     * The body's inventory binding.
     *
     * @return the inventory; never null
     */
    public BindingInventory getInventory() {
        return inventory;
    }

    /**
     * Drop the selected hotbar item as a world item entity. Called by
     * the INTERACT channel's DropSelected handler on the rising edge
     * (issue 0007 Phase 1). Uses {@code Entity.spawnAtLocation} — the
     * same vanilla path mobs use for death drops — which handles the
     * random velocity and the item entity registration. The source slot
     * is cleared (full stack) or shrunk (single item) after the spawn.
     *
     * <p>Why on the body and not in BindingActor: the drop mutates the
     * inventory container (owned by the body) and spawns a world entity
     * (a body-level operation). BindingActor is the claim resolver; it
     * delegates the actual world mutation here, same as DigExecutor
     * delegates destroy progress to the body's level.
     *
     * @param fullStack true to drop the entire stack; false to drop a
     *                  single item (vanilla Q vs Ctrl-Q distinction)
     */
    public void dropSelectedItem(boolean fullStack) {
        var container = inventory.container();
        ItemStack held = container.getItem(selectedSlot);
        if (held.isEmpty()) {
            return;
        }
        // 0.5f y-offset: spawns at about hand height, matching the
        // vanilla player drop visual.
        ItemStack toDrop = fullStack ? held.copy() : held.copyWithCount(1);
        ItemEntity dropped = spawnAtLocation(toDrop, 0.5f);
        if (dropped != null) {
            // Vanilla player-thrown drops wait 40 ticks before pickup;
            // spawnAtLocation's default of 10 would let the bot
            // re-vacuum its own drop almost immediately.
            dropped.setPickUpDelay(40);
        }
        if (fullStack) {
            container.setItem(selectedSlot, ItemStack.EMPTY);
        } else {
            held.shrink(1);
        }
    }

    /**
     * Creates a body for the registered entity type.
     *
     * @param type  the registered type; never null
     * @param level the server level; never null
     */
    public BotBodyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        // Neutralize all three vanilla controls: the binding is the sole
        // writer of locomotion, look, and jump state. Each control runs
        // in Mob.aiStep() AFTER customServerAiStep() and would silently
        // clobber the binding's writes:
        //   - MoveControl.tick() WAIT branch: setZza(0) every tick
        //   - LookControl.tick(): resetXRotOnTick() forces setXRot(0),
        //     wiping any non-zero pitch the binding set (pitch-aim landmine)
        //   - JumpControl.tick(): setJumping(false) every tick, would
        //     zero a setJumping(true) before LivingEntity.aiStep() sees it
        // All three are Goal-system executors; with no goals registered and
        // the binding owning every input channel, they are concurrent writers
        // on shared state, not helpers. Anonymous no-ops match the existing
        // MoveControl pattern.
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
                // binding owns locomotion inputs; nothing to do
            }
        };
        this.lookControl = new net.minecraft.world.entity.ai.control.LookControl(this) {
            @Override
            public void tick() {
                // binding owns rotation via setRot/setYHeadRot; nothing to do
            }
        };
        this.jumpControl = new net.minecraft.world.entity.ai.control.JumpControl(this) {
            @Override
            public void tick() {
                // binding owns jump via jumpFromGround/jumpInFluid; nothing to do
            }
        };
        setPersistenceRequired();
    }

    /**
     * One tick of locomotion intent, written by the binding before the
     * physics step consumes it.
     *
     * @param forward -1..1 forward drive
     * @param strafe  -1..1 strafe drive
     * @param jump    true to request upward thrust this tick - a
     *                ground jump on land, buoyant ascent while in
     *                water or lava (issue 0004 F2 + lava branch)
     */
    public void setDrive(float forward, float strafe, boolean jump) {
        setDrive(forward, strafe, jump, false);
    }

    /**
     * Queue the drive for the next physics step, with the sneak
     * modifier. Sneak is dual-application: the shift flag drives
     * detection-radius reduction and vanilla mob AI courtesies, while
     * {@code setStayingOnGroundSurface} engages {@code Entity.move}'s
     * {@code maybeBackOffFromEdge} clip so a sneaking walk cannot step
     * off a ledge. The harness latch ({@link #setSneakLatched})
     * survives idle ticks where no MOVE claim wins; a per-tick
     * {@code Move.sneak} only ever widens it.
     *
     * @param forward -1..1 forward drive
     * @param strafe  -1..1 strafe drive
     * @param jump    true to request upward thrust this tick - a
     *                ground jump on land, buoyant ascent while in
     *                water or lava (issue 0004 F2 + lava branch)
     * @param sneak   true to hold the sneak modifier this tick (issue
     *                0004 F1 seed; Intent.Move.sneak finally consumed)
     */
    public void setDrive(float forward, float strafe, boolean jump, boolean sneak) {
        this.driveForward = forward;
        this.driveStrafe = strafe;
        this.driveJump = jump;
        boolean sneakOn = sneak || sneakLatch;
        applySneak(sneakOn);
        if (!jump) {
            setJumping(false);
        }
    }

    /**
     * Harness-latched sneak: survives idle ticks (no MOVE claim) until
     * explicitly cleared - the /bot sneak verb's write site. Applies
     * immediately so an idle body still holds the edge guard.
     *
     * @param latched true to hold sneak across ticks; false to release
     */
    public void setSneakLatched(boolean latched) {
        this.sneakLatch = latched;
        applySneak(latched);
    }

    /**
     * Apply the sneak pose pair: the shift flag drives vanilla's
     * pressure-plate/trigger courtesies, and CROUCHING pose gives the
     * 1.5-block height (gap crawl + {@code isCrouching} visibility
     * reduction) - both entity-level, no player required. Deliberate
     * omission: vanilla's walk-off-the-edge clip lives only in
     * {@code Player.maybeBackOffFromEdge} (the base Entity method is a
     * stub), so a sneaking body CAN still step off a ledge - porting
     * that guard is its own item with its own gametest, not free
     * rider behavior here.
     *
     * @param sneakOn true to crouch, false to stand back up
     */
    private void applySneak(boolean sneakOn) {
        setShiftKeyDown(sneakOn);
        setPose(sneakOn ? Pose.CROUCHING : Pose.STANDING);
    }

    /**
     * Crouch semantics made real: the base Entity answers the same
     * dimensions for every pose (only Player overrides), so without
     * this the CROUCHING pose would flip flags without changing the
     * collision box. Vanilla's crouch box is 0.6 x 1.5 - the 1.5-block
     * gap crawl and the standing 1.8 are the two poses the sneak pair
     * switches between.
     *
     * @param pose the requested pose
     * @return the crouch box while crouching, the registered box
     *         otherwise
     */
    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(Pose pose) {
        return pose == Pose.CROUCHING
                ? net.minecraft.world.entity.EntityDimensions.scalable(0.6f, 1.5f)
                : super.getDimensions(pose);
    }

    /**
     * Queue an absolute rotation for the next tick.
     *
     * @param yawDeg   target body yaw in degrees
     * @param pitchDeg target view pitch in degrees
     */
    public void setTargetRotation(float yawDeg, float pitchDeg) {
        this.hasPendingRotation = true;
        this.targetYaw = yawDeg;
        this.targetPitch = pitchDeg;
    }

    @Override
    protected void customServerAiStep() {
        // Hunger lifecycle (Phase 4 eat slice, issue 0010): the old
        // peaceful-style free-regen floor is RETIRED - regeneration
        // and starvation are now vanilla food-driven, exactly the
        // one-line delete the old javadoc promised. Runs regardless
        // of crash latch: the latch freezes the BRAIN, not the body's
        // life support.
        hungerTicker.tick(this, foodData);

        // Deliberately no super call: goal selectors and MoveControl
        // would fight the binding for zza. Inputs are applied directly
        // through LivingEntity's own public drive setters.
        //
        // ORDER MATTERS: Mob.setSpeed() overrides LivingEntity.setSpeed()
        // and additionally calls setZza(speed) — vanilla treats speed and
        // drive as synonymous for ordinary mobs. If setXxa/setZza run
        // before setSpeed, the speed write clobbers the binding's drive
        // every tick (zza=0.25 instead of 0), so an idle body walks
        // forward forever. setSpeed first, then re-apply drive values.
        setSpeed((float) getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
        setXxa(driveStrafe);
        setZza(driveForward);
        // Movement exhaustion, vanilla-shaped (Player.aiStep rates):
        // sprinting burns 10x the walking cost per block; nothing on a
        // mob accumulates exhaustion naturally, so the carrier feeds it
        // from the distance it actually traveled this tick.
        float movedHorizontally = (float) Math.hypot(getX() - xo, getZ() - zo);
        foodData.addExhaustion(movedHorizontally * (isSprinting() ? 0.1f : 0.01f));
        applyDriveJump();
        if (hasPendingRotation) {
            // setRot does yaw%360 / pitch%360 normalization internally;
            // setYHeadRot aligns the head with the body so the model does
            // not lag one tick behind. Do NOT add a separate setYRot call
            // after setRot — it would overwrite the normalized value with
            // a raw angle and break the wrap-around for yaw outside [0,360).
            setRot(targetYaw, targetPitch);
            setYHeadRot(targetYaw);
            hasPendingRotation = false;
        }
        tickPresence();
        groundPickup.tick();
    }

    /**
     * Eat the held stack's top item (Phase 4 eat slice): the vanilla
     * mob-safe chain - {@code finishUsingItem} plays the eat sound,
     * rolls probability effects (rotten flesh, golden apples), shrinks
     * the stack and fires the EAT game event; the separate
     * {@code foodData.eat} adds nutrition/saturation, exactly the
     * Player.eat split. Gated like vanilla Item.use: eating requires
     * hunger (1.20.1 exposes no always-eat getter; the eat reflex
     * only fires while hungry, so the golden-apple-at-full edge is
     * out of scope).
     *
     * @return true when an item was consumed
     */
    public boolean eatHeldItem() {
        ItemStack held = inventory.container().getItem(selectedSlot);
        var props = held.getFoodProperties(this);
        if (props == null || !foodData.needsFood()) {
            return false;
        }
        ItemStack remainder = held.finishUsingItem(level(), this);
        foodData.eat(held.getItem(), held, this);
        inventory.container().setItem(selectedSlot, remainder);
        return true;
    }

    /**
     * Resolves a held jump drive flag against the carrier's footing:
     * lava ascent, water ascent, or grounded hop. Each case calls the
     * engine routine directly - see the per-case notes for the probes
     * that killed the subtler vanilla-flag routes.
     */
    private void applyDriveJump() {
        if (driveJump && isInLava() && !onGround()) {
            // Lava ascent: same direct-call deviation as the water branch
            // below — setJumping(true) produced zero vertical velocity on
            // this carrier (issue 0004 F2, same anomaly class). Mob.jumpInFluid
            // with LAVA_TYPE is the exact call the vanilla aiStep fluid branch
            // makes for lava. Lava is more viscous than water so the ascent
            // is slower, but a held flag keeps applying thrust each tick the
            // way MinimalReflex intends while crashed in lava.
            jumpInFluid(net.minecraftforge.common.ForgeMod.LAVA_TYPE.get());
        } else if (driveJump && isInWater() && !onGround()) {
            // Fluid ascent with no floor to push from: call the
            // engine's fluid jump directly. Runtime probes killed the
            // subtler route - writing setJumping(true) lets the vanilla
            // aiStep fluid branch read the flag but produced ZERO
            // vertical velocity on this carrier, the same anomaly as
            // the land case below (deep-pool gametest position trace,
            // issue 0004 F2). Mob.jumpInFluid is the exact call that
            // branch makes; invoking it here is the same class of
            // deviation as jumpFromGround. +0.3/tick while held,
            // matching a player holding space to surface.
            jumpInFluid(net.minecraftforge.common.ForgeMod.WATER_TYPE.get());
        } else if (driveJump && onGround()) {
            // Grounded (dry OR wading): fire jumpFromGround directly.
            // Two deliberate deviations live in this branch. First,
            // the vanilla flag path never lifted this body on LAND
            // (field verified true at write time, zero vertical
            // velocity ever applied - traced by the gauntlet jump
            // probe). Second, the flag route must NOT be used while
            // grounded in shallow water: aiStep throttles it with
            // noJumpDelay=10, and the slow hops stalled the trench
            // exit that per-tick direct jumps clear. The one-shot
            // reset keeps a held flag from machine-gunning jumps;
            // steering re-raises it each tick while the JumpUp
            // waypoint is still above us.
            jumpFromGround();
            driveJump = false;
        }
    }

    /**
     * Carrier-side presence: monsters that can see this body treat it
     * as a player-shaped target. Deliberately NOT an Intent and not
     * on the Actor - being visible is a property of the body in the
     * world (same category as its collision box or its fire ticks),
     * not a decision any brain tier makes; the crashed latch keeps it
     * running for exactly that reason (a crashed body still gets
     * eaten). Only acquires targets that are currently unoccupied,
     * skips no-AI mobs (a frozen mob must stay frozen), and honors
     * each monster's own follow range plus a line-of-sight ray, so a
     * wall between the body and a monster still hides it.
     */
    private void tickPresence() {
        if (--presenceCooldown > 0) {
            return;
        }
        presenceCooldown = PRESENCE_SCAN_INTERVAL_TICKS;
        for (net.minecraft.world.entity.monster.Monster monster : level().getEntitiesOfClass(
                        net.minecraft.world.entity.monster.Monster.class,
                        getBoundingBox().inflate(PRESENCE_SCAN_HALF_WIDTH),
                        m -> m.isAlive() && !m.isNoAi() && m.getTarget() == null)) {
            double follow = monster.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
            if (monster.distanceToSqr(this) > follow * follow) {
                continue;
            }
            if (!monster.getSensing().hasLineOfSight(this)) {
                continue;
            }
            monster.setTarget(this);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /**
     * Equipment mirror (Phase 4 combat/wear slices): the container is
     * the source of truth, the vanilla equipment slots are a mirror
     * over it. {@code LivingEntity.detectEquipmentUpdates} diffs
     * {@code getItemBySlot} every tick and applies the item's
     * attribute modifiers for exactly this view - a held sword raises
     * ATTACK_DAMAGE and worn armor raises ARMOR with zero extra
     * wiring, and the mob model renders held/worn items for free.
     *
     * @param slot the vanilla equipment slot; never null
     * @return the container stack mapped to that slot; never null
     */
    @Override
    public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        var container = inventory.container();
        return switch (slot) {
            case MAINHAND -> container.getItem(selectedSlot);
            case OFFHAND -> container.getItem(BindingInventory.OFFHAND_SLOT);
            case HEAD -> container.getItem(BindingInventory.ARMOR_START);
            case CHEST -> container.getItem(BindingInventory.ARMOR_START + 1);
            case LEGS -> container.getItem(BindingInventory.ARMOR_START + 2);
            case FEET -> container.getItem(BindingInventory.ARMOR_START + 3);
        };
    }

    /**
     * Write-through keeping the equipment mirror symmetric: vanilla
     * writers (if any ever run) land in the container instead of the
     * dead {@code handItems}/{@code armorItems} lists. Equip sounds
     * ride the menu-transaction path, so {@code onEquipItem} is
     * skipped deliberately.
     *
     * @param slot  the vanilla equipment slot; never null
     * @param stack the stack to place; never null
     */
    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot, ItemStack stack) {
        var container = inventory.container();
        switch (slot) {
            case MAINHAND -> container.setItem(selectedSlot, stack);
            case OFFHAND -> container.setItem(BindingInventory.OFFHAND_SLOT, stack);
            case HEAD -> container.setItem(BindingInventory.ARMOR_START, stack);
            case CHEST -> container.setItem(BindingInventory.ARMOR_START + 1, stack);
            case LEGS -> container.setItem(BindingInventory.ARMOR_START + 2, stack);
            case FEET -> container.setItem(BindingInventory.ARMOR_START + 3, stack);
        }
    }
}
