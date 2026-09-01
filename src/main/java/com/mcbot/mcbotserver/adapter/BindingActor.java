package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.menu.MenuTransactions;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BowlFoodItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.SuspiciousStewItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Write half of the entity binding: resolves per-tick claims and
 * echoes the winners into the body's input fields. Vanilla physics
 * does the rest.
 *
 * <p>Contract: see ADR-0004 D2 and boundaries.md section A. An unwon
 * MOVE channel means halt — a body with no client must be told to
 * stand still every tick (numen-notes.md section 4). USE swings on the
 * rising press edge; SLOT writes the hotbar selection directly.
 * Menu transactions (issue 0007 §6.2, ledger 29) ride the same
 * binding as imperative request-response methods, composed inside
 * this actor as {@link ActorMenuTransactions} — still one boundary-A
 * implementer aggregate per body.
 *
 * <p>Implementation note: server tick thread only; flush is called
 * exactly once per tick by the pipeline's stage 4.
 */
// contract: see ADR-0004 D2 (four channels, per-tick expiring claims)
public final class BindingActor implements Actor {

    private final ChannelArbiter delegate = new ChannelArbiter();
    private final BotBodyEntity body;
    /** USE-press melee resolution (cone, reach, LOS, lava). */
    private final MeleeResolver melee;
    /** Cosmetic expression: idle look/sweep + walk fidget (0005). */
    private final PresenceLayer presence;
    /** Held-dig execution: destroy progress + the break (0009). */
    private final DigExecutor dig;
    /** One-shot right-click chain from the selected hotbar slot (0007). */
    private final InteractBlockExecutor interact;

    /**
     * The one-shot place executor for the synchronous /bot place verb
     * (issue 0013 R2). Public for the McBotServer wiring (root
     * package); adapter-internal semantics - a rising-edge instant
     * action, safe to call between ticks like the menu transactions.
     *
     * @return the executor; never null
     */
    public InteractBlockExecutor interactExecutor() {
        return interact;
    }

