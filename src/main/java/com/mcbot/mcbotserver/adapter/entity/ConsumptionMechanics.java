package com.mcbot.mcbotserver.adapter.entity;

import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.food.FoodProperties;
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

/**
 * The body's consumable mechanics: eat / drink / milk / throw and the
 * container-recovery inventory rules, extracted from BotBodyEntity so
 * the carrier keeps only its engine-override surface - the same
 * state-vs-mechanics split as {@link ExperienceMirror},
 * {@link GroundPickup} and {@link HungerTicker}. Wire narration for
 * these actions lives in the actor's ConsumableUse + EventFacts; this
 * class owns the physical side only.
 *
 * <p>Vanilla fidelity notes ride the individual methods (decompiled
 * 1.20.1 line references); the container-recovery rules are the single
 * home for what a finished consumable leaves behind and where it goes.
 */
final class ConsumptionMechanics {

    private final BotBodyEntity body;

    /**
     * Whether the most recent {@code consumeDrink} call dropped the
     * recovered container because inventory was full.
     */
    private boolean lastContainerDropped;

    /**
     * Creates the mechanics over one body.
     *
     * @param body the consuming body; never null
     */
    ConsumptionMechanics(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * Eat the held stack's top item (Phase 4 eat slice): the vanilla
     * mob-safe chain - {@code finishUsingItem} plays the eat sound,
     * rolls probability effects (rotten flesh, golden apples), shrinks
     * the stack and fires the EAT game event; the separate
     * {@code foodData.eat} adds nutrition/saturation, exactly the
     * Player.eat split.
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
    boolean eatHeldItem() {
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        FoodProperties props = held.getFoodProperties(body);
        if (props == null || (!body.getFoodData().needsFood() && !props.canAlwaysEat())) {
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
        ItemStack remainder = held.finishUsingItem(body.level(), body);
        body.getFoodData().eat(nutrition, saturation);
        if (containerItem != null) {
            if (held.isEmpty()) {
                // Count was 1: finishUsingItem returned the container; the
                // slot becomes the container (bowl / glass bottle).
                body.getInventory().container().setItem(body.selectedSlot, remainder);
            } else {
                // Count was >1: finishUsingItem shrank held to count-1. Keep
                // the shrunk stack in the slot and route the container to
                // inventory (BowlFoodItem/SuspiciousStewItem return the bowl
                // as remainder; HoneyBottleItem returns the shrunk stack, so
                // we synthesize the bottle here).
                body.getInventory().container().setItem(body.selectedSlot, held);
                ItemStack container = remainder.getItem() == containerItem ? remainder : new ItemStack(containerItem);
                addOrDrop(container);
            }
        } else {
            // Non-container food: remainder is the shrunk stack.
            body.getInventory().container().setItem(body.selectedSlot, remainder);
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
     * The caller ({@code ConsumableUse}) captures the effect list
     * BEFORE this method so DRINK_COMPLETED can report what was
     * applied.
     *
     * @return true when a potion was consumed
     */
    boolean drinkHeldItem() {
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        if (!(held.getItem() instanceof PotionItem)) {
            return false;
        }
        // finishUsingItem applies effects but does not shrink for Mob
        // callers — the Player-only shrink in PotionItem.finishUsingItem
        // line 63 is skipped, so consumeDrink shrinks manually below.
        held.finishUsingItem(body.level(), body);
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
    boolean drinkMilk() {
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        if (!(held.getItem() instanceof MilkBucketItem)) {
            return false;
        }
        // finishUsingItem cures effects but does not shrink for Mob callers
        // — consumeDrink handles the manual shrink and bucket recovery.
        held.finishUsingItem(body.level(), body);
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
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        held.shrink(1);
        if (held.isEmpty()) {
            // Count was 1: slot becomes the container.
            body.getInventory().container().setItem(body.selectedSlot, new ItemStack(container));
            lastContainerDropped = false;
        } else {
            // Count was > 1: keep the shrunk stack in the slot and route
            // the container to inventory (or drop if full).
            body.getInventory().container().setItem(body.selectedSlot, held);
            lastContainerDropped = !addOrDrop(new ItemStack(container));
        }
    }

    /**
     * Whether the most recent {@code consumeDrink} call dropped the
     * recovered container (glass bottle / bucket) because inventory
     * was full. Read by the actor's ConsumableUse to attach the
     * {@code containerDropped} attr to DRINK_COMPLETED.
     *
     * @return true if the container was dropped, false if it was added
     *         to inventory or placed in the selected slot (count-1 case)
     */
    boolean wasLastContainerDropped() {
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
    boolean throwHeldPotion() {
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        boolean isSplash = held.getItem() instanceof SplashPotionItem;
        boolean isLingering = held.getItem() instanceof LingeringPotionItem;
        if (!isSplash && !isLingering) {
            return false;
        }
        // The projectile carries a copy: the original is shrunk below, and
        // sharing the reference would mutate the projectile's stack too.
        ItemStack projectileStack = held.copy();
        ThrownPotion potion = new ThrownPotion(body.level(), body);
        potion.setItem(projectileStack);
        // Vanilla ThrowableItem.use parameters: -20deg pitch offset, 0.5
        // velocity, 1.0 inaccuracy.
        potion.shootFromRotation(body, body.getXRot(), body.getYRot(), -20.0F, 0.5F, 1.0F);
        body.level().addFreshEntity(potion);
        SoundEvent sound = isLingering ? SoundEvents.LINGERING_POTION_THROW : SoundEvents.SPLASH_POTION_THROW;
        body.level()
                .playSound(
                        null,
                        body.getX(),
                        body.getY(),
                        body.getZ(),
                        sound,
                        SoundSource.PLAYERS,
                        0.5F,
                        0.4F / (body.level().getRandom().nextFloat() * 0.4F + 0.8F));
        // Shrink: count 1 empties the slot; count > 1 keeps the shrunk stack.
        held.shrink(1);
        if (held.isEmpty()) {
            body.getInventory().container().setItem(body.selectedSlot, ItemStack.EMPTY);
        } else {
            body.getInventory().container().setItem(body.selectedSlot, held);
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
        var container = body.getInventory().container();
        // Phase 1: merge into existing same-item stack.
        int mergeSlot = -1;
        if (canMergeInto(container.getItem(body.selectedSlot), stack)) {
            mergeSlot = body.selectedSlot;
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
        body.spawnAtLocation(stack);
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
}
