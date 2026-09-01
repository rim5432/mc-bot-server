package com.mcbot.mcbotserver.adapter.entity;

import com.mcbot.mcbotserver.adapter.BindingActor;
import com.mcbot.mcbotserver.adapter.BotPlayerFacade;
import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.BowlFoodItem;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.SuspiciousStewItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The physical body: a vanilla-pathfinding-capable mob whose locomotion
 * is driven exclusively by per-tick input writes from the binding.
 * Vanilla {@code travel()} is the only thing that moves this body
 * — boundary A holds because every write below is an intent echo of an
 * Actor claim, never self-computed physics.
 *
 * <p>Player parity is split (issue 0007 Path A): entity-scan
 * player-ness — hostile targeting, closed by the carrier-side
 * presence pass below — versus menu/interaction player-ness, which
 * lives in {@link BotPlayerFacade} (typed Player parameters, never
 * entity scans).
 *
 * <p>The three vanilla AI controls (move/look/jump) are swapped for
 * no-ops in the constructor — they tick after the binding's write
 * point and clobber shared state. The probe evidence, the pitch-aim
 * landmine, and the Stage 2 revisit condition live in the constructor
 * comment; the swap must be revisited as a whole, never re-enabled
 * one control at a time.
 *
 * <p>Implementation note: constructed and ticked by the server only.
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
     * Position at the previous customServerAiStep call. Used for
     * movement-exhaustion distance measurement because
     * customServerAiStep runs BEFORE LivingEntity.travel() in the
     * aiStep() chain (decompiled 1.20.1 LivingEntity.aiStep line 2609
     * serverAiStep, line 2662 travel), so the engine has not moved the
     * body yet this tick and getX()-xo is always zero here. The delta
     * against the previous call measures the previous tick's travel —
     * exactly what vanilla Player.checkMovementStatistics consumes (it
     * too runs on the previous tick's displacement). Tracked manually
     * for that reason.
     */
    private double lastTickX;

    private double lastTickZ;
    private boolean hasLastTickPos;

    /**
     * Set by {@code consumeDrink} when the recovered container (glass
     * bottle / bucket) could not fit in inventory and was dropped at the
     * body's location instead. Read by {@link BindingActor} after a
     * drink to attach the {@code containerDropped} attr to
     * DRINK_COMPLETED. Single-threaded tick pipeline makes this safe
     * without synchronization.
     */
    private boolean lastContainerDropped;

    /**
     * Experience state (level/progress/total) with the vanilla
     * {@code Player.experience*} arithmetic, in the same
     * state-vs-math split as {@link HungerTicker}: the carrier owns
     * the state, the mirror owns the math.
     */
    private final ExperienceMirror experience = new ExperienceMirror();

    /**
     * Lazy-initialized Player facade for kill-XP attribution. Vanilla
     * mobs award XP orbs in die() only when lastHurtByPlayerTime > 0,
     * which is set by LivingEntity.setLastHurtByPlayer(Player). The bot
     * is a PathfinderMob and cannot be that Player directly, so the
     * facade fills the typed slot — it is never spawned, never moves,
     * and die() only reads the timestamp, not any facade method. Created
     * on first melee hit to avoid paying the menu-bridge construction
     * cost for a bot that never fights.
     */
    private BotPlayerFacade playerFacade;

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
     * Movement exhaustion rates (Player.checkMovementStatistics, vanilla
     * 1.20.1). Sprint is 0.1/block (parity); walk is a recorded
     * deviation at 0.01/block (the vanilla swim rate, not vanilla's
     * zero walk rate) — a non-zero baseline so a purely-walking mob
     * carrier still drains hunger over time. See player-behavior-RE.md
     * section 1 and the hunger.movement_exhaustion capability face.
     * Future movement kinds (swim, jump, sneak) should add their own
     * constants here rather than inline literals.
     */
    private static final float SPRINT_EXHAUSTION_PER_BLOCK = 0.1f;

    private static final float WALK_EXHAUSTION_PER_BLOCK = 0.01f;

    /**
     * Downward velocity applied while sneaking in water. Vanilla
     * Player.travel interprets shift-held-in-water as descent;
     * LivingEntity.travel (the carrier's superclass) does not, so the
     * binding applies it directly. -0.10/tick before the water drag
     * (0.8x) and gravity (-0.005) in travel() yields a net ~-0.085/tick
     * descent, comparable to a player holding shift at the surface.
     */
    private static final float WATER_DESCENT_SPEED = -0.10f;

    /**
     * The body's hunger state (Phase 4 eat slice, issue 0010): a
     * vanilla {@link net.minecraft.world.food.FoodData} owned and
     * ticked by the carrier - Path A facade (issue 0007).
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
        // clobber the binding's writes (empirically confirmed via
        // body-probe, do not re-derive from memory — Mob.aiStep() runs
        // goalSelector.tick(), navigation.tick(), customServerAiStep()
        // (our write point), THEN the three control ticks):
        //   - MoveControl.tick() WAIT branch: setZza(0) every tick
        //   - LookControl.tick(): resetXRotOnTick() forces setXRot(0),
        //     wiping any non-zero pitch the binding set (pitch-aim landmine)
        //   - JumpControl.tick(): setJumping(false) every tick, would
        //     zero a setJumping(true) before LivingEntity.aiStep() sees it
        // All three are Goal-system executors; with no goals registered and
        // the binding owning every input channel, they are concurrent writers
        // on shared state, not helpers. Anonymous no-ops match the existing
        // MoveControl pattern.
        // Stage 2 landmine: if A* or follow goals ever need vanilla pathing
        // again, all three swaps must be revisited as a whole (drive through
        // the controls instead of around them), not patched by re-enabling
        // one beside the binding.
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
     * reduction) - both entity-level, no player required. The walk-off-
     * the-edge guard is implemented via {@link #maybeBackOffFromEdge}
     * (a verbatim clone of Player.maybeBackOffFromEdge), so a sneaking
     * body stops at ledges exactly like a player - the old "base Entity
     * maybeBackOffFromEdge is a stub" gap is closed.
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
        // Movement exhaustion: nothing on a mob accumulates it
        // naturally, so the carrier feeds it from actual horizontal
        // distance. The rates and the walk-rate deviation rationale
        // live on the SPRINT/WALK_EXHAUSTION_PER_BLOCK constants; why
        // the delta is measured manually against lastTickX/Z (xo still
        // equals the current position at this point in the aiStep
        // chain) lives on the lastTickX field.
        if (hasLastTickPos) {
            float movedHorizontally = (float) Math.hypot(getX() - lastTickX, getZ() - lastTickZ);
            foodData.addExhaustion(
                    movedHorizontally * (isSprinting() ? SPRINT_EXHAUSTION_PER_BLOCK : WALK_EXHAUSTION_PER_BLOCK));
        }
        lastTickX = getX();
        lastTickZ = getZ();
        hasLastTickPos = true;
        applyDriveJump();
        applyWaterDescent();
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
        tickXpPickup();
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
     * <p>Container retention (the non-obvious part): BowlFoodItem and
     * SuspiciousStewItem always return a bowl from {@code finishUsingItem}
     * for non-creative callers, even when the original stack had count{@code >}1
     * — the shrunk remainder is silently lost if the caller replaces the
     * slot with the returned bowl. HoneyBottleItem returns the shrunk stack
     * for count{@code >}1 but only adds the glass bottle to {@code Player}
     * inventories (the bot is a Mob, so the bottle is lost). This method
     * implements the vanilla Player resolution: count 1 replaces the slot
     * with the container; count {@code > 1} keeps the shrunk stack in the slot
     * and routes the container to inventory (or drops it if full).
     *
     * <p>Always-edible bypass (golden apples, enchanted golden apples):
     * {@code FoodProperties.canAlwaysEat()} bypasses the {@code needsFood()}
     * gate, matching vanilla {@code Item.use} — a player at full hunger can
     * still eat a golden apple for its absorption/regeneration effects. The
     * reflex layer ({@code EatWhenHungryRule}) does not fire above its
     * trigger, so always-edible food at full hunger is harness-driven
     * (strategic use before combat), not reflex-driven.
     *
     * @return true when an item was consumed
     */
    public boolean eatHeldItem() {
        ItemStack held = inventory.container().getItem(selectedSlot);
        var props = held.getFoodProperties(this);
        if (props == null || (!foodData.needsFood() && !props.canAlwaysEat())) {
            return false;
        }
        // Capture nutrition BEFORE finishUsingItem: it shrinks the stack in
        // place, and Forge's contexted eat re-reads properties from the
        // shrunk stack (null for air) — the whole meal would apply zero food.
        int nutrition = props.getNutrition();
        float saturation = props.getSaturationModifier();
        // Determine the container item BEFORE finishUsingItem mutates the
        // stack. BowlFoodItem/SuspiciousStewItem -> bowl; HoneyBottleItem ->
        // glass bottle; everything else -> no container.
        Item containerItem = containerItemFor(held.getItem());
        ItemStack remainder = held.finishUsingItem(level(), this);
        foodData.eat(nutrition, saturation);
        if (containerItem != null) {
            if (held.isEmpty()) {
                // Count was 1: finishUsingItem returned the container; the
                // slot becomes the container (bowl / glass bottle).
                inventory.container().setItem(selectedSlot, remainder);
            } else {
                // Count was >1: finishUsingItem shrank held to count-1. Keep
                // the shrunk stack in the slot and route the container to
                // inventory (BowlFoodItem/SuspiciousStewItem return the bowl
                // as remainder; HoneyBottleItem returns the shrunk stack, so
                // we synthesize the bottle here).
                inventory.container().setItem(selectedSlot, held);
                ItemStack container = remainder.getItem() == containerItem ? remainder : new ItemStack(containerItem);
                addOrDrop(container);
            }
        } else {
            // Non-container food: remainder is the shrunk stack.
            inventory.container().setItem(selectedSlot, remainder);
        }
        return true;
    }

    /**
     * Drink the held potion (consumable-face Phase 1): the vanilla
     * mob-safe chain — {@code finishUsingItem} applies the potion
     * effects and fires the DRINK game event, but does NOT shrink the
     * stack or recover a glass bottle for non-Player callers
     * ({@code PotionItem.finishUsingItem} lines 60-75 are
     * Player-only). The vanilla Player resolution — manual shrink plus
     * glass-bottle recovery — is shared with milk in
     * {@link #consumeDrink(Item)}.
     *
     * <p>No hunger gate: potions are drinkable at any time, matching
     * vanilla {@code PotionItem.use} (calls {@code startUsingInstantly}
     * with no {@code canEat} check). The only food category sharing
     * this property is always-edible ({@code FoodProperties
     * .canAlwaysEat}); normal food is gated by {@code needsFood()}.
     * The caller ({@code BindingActor.onUsePressEdge}) captures the
     * effect list BEFORE this method so DRINK_COMPLETED can report
     * what was applied.
     *
     * @return true when a potion was consumed
     */
    public boolean drinkHeldItem() {
        ItemStack held = inventory.container().getItem(selectedSlot);
        if (!(held.getItem() instanceof PotionItem)) {
            return false;
        }
        // finishUsingItem applies effects but does not shrink for Mob
        // callers — the Player-only shrink in PotionItem.finishUsingItem
        // line 63 is skipped, so consumeDrink shrinks manually below.
        held.finishUsingItem(level(), this);
        consumeDrink(Items.GLASS_BOTTLE);
        return true;
    }

    /**
     * Drink the held milk bucket (consumable-face Phase 2): the vanilla
     * mob-safe chain — {@code finishUsingItem} calls {@code
     * curePotionEffects} to clear all curable effects and fires the
     * DRINK game event, but does NOT shrink the stack or return a
     * bucket for non-Player callers ({@code MilkBucketItem
     * .finishUsingItem} lines 34-36 are Player-only). The vanilla
     * Player resolution — manual shrink plus bucket recovery — is
     * shared with potions in {@link #consumeDrink(Item)}.
     *
     * <p>Effect-clearing semantics: {@code curePotionEffects} removes
     * all curable {@code MobEffectInstance}s (the default for most
     * effects; effects marked non-curable in their constructor
     * survive). The caller captures the active effect list BEFORE this
     * method so DRINK_COMPLETED can report what was cleared.
     *
     * @return true when a milk bucket was consumed
     */
    public boolean drinkMilk() {
        ItemStack held = inventory.container().getItem(selectedSlot);
        if (!(held.getItem() instanceof MilkBucketItem)) {
            return false;
        }
        // finishUsingItem cures effects but does not shrink for Mob callers
        // — consumeDrink handles the manual shrink and bucket recovery.
        held.finishUsingItem(level(), this);
        consumeDrink(Items.BUCKET);
        return true;
    }

    /**
     * Shared drink-consumption tail: manually shrink the held stack
     * (Mob callers skip the Player-only shrink in
     * PotionItem/MilkBucketItem finishUsingItem) and recover the
     * container — the single home for the container-recovery rules.
     * Count 1 replaces the slot with the container; count
     * {@code > 1} keeps the shrunk stack in the slot and routes the
     * container to inventory via addOrDrop, which merges into an
     * existing same-item stack or takes an empty main-inventory slot
     * and drops it at the body's location when it fits nowhere (see
     * {@link #wasLastContainerDropped()}).
     *
     * <p>The only difference between the drink kinds is the container
     * item (glass bottle vs bucket) and the effect side effect (add
     * vs cure), both handled by the caller before this method runs.
     *
     * @param container the container item left behind (GLASS_BOTTLE /
     *                  BUCKET); never null
     */
    private void consumeDrink(Item container) {
        ItemStack held = inventory.container().getItem(selectedSlot);
        held.shrink(1);
        if (held.isEmpty()) {
            // Count was 1: slot becomes the container.
            inventory.container().setItem(selectedSlot, new ItemStack(container));
            lastContainerDropped = false;
        } else {
            // Count was > 1: keep the shrunk stack in the slot and route
            // the container to inventory (or drop if full).
            inventory.container().setItem(selectedSlot, held);
            lastContainerDropped = !addOrDrop(new ItemStack(container));
        }
    }

    /**
     * Whether the most recent {@code consumeDrink} call dropped the
     * recovered container (glass bottle / bucket) because inventory
     * was full. Read by {@link BindingActor} to attach the
     * {@code containerDropped} attr to DRINK_COMPLETED.
     *
     * @return true if the container was dropped, false if it was added
     *         to inventory or placed in the selected slot (count-1 case)
     */
    public boolean wasLastContainerDropped() {
        return lastContainerDropped;
    }

    /**
     * Throw the held splash or lingering potion (consumable-face P2-c):
     * the vanilla ThrowableItem path — spawn a {@code ThrownPotion} entity
     * with the bot's rotation, shoot it with the vanilla -20deg pitch
     * offset and 0.5 velocity, and shrink the held stack. Unlike {@code
     * drinkHeldItem}, this does NOT call {@code finishUsingItem}: the
     * projectile carries the item stack and applies effects on impact
     * (splash AoE / lingering AreaEffectCloud), resolved entirely by the
     * {@code ThrownPotion} entity.
     *
     * <p>Container recovery: thrown potions leave no container (unlike
     * drinkable potions which leave a glass bottle) — the stack simply
     * shrinks by 1. Count 1 empties the slot; count {@code > 1} keeps the
     * shrunk stack.
     *
     * <p>Sound: splash uses {@code SPLASH_POTION_THROW}, lingering uses
     * {@code LINGERING_POTION_THROW} — same pitch randomization as vanilla
     * {@code ThrowableItem.use} (0.4F / (random * 0.4F + 0.8F)).
     *
     * @return true when a splash/lingering potion was thrown
     */
    public boolean throwHeldPotion() {
        ItemStack held = inventory.container().getItem(selectedSlot);
        boolean isSplash = held.getItem() instanceof SplashPotionItem;
        boolean isLingering = held.getItem() instanceof LingeringPotionItem;
        if (!isSplash && !isLingering) {
            return false;
        }
        // The projectile carries a copy: the original is shrunk below, and
        // sharing the reference would mutate the projectile's stack too.
        ItemStack projectileStack = held.copy();
        ThrownPotion potion = new ThrownPotion(level(), this);
        potion.setItem(projectileStack);
        // Vanilla ThrowableItem.use parameters: -20deg pitch offset, 0.5
        // velocity, 1.0 inaccuracy.
        potion.shootFromRotation(this, getXRot(), getYRot(), -20.0F, 0.5F, 1.0F);
        level().addFreshEntity(potion);
        SoundEvent sound = isLingering ? SoundEvents.LINGERING_POTION_THROW : SoundEvents.SPLASH_POTION_THROW;
        level().playSound(
                        null,
                        getX(),
                        getY(),
                        getZ(),
                        sound,
                        SoundSource.PLAYERS,
                        0.5F,
                        0.4F / (level().getRandom().nextFloat() * 0.4F + 0.8F));
        // Shrink: count 1 empties the slot; count > 1 keeps the shrunk stack.
        held.shrink(1);
        if (held.isEmpty()) {
            inventory.container().setItem(selectedSlot, ItemStack.EMPTY);
        } else {
            inventory.container().setItem(selectedSlot, held);
        }
        return true;
    }

    /**
     * Resolve the container item a food type leaves behind, or null for
     * foods with no container. Extracted so the eat path stays linear and
     * the mapping is auditable in one place.
     *
     * @param item the food item being eaten; never null
     * @return the container item (Items.BOWL / Items.GLASS_BOTTLE), or null
     */
    private static Item containerItemFor(Item item) {
        if (item instanceof BowlFoodItem || item instanceof SuspiciousStewItem) {
            return Items.BOWL;
        }
        if (item instanceof HoneyBottleItem) {
            return Items.GLASS_BOTTLE;
        }
        return null;
    }

    /**
     * Route a recovered container (glass bottle / bucket / bowl) to
     * inventory, mirroring vanilla {@code Inventory.placeItemBackInInventory}
     * (decompiled 1.20.1 Inventory.java lines 304-325):
     *
     * <ol>
     *   <li><b>Merge</b> into an existing same-item stack, in vanilla
     *       {@code getSlotWithRemainingSpace} order: selected hotbar
     *       slot, then offhand (slot 40), then main inventory 0..35.
     *       Condition mirrors {@code hasRemainingSpaceForItem}:
     *       non-empty, same item+tags, stackable, below max stack size.</li>
     *   <li><b>Empty slot</b> in main inventory 0..35 only — vanilla
     *       {@code getFreeSlot} scans only {@code this.items}; armor
     *       slots 36..39 are NEVER destinations for item pickup.</li>
     *   <li><b>Drop</b> at the body's location if neither phase finds
     *       a slot (vanilla {@code player.drop}).</li>
     * </ol>
     *
     * <p>The previous implementation scanned all 41 slots for empties
     * and never merged: a count-2 potion drunk with a glass bottle
     * already in inventory would drop the second bottle and report
     * containerDropped=true, while vanilla merges it into the existing
     * stack. This rewrite closes that fidelity gap.
     *
     * @param stack the single-item container stack to route; never null
     * @return true if merged or placed in inventory, false if dropped
     */
    private boolean addOrDrop(ItemStack stack) {
        var container = inventory.container();
        // Phase 1: merge into existing same-item stack.
        int mergeSlot = -1;
        if (canMergeInto(container.getItem(selectedSlot), stack)) {
            mergeSlot = selectedSlot;
        } else if (canMergeInto(container.getItem(BindingInventory.OFFHAND_SLOT), stack)) {
            mergeSlot = BindingInventory.OFFHAND_SLOT;
        } else {
            for (int i = 0; i < BindingInventory.ARMOR_START; i++) {
                if (canMergeInto(container.getItem(i), stack)) {
                    mergeSlot = i;
                    break;
                }
            }
        }
        if (mergeSlot >= 0) {
            ItemStack existing = container.getItem(mergeSlot);
            existing.grow(1);
            container.setItem(mergeSlot, existing);
            return true;
        }
        // Phase 2: empty slot in main inventory only (armor never receives).
        for (int i = 0; i < BindingInventory.ARMOR_START; i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, stack);
                return true;
            }
        }
        // Phase 3: drop at body location.
        spawnAtLocation(stack);
        return false;
    }

    /**
     * Whether the existing slot can accept the incoming stack via
     * merging. Mirrors vanilla {@code Inventory.hasRemainingSpaceForItem}
     * (decompiled 1.20.1 lines 57-63): non-empty, same item+tags,
     * stackable, and below the item's max stack size.
     *
     * @param existing the slot's current stack; never null
     * @param incoming the stack to merge; never null
     * @return true if the incoming stack can merge into the existing slot
     */
    private static boolean canMergeInto(ItemStack existing, ItemStack incoming) {
        return !existing.isEmpty()
                && ItemStack.isSameItemSameTags(existing, incoming)
                && existing.isStackable()
                && existing.getCount() < existing.getMaxStackSize();
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
     * Water descent: shift-held-in-water maps to downward thrust.
     * Vanilla Player.travel handles this natively; LivingEntity.travel
     * (the carrier's superclass) does not — the water section has no
     * shift-key branch (verified against decompiled 1.20.1
     * LivingEntity.java lines 2086-2125). So the binding applies the
     * descent directly, the same class of deviation as jumpInFluid for
     * ascent. Gated on !driveJump so an active ascent claim wins over a
     * stale sneak flag (the pathing layer never submits both, but the
     * harness latch can).
     */
    private void applyWaterDescent() {
        if (!driveJump && isShiftKeyDown() && isInWater() && !onGround()) {
            setDeltaMovement(
                    getDeltaMovement().x(),
                    WATER_DESCENT_SPEED,
                    getDeltaMovement().z());
        }
    }

    /**
     * Sneak edge guard: delegates to {@link SneakEdgeGuard}, the
     * verbatim clone of Player.maybeBackOffFromEdge (decompiled
     * 1.20.1 Player.java lines 1116-1168) with its isAboveGround
     * gate - see there for the conditions and the three-pass
     * algorithm. The base Entity implementation is a no-op (returns
     * the movement vector unchanged), so without this override a
     * sneaking bot walks straight off ledges.
     *
     * @param movement the intended movement vector; never null
     * @param moverType the mover type; never null
     * @return the edge-clamped movement vector; never null
     */
    @Override
    protected Vec3 maybeBackOffFromEdge(Vec3 movement, MoverType moverType) {
        return SneakEdgeGuard.clamp(this, movement, moverType);
    }

    // ------------------------------------------------------------------
    // Experience (vanilla Player.experience* mirror)
    // ------------------------------------------------------------------

    /**
     * The bot's current experience level. Mirrors Player.experienceLevel.
     *
     * @return experience level; never negative
     */
    public int getExperienceLevel() {
        return experience.getLevel();
    }

    /**
     * Progress toward the next level, 0.0..1.0. Mirrors
     * Player.experienceProgress.
     *
     * @return progress fraction; 0.0..1.0
     */
    public float getExperienceProgress() {
        return experience.getProgress();
    }

    /**
     * Total experience points ever absorbed. Mirrors Player.totalExperience.
     *
     * @return total XP; never negative
     */
    public int getTotalExperience() {
        return experience.getTotal();
    }

    /**
     * XP required to go from the current level to the next. Verbatim
     * vanilla formula (decompiled Player.java getXpNeededForNextLevel):
     * level >= 30: 112 + (level-30)*9; level >= 15: 37 + (level-15)*5;
     * else: 7 + level*2.
     *
     * @return XP points needed for the next level
     */
    public int getXpNeededForNextLevel() {
        return experience.getXpNeededForNextLevel();
    }

    /**
     * Add experience points, leveling up as needed. Mirrors
     * Player.giveExperiencePoints (decompiled lines 1718-1743): clamps
     * to int, adds to total, fills the progress bar, and rolls overflow
     * into level-ups. Negative values subtract (used by anvil/enchant
     * costs).
     *
     * @param points XP to add (or subtract if negative)
     */
    public void giveExperiencePoints(int points) {
        experience.addPoints(points);
    }

    /**
     * Add (or subtract) whole levels. Mirrors Player.giveExperienceLevels.
     *
     * @param levels levels to add (or subtract if negative)
     */
    public void giveExperienceLevels(int levels) {
        experience.addLevels(levels);
    }

    /**
     * Lazy Player facade for kill-XP attribution. See {@link #playerFacade}
     * field javadoc for why this exists. Created once and cached; the
     * facade is never spawned and carries no world state.
     *
     * @return the cached facade; never null after first call
     */
    public BotPlayerFacade playerFacade() {
        if (playerFacade == null) {
            playerFacade = new BotPlayerFacade(this);
        }
        return playerFacade;
    }

    /**
     * Absorb nearby experience orbs. Vanilla XP orbs fly toward players
     * and call player.giveExperiencePoints on contact; a PathfinderMob
     * carrier is invisible to that attraction, so the bot scans manually.
     *
     * <p>Range is inflated 1.5/1.0/1.5 — wider than GroundPickup's
     * 1.0/0.5/1.0 because XP orbs spawn at block centers (e.g. grindstone
     * onTake awards at Vec3.atCenterOf), one full block away from the bot
     * standing adjacent. At 1.0 inflation the orb sits 0.2 blocks outside
     * the scan box and is randomly missed depending on which face the block
     * collision pushes it toward. 1.5 covers the adjacent-block center plus
     * a small margin for post-spawn drift. XP orbs have no pickup delay
     * (unlike ItemEntity), so every orb in range is absorbed immediately.
     *
     * <p>Called from customServerAiStep every tick; the scan is cheap
     * (entity class filter on a small box) and the orbs despawn on their
     * own if never collected.
     */
    private void tickXpPickup() {
        var box = getBoundingBox().inflate(1.5, 1.0, 1.5);
        for (net.minecraft.world.entity.ExperienceOrb orb :
                level().getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) {
            if (orb.isAlive() && orb.value > 0) {
                experience.addPoints(orb.value);
                orb.discard();
            }
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

    /**
     * Never despawn: the body is a registered, externally-driven bot,
     * not ambient wildlife — its lifetime is owned by the session that
     * created it, not by chunk distance. Pairs with
     * {@code setPersistenceRequired()} in the constructor.
     */
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