    /**
     * Sleep at the target bed - the synchronous /bot sleep verb's body.
     * The device completes the vanilla all-sleepers rule the engine
     * cannot see: ServerLevel skips the night only when every PLAYER
     * is sleeping, and the carrier is a PathfinderMob outside that
     * list, so after the bed verifies (real bed, in reach, nighttime)
     * the body's sleep is completed by advancing the clock to the next
     * morning - the same arithmetic vanilla's sleep-finish applies.
     * Deliberate v1 gaps, both recorded: no monster-proximity refusal
     * (the harness can scan /entities first) and no spawn-point
     * binding (body death does not run the player respawn flow).
     *
     * @param target the bed cell to sleep at; never null
     * @return null on success, or the machine-readable refusal reason
     */
    public String sleepAt(CellPos target) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) body.level();
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(target.x(), target.y(), target.z());
        if (!level.isLoaded(pos)) {
            return "chunk not loaded";
        }
        if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return "not a bed";
        }
        if (!ReachPolicy.withinReach(body.getEyePosition(), pos)) {
            return "out of reach";
        }
        if (level.isDay()) {
            return "already daytime";
        }
        long time = level.getDayTime();
        level.setDayTime(time + 24000L - (time % 24000L));
        return null;
    }

    /**
     * Vanilla air-use for the held item - the synchronous /bot use-item
     * verb's body. Items whose use is a hold (bow draw, shield raise)
     * and food are rejected here: they ride the USE channel (combat
     * draw pacing, EAT reflex) and a verb that starts a hold with
     * nobody releasing it would orphan the state.
     *
     * @return true when the held item's use consumed the click
     */
    public boolean useHeldItemAir() {
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        if (held.isEmpty()
                || held.getItem() instanceof BowItem
                || held.getItem() instanceof ShieldItem
                || held.getFoodProperties(body) != null) {
            return false;
        }
        return useHeldAirStack();
    }

    /**
     * Resolves one vanilla air-use for the main-hand stack: syncs the
     * facade pose, fires {@code Item.use}, writes the mutated result
     * stack back to the selected hotbar slot and swings. The write-back
     * is the load-bearing half - {@code ItemStack.use} returns the
     * mutated stack but does not write it through the player inventory
     * (the facade has no ServerPlayer inventory-write path), so a
     * caller that drops the result loses the emptied/filled bucket.
     *
     * @return true when the use consumed the click
     */
    private boolean useHeldAirStack() {
        ItemStack heldStack = body.getInventory().container().getItem(body.selectedSlot);
        facade.syncPosition();
        var useResult = heldStack.use(body.level(), facade, InteractionHand.MAIN_HAND);
        if (useResult.getResult().consumesAction()) {
            body.getInventory().container().setItem(body.selectedSlot, useResult.getObject());
            body.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }
    /** The menu-transaction aggregate over the shared facade (ledger 29). */
    private final ActorMenuTransactions menuTx;

    /** The one Player-typed acting surface (use chain, use loop, menus). */
    private final BotPlayerFacade facade;

    private boolean lastUsePressing;
    private boolean lastDropClaimed;
    private boolean lastInteractClaimed;

    /** Boundary-D event sink for eat lifecycle disclosure (ledger 34). */
    private final EventQueue events;

    /** Game-day supplier for event stamps; never null. */
    private final LongSupplier daySupplier;

    /** Time-of-day ticks supplier for event stamps; never null. */
    private final LongSupplier todSupplier;

    /**
     * Creates an actor bound to one body.
     *
     * @param body         the physical carrier; never null
     * @param events       boundary-D event sink for eat lifecycle; never
     *                     null
     * @param daySupplier  game-day stamp source; never null
     * @param todSupplier  time-of-day tick stamp source; never null
     */
    public BindingActor(BotBodyEntity body, EventQueue events, LongSupplier daySupplier, LongSupplier todSupplier) {
        this.body = Objects.requireNonNull(body, "body");
        this.melee = new MeleeResolver(body);
        this.presence = new PresenceLayer(body);
        this.dig = new DigExecutor(body);
        // One facade per body serves the right-click chain, the use
        // loop and the menu transactions - extra instances would fork
        // the containerMenu state the MenuOpener owns.
        this.facade = new BotPlayerFacade(body);
        this.interact = new InteractBlockExecutor(body, facade);
        this.menuTx = new ActorMenuTransactions(facade);
        this.events = Objects.requireNonNull(events, "events");
        this.daySupplier = Objects.requireNonNull(daySupplier, "daySupplier");
        this.todSupplier = Objects.requireNonNull(todSupplier, "todSupplier");
    }

    /**
     * The binding's menu-transaction surface (boundary-A ledger 29).
     *
     * @return the imperative menu surface sharing this actor's
     *         facade; never null
     */
    public MenuTransactions menuTransactions() {
        return menuTx;
    }

    @Override
    public void submit(Claim claim) {
        delegate.submit(claim);
    }

    @Override
    public Map<Channel, Claim> flush() {
        Map<Channel, Claim> winners = delegate.flush();
        applyMove(winners.get(Channel.MOVE));
        applyRot(winners.get(Channel.ROT));
        applyUse(winners.get(Channel.USE));
        applySlot(winners.get(Channel.SLOT));
        applyInteract(winners.get(Channel.INTERACT));
        presence.tickWalkFidget(winners, winners.get(Channel.MOVE), winners.get(Channel.ROT));
        return winners;
    }

    private void applyMove(Claim move) {
        if (move != null && move.intent() instanceof Intent.Move m) {
            body.setDrive((float) clamp(m.forward()), (float) clamp(m.strafe()), m.jump(), m.sneak());
            // Sprint gait (issue 0005 P1.1, issue 0004 F6(3) ruling
            // D4): adapter-local policy, no Intent field. Forward
            // stride plus straight clearance ahead. Suppress in fluid
            // - setSprinting in water is the swim boost and would
            // silently change crossing speed (F6(1) surface-lane
            // semantics).
            body.setSprinting(sprintClearAhead(m));
        } else {
            body.setDrive(0f, 0f, false);
            body.setSprinting(false);
        }
    }

    private void applyRot(Claim rot) {
        if (rot != null && rot.intent() instanceof Intent.Look l) {
            body.setTargetRotation(l.yawDeg(), l.pitchDeg());
            // Apply the rotation to the body immediately as well:
            // setTargetRotation only schedules a smoothed transition via
            // customServerAiStep, but applyUse calls facade.syncPosition()
            // in the SAME tick, which reads body.getXRot() (the current,
            // not target, value). Without the immediate setXRot/setYRot,
            // the bucket raycast fires from the stale horizontal pitch and
            // misses the ground — MLG water lands nowhere near the feet.
            body.setYRot(l.yawDeg());
            body.setXRot(l.pitchDeg());
            facade.setYRot(l.yawDeg());
            facade.setXRot(l.pitchDeg());
            presence.onRotClaim();
        } else if (rot == null) {
            // Idle presence (issue 0005 P0.2/P0.3): no behavior owns
            // ROT this tick - between plans, after arrival, reflex
            // freeze. Turn the head toward the nearest player, or
            // sweep when nobody is near (P2.3). Pure presentation: it
            // rides the same setTargetRotation write path as
            // claim-resolved looks and never touches a mission
            // channel. When pathing or combat claims ROT the same
            // tick, that claim wins and this stays silent.
            presence.tickIdleRot();
        }
    }

    private void applyUse(Claim use) {
        if (use != null && use.intent() instanceof Intent.Use u) {
            if (u.pressing() && !lastUsePressing) {
                onUsePressEdge(use.holder());
            }
            if (facade.isUsingItem()) {
                // The draw charge advances only when pumped - the facade
                // is never player-ticked.
                facade.tickUseLoop();
            }
            if (!u.pressing() && lastUsePressing) {
                releaseUseHolds();
            }
            lastUsePressing = u.pressing();
        } else {
            // The USE claim vanished mid-draw (reflex preemption) -
            // release now; an orphaned charge would keep the loop
            // running with nobody watching it.
            releaseUseHolds();
            lastUsePressing = false;
        }
    }

    /**
     * One rising USE edge (decision 14, amended ledger 37 for held
     * items): an edible held item means eat, not swing - vanilla
     * right-click semantics. One item per rising edge; the eat plays
     * its own sound and never melees. A bow starts its draw - the
     * charge accumulates through the held ticks and the falling edge
     * releases the shot. A shield raises on the BODY (never the
     * facade): isDamageSourceBlocked reads the hurt entity's own
     * blocking stack, and the body is what takes the hit.
     */
    @Feature(
            id = "combat.shield.body_blocking",
            face = "combat.shield",
            description = "Shield raises on the BODY (never the facade): isDamageSourceBlocked reads the hurt entity's"
                    + " own blocking stack, and the body is what takes the hit. Rising USE edge starts the hold;"
                    + "falling edge or USE claim loss releases it (orphan protection, same as bow draw). Frontal"
                    + " hemisphere only — rear sources bypass the block via vanilla's direction gate.",
            vanillaRef = "LivingEntity.isDamageSourceBlocked + ShieldItem (decompiled 1.20.1)",
            deviation = "Bot-specific: the facade is a Player-shaped object for attribute/XP machinery, but it never"
                    + " takes damage — the shield must be held on the body that actually receives the hurt call."
                    + "Verified by issue 0003: a facade-raised shield did nothing.")
    private void onUsePressEdge(String owner) {
        String source = owner != null && owner.startsWith(ReflexAction.REFLEX_OWNER_PREFIX) ? "reflex" : "harness";
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        // Reflex intent detection (P2-d): EAT_*/DRINK_* reflex claims carry
        // unambiguous consumable intent. For these, an empty slot or a
        // non-consumable item is a FAILED action, not a silent melee.
        // Generic harness USE claims are ambiguous (could be a weapon swing),
        // so they fall through to the melee fallback without a FAILED event.
        // Prefixes are shared constants in ReflexAction so a rule rename on
        // the production side (ReflexClaimInjector) breaks the consumer side
        // at compile time instead of silently degrading to melee.
        boolean isEatReflex = owner != null && owner.startsWith(ReflexAction.REFLEX_EAT_OWNER_PREFIX);
        boolean isDrinkReflex = owner != null && owner.startsWith(ReflexAction.REFLEX_DRINK_OWNER_PREFIX);
        boolean isConsumableReflex = isEatReflex || isDrinkReflex;
        if (held.isEmpty() && isConsumableReflex) {
            if (isEatReflex) {
                emitEatFailed("SLOT_EMPTY", body.selectedSlot, "", source);
            } else {
                emitDrinkFailed("SLOT_EMPTY", body.selectedSlot, "", source);
            }
            return;
        }
        var props = held.getFoodProperties(body);
        if (props != null) {
            int foodBefore = body.getFoodData().getFoodLevel();
            float satBefore = body.getFoodData().getSaturationLevel();
            int slot = body.selectedSlot;
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            int nutrition = props.getNutrition();
            float satModifier = props.getSaturationModifier();
            // Container type for the EAT_COMPLETED attrs: bowl foods and
            // honey bottles leave a container; normal foods and chorus fruit
            // do not. Captured before eatHeldItem mutates the slot.
            String containerType = containerTypeFor(held.getItem());
            boolean consumed = body.eatHeldItem();
            if (consumed) {
                int foodAfter = body.getFoodData().getFoodLevel();
                float satAfter = body.getFoodData().getSaturationLevel();
                emitEatCompleted(
                        itemId, slot, nutrition, satBefore, satAfter, foodBefore, foodAfter, containerType, source);
                return;
            }
            // Food in hand but not consumed: needsFood() was false (full)
            // or the stack was empty between props read and eatHeldItem.
            emitEatFailed("NOT_HUNGRY", slot, itemId, source);
            return;
        }
        // Splash/lingering potions MUST be checked before PotionItem:
        // ThrowablePotionItem extends PotionItem, so a bare instanceof
        // PotionItem would match them and attempt to drink (finishUsingItem
        // applies nothing for throwable potions — the effects resolve on
        // projectile impact instead).
        if (held.getItem() instanceof SplashPotionItem || held.getItem() instanceof LingeringPotionItem) {
            int slot = body.selectedSlot;
            String potionId =
                    BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(held)).toString();
            String effects = serializeEffects(PotionUtils.getMobEffects(held));
            String throwType = held.getItem() instanceof LingeringPotionItem ? "lingering" : "splash";
            boolean thrown = body.throwHeldPotion();
            if (thrown) {
                emitPotionThrown(potionId, slot, throwType, effects, source);
            }
            return;
        }
        if (held.getItem() instanceof PotionItem) {
            int slot = body.selectedSlot;
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            String potionId =
                    BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(held)).toString();
            String effects = serializeEffects(PotionUtils.getMobEffects(held));
            float health = body.getHealth();
            emitDrinkStarted(potionId, slot, health, source);
            boolean consumed = body.drinkHeldItem();
            if (consumed) {
                emitDrinkCompleted(potionId, slot, effects, "glass_bottle", source);
            } else {
                emitDrinkFailed("NOT_DRINKABLE", slot, itemId, source);
            }
            return;
        }
        if (held.getItem() instanceof MilkBucketItem) {
            int slot = body.selectedSlot;
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            // Milk cures all curable effects: capture the active effect list
            // BEFORE drinking so DRINK_COMPLETED can report what was cleared.
            String clearedEffects = serializeEffects(List.copyOf(body.getActiveEffects()));
            float health = body.getHealth();
            emitDrinkStarted("minecraft:milk", slot, health, source);
            boolean consumed = body.drinkMilk();
            if (consumed) {
                emitDrinkCompleted("minecraft:milk", slot, clearedEffects, "bucket", source);
            } else {
                emitDrinkFailed("NOT_DRINKABLE", slot, itemId, source);
            }
            return;
        }
        // Consumable reflex reached here: the item is not food, not a
        // drinkable potion, not milk, and not a throwable potion. Emit
        // the precise failure reason instead of silently meleeing.
        if (isConsumableReflex) {
            String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (isEatReflex) {
                emitEatFailed("NOT_EDIBLE", body.selectedSlot, itemId, source);
            } else {
                emitDrinkFailed("NO_POTION", body.selectedSlot, itemId, source);
            }
            return;
        }
        Item heldItem = held.getItem();
        if (heldItem instanceof BowItem) {
            facade.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (heldItem instanceof ShieldItem) {
            body.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (heldItem instanceof BucketItem || heldItem instanceof FishingRodItem) {
            useHeldAirStack();
        } else {
            melee.onUsePress();
        }
    }

    /**
     * Push one EAT_COMPLETED event. Wrapped so a reporting failure never
     * takes the tick pipeline down (same invariant as MissionReporter).
     */
    private void emitEatCompleted(
            String itemId,
            int slot,
            int nutrition,
            float satBefore,
            float satAfter,
            int foodBefore,
            int foodAfter,
            String containerType,
            String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("itemId", itemId);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("nutrition", Integer.toString(nutrition));
            attrs.put("saturationGained", Float.toString(satAfter - satBefore));
            attrs.put("foodLevelBefore", Integer.toString(foodBefore));
            attrs.put("foodLevelAfter", Integer.toString(foodAfter));
            attrs.put("saturationBefore", Float.toString(satBefore));
            attrs.put("saturationAfter", Float.toString(satAfter));
            attrs.put("containerType", containerType);
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.EAT_COMPLETED,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    "ate " + itemId + " (" + nutrition + " nutrition, food " + foodBefore + "->" + foodAfter + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one EAT_FAILED event. Same failure-isolation wrapper as
     * {@link #emitEatCompleted}.
     */
    private void emitEatFailed(String reason, int slot, String itemId, String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("reason", reason);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("itemId", itemId);
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.EAT_FAILED,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    "eat failed: " + reason + " (slot " + slot + ", item " + itemId + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Serialize a potion's effect list into the DRINK_COMPLETED wire
     * format: comma-separated "id:amplifier:duration" triples.
     * Instantaneous effects (instant health, instant damage) carry
     * duration 1 — vanilla Potions.HEALING registers
     * MobEffectInstance(HEAL, 1, 0), not 0. An empty list yields ""
     * (not null), so the attr is always present.
     *
     * @param effects the potion's effect instances; never null
     * @return the serialized string, possibly empty
     */
    private static String serializeEffects(List<MobEffectInstance> effects) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance e : effects) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect()).toString());
            sb.append(':').append(e.getAmplifier());
            sb.append(':').append(e.getDuration());
        }
        return sb.toString();
    }

    /**
     * Push one DRINK_STARTED event. Wrapped so a reporting failure
     * never takes the tick pipeline down.
     */
    private void emitDrinkStarted(String potionId, int slot, float health, String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("potionId", potionId);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("health", Float.toString(health));
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.DRINK_STARTED,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    true,
                    Map.copyOf(attrs),
                    "drink started: " + potionId + " (slot " + slot + ", health " + health + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one DRINK_COMPLETED event. Wrapped so a reporting failure
     * never takes the tick pipeline down.
     */
    private void emitDrinkCompleted(String potionId, int slot, String effects, String containerType, String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("potionId", potionId);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("effects", effects);
            attrs.put("containerType", containerType);
            // P2-d: whether the recovered container was dropped because
            // inventory was full (count>1 branch only; count=1 places the
            // container in the selected slot and this is always false).
            attrs.put("containerDropped", Boolean.toString(body.wasLastContainerDropped()));
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.DRINK_COMPLETED,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    "drank " + potionId + " (effects: " + effects + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one DRINK_FAILED event. Wrapped so a reporting failure
     * never takes the tick pipeline down.
     */
    private void emitDrinkFailed(String reason, int slot, String itemId, String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("reason", reason);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("itemId", itemId);
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.DRINK_FAILED,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    "drink failed: " + reason + " (slot " + slot + ", item " + itemId + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one POTION_THROWN event. Wrapped so a reporting failure
     * never takes the tick pipeline down.
     */
    private void emitPotionThrown(String potionId, int slot, String throwType, String effects, String source) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("potionId", potionId);
            attrs.put("slot", Integer.toString(slot));
            attrs.put("throwType", throwType);
            attrs.put("effects", effects);
            attrs.put("source", source);
            events.push(new BotEvent(
                    EventKind.POTION_THROWN,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    "threw " + throwType + " " + potionId + " (effects: " + effects + ")"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Resolve the container type string for EAT_COMPLETED attrs. Mirrors
     * {@link BotBodyEntity#containerItemFor(Item)} but returns the wire
     * string instead of the engine item. Kept in the actor because the
     * event vocabulary is owned here; the body owns the physical mapping.
     *
     * @param item the food item; never null
     * @return "bowl", "glass_bottle", or "none"
     */
    private static String containerTypeFor(Item item) {
        if (item instanceof BowlFoodItem || item instanceof SuspiciousStewItem) {
            return "bowl";
        }
        if (item instanceof HoneyBottleItem) {
            return "glass_bottle";
        }
        return "none";
    }

    /** Releases every in-progress hold (facade draw, body shield). */
    private void releaseUseHolds() {
        if (facade.isUsingItem()) {
            facade.releaseUsingItem();
        }
        if (body.isUsingItem()) {
            body.releaseUsingItem();
        }
    }

    private void applySlot(Claim slot) {
        if (slot != null && slot.intent() instanceof Intent.SelectSlot s) {
            body.selectedSlot = s.slot();
            // Mirror to the inventory binding so perception snapshots
            // read the correct held slot (issue 0007 Phase 1). The body
            // field stays the write target for the SLOT channel; the
            // inventory is the read side for WorldView.getInventory().
            body.getInventory().setSelectedSlot(s.slot());
        }
    }

    private void applyInteract(Claim interact) {
        if (interact != null && interact.intent() instanceof Intent.Dig d) {
            dig.dig(d.target());
            lastDropClaimed = false;
            lastInteractClaimed = false;
        } else if (interact != null && interact.intent() instanceof Intent.DropSelected ds) {
            // Drop is one-shot: fire on the rising edge only. A held
            // DropSelected claim across ticks must not drop twice — the
            // same rising-edge gate USE uses for melee swings. Any stale
            // dig crack is cleared because DropSelected owns the channel
            // this tick (per-channel arbitration means Dig cannot also
            // win).
            dig.release();
            if (!lastDropClaimed) {
                body.dropSelectedItem(ds.fullStack());
            }
            lastDropClaimed = true;
            lastInteractClaimed = false;
        } else if (interact != null && interact.intent() instanceof Intent.InteractBlock ib) {
            // Place is one-shot: fire on the rising edge only. A held
            // InteractBlock claim across ticks must not place repeatedly
            // — same rising-edge gate as DropSelected and USE melee.
            // Any stale dig crack is cleared because InteractBlock owns
            // the channel this tick.
            dig.release();
            if (!lastInteractClaimed) {
                this.interact.place(ib);
            }
            lastInteractClaimed = true;
            lastDropClaimed = false;
        } else {
            // A dig no longer held must clear its crack broadcast the
            // same tick; vanilla's stop path does, and a stale crack
            // would lie to every observer.
            dig.release();
            lastDropClaimed = false;
            lastInteractClaimed = false;
        }
    }

    @Override
    public void clearAllIntents() {
        delegate.clearAllIntents();
        body.setDrive(0f, 0f, false);
        body.setSprinting(false);
        dig.release();
        lastDropClaimed = false;
        lastInteractClaimed = false;
    }

    /**
     * Straight clearance required ahead before the sprint gait
     * engages, in blocks (issue 0005 P1.1). Issue 0004 F6(3): a mob
     * carrier pays no hunger for sprinting.
     */
    public static final double SPRINT_CLEARANCE_BLOCKS = 5.0;

    /**
     * Drive strength at or above which the body counts as striding
     * for the sprint policy.
     */
    public static final double SPRINT_FORWARD_MIN = 0.95;

    /**
     * Whether the sprint gait may engage this tick: full forward
     * stride, not in fluid, and no wall inside
     * {@link #SPRINT_CLEARANCE_BLOCKS} along the facing. The clip
     * rides at eye height, so single steps ahead do not read as
     * walls - sprinting into a step is the sprint-jump gait, not a
     * collision.
     */
    // contract: see issues/0005-player-feel-motion-layer.md P1 +
    // issue 0004 D4 (sprint is an adapter policy, no Intent field)
    private boolean sprintClearAhead(Intent.Move m) {
        if (m.forward() < SPRINT_FORWARD_MIN || body.isInWater() || body.isInLava()) {
            return false;
        }
        var view = body.getViewVector(1.0F);
        var dir = new Vec3(view.x, 0, view.z).normalize();
        var eye = body.getEyePosition();
        var clip = body.level()
                .clip(new ClipContext(
                        eye,
                        eye.add(dir.scale(SPRINT_CLEARANCE_BLOCKS)),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        body));
        return clip.getType() == HitResult.Type.MISS;
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }
}
