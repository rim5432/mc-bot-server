package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.event.EventFacts;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import java.util.List;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BowlFoodItem;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.SuspiciousStewItem;
import net.minecraft.world.item.alchemy.PotionUtils;

/**
 * Consumable USE-edge execution - the actor's consumable dispatch
 * chain: an edible held item means eat, a potion means drink, a milk
 * bucket means cure, a splash/lingering potion means throw, and a
 * consumable reflex whose item matches none of those fails with a
 * typed reason instead of silently meleeing. Wire shapes are owned by
 * {@link EventFacts}; physical consumption is
 * {@link com.mcbot.mcbotserver.adapter.entity.ConsumptionMechanics};
 * this class is the seam between them - fact mining (registry ids,
 * effect-list traversal, the container-dropped read) plus emission.
 *
 * <p>Dispatch order is load-bearing: splash/lingering MUST be checked
 * before {@code PotionItem} because {@code ThrowablePotionItem
 * extends PotionItem} - a bare instanceof would attempt to drink a
 * throwable potion whose effects only resolve on projectile impact.
 * Food in hand consumes the press even when the eat is refused
 * (NOT_HUNGRY) - vanilla right-click semantics; the weapon tail
 * (bow/shield/bucket/melee) only runs when this method returns false.
 *
 * <p>Implementation note: server tick thread only, called from the
 * actor's USE rising edge.
 */
final class ConsumableUse {

    private final BotBodyEntity body;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier todSupplier;

    /**
     * Creates the consumable seam over one body's event stream.
     *
     * @param body        the consuming body; never null
     * @param events      boundary-D event sink; never null
     * @param daySupplier game-day stamp source; never null
     * @param todSupplier time-of-day tick stamp source; never null
     */
    ConsumableUse(BotBodyEntity body, EventQueue events, LongSupplier daySupplier, LongSupplier todSupplier) {
        this.body = body;
        this.events = events;
        this.daySupplier = daySupplier;
        this.todSupplier = todSupplier;
    }

    /**
     * Attempt to route one rising USE edge through the consumable
     * dispatch. Emits the matching lifecycle events; returns false
     * only when the held item is no consumable at all and the owner is
     * not a consumable reflex - the caller then runs its weapon tail.
     *
     * @param owner the claim owner tag (reflex prefixes discriminate
     *              consumable intent); may be null
     * @return true when the press was consumed by a consumable path
     */
    boolean tryConsume(String owner) {
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
        String source = owner != null && owner.startsWith(ReflexAction.REFLEX_OWNER_PREFIX) ? "reflex" : "harness";
        ItemStack held = body.getInventory().container().getItem(body.selectedSlot);
        if (held.isEmpty() && isConsumableReflex) {
            if (isEatReflex) {
                emitEatFailed("SLOT_EMPTY", body.selectedSlot, "", source);
            } else {
                emitDrinkFailed("SLOT_EMPTY", body.selectedSlot, "", source);
            }
            return true;
        }
        if (tryEat(held, source)
                || tryThrowPotion(held, source)
                || tryDrinkPotion(held, source)
                || tryDrinkMilk(held, source)) {
            return true;
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
            return true;
        }
        return false;
    }

    /**
     * Food branch of the dispatch: an edible held item is eaten and
     * the EAT lifecycle emitted; a food refused by the body (full, or
     * the stack drained between reads) still consumes the press -
     * vanilla right-click semantics.
     *
     * @param held   the held stack; never null, may be non-food
     * @param source wire attr for the emitted events; never null
     * @return true when the held item was food and the press is
     *         consumed
     */
    private boolean tryEat(ItemStack held, String source) {
        var props = held.getFoodProperties(body);
        if (props == null) {
            return false;
        }
        int foodBefore = body.getFoodData().getFoodLevel();
        float satBefore = body.getFoodData().getSaturationLevel();
        int slot = body.selectedSlot;
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        int nutrition = props.getNutrition();
        // Container type for the EAT_COMPLETED attrs: bowl foods and
        // honey bottles leave a container; normal foods and chorus fruit
        // do not. Captured before eatHeldItem mutates the slot.
        String containerType = containerTypeFor(held.getItem());
        boolean consumed = body.consumption().eatHeldItem();
        if (consumed) {
            int foodAfter = body.getFoodData().getFoodLevel();
            float satAfter = body.getFoodData().getSaturationLevel();
            emitEatCompleted(
                    itemId, slot, nutrition, satBefore, satAfter, foodBefore, foodAfter, containerType, source);
            return true;
        }
        // Food in hand but not consumed: needsFood() was false (full)
        // or the stack was empty between props read and eatHeldItem.
        emitEatFailed("NOT_HUNGRY", slot, itemId, source);
        return true;
    }

    /**
     * Throwable-potion branch. Dispatch order is load-bearing:
     * splash/lingering MUST be checked before the plain
     * {@link PotionItem} branch because {@code ThrowablePotionItem
     * extends PotionItem} - a bare instanceof would attempt to drink a
     * throwable potion whose effects only resolve on projectile
     * impact.
     *
     * @param held   the held stack; never null
     * @param source wire attr for the emitted event; never null
     * @return true when the held item was a throwable potion
     */
    private boolean tryThrowPotion(ItemStack held, String source) {
        if (!(held.getItem() instanceof SplashPotionItem || held.getItem() instanceof LingeringPotionItem)) {
            return false;
        }
        int slot = body.selectedSlot;
        String potionId =
                BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(held)).toString();
        String effects = serializeEffects(PotionUtils.getMobEffects(held));
        String throwType = held.getItem() instanceof LingeringPotionItem ? "lingering" : "splash";
        if (body.consumption().throwHeldPotion()) {
            emitPotionThrown(potionId, slot, throwType, effects, source);
        }
        return true;
    }

    /**
     * Drinkable-potion branch: DRINK_STARTED, then COMPLETED or the
     * NOT_DRINKABLE failure when the body refuses the drink.
     *
     * @param held   the held stack; never null
     * @param source wire attr for the emitted events; never null
     * @return true when the held item was a drinkable potion
     */
    private boolean tryDrinkPotion(ItemStack held, String source) {
        if (!(held.getItem() instanceof PotionItem)) {
            return false;
        }
        int slot = body.selectedSlot;
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        String potionId =
                BuiltInRegistries.POTION.getKey(PotionUtils.getPotion(held)).toString();
        String effects = serializeEffects(PotionUtils.getMobEffects(held));
        float health = body.getHealth();
        emitDrinkStarted(potionId, slot, health, source);
        if (body.consumption().drinkHeldItem()) {
            emitDrinkCompleted(potionId, slot, effects, "glass_bottle", source);
        } else {
            emitDrinkFailed("NOT_DRINKABLE", slot, itemId, source);
        }
        return true;
    }

    /**
     * Milk branch: the active effect list is captured BEFORE drinking
     * so DRINK_COMPLETED can report what was cured.
     *
     * @param held   the held stack; never null
     * @param source wire attr for the emitted events; never null
     * @return true when the held item was a milk bucket
     */
    private boolean tryDrinkMilk(ItemStack held, String source) {
        if (!(held.getItem() instanceof MilkBucketItem)) {
            return false;
        }
        int slot = body.selectedSlot;
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        // Milk cures all curable effects: capture the active effect list
        // BEFORE drinking so DRINK_COMPLETED can report what was cleared.
        String clearedEffects = serializeEffects(List.copyOf(body.getActiveEffects()));
        float health = body.getHealth();
        emitDrinkStarted("minecraft:milk", slot, health, source);
        if (body.consumption().drinkMilk()) {
            emitDrinkCompleted("minecraft:milk", slot, clearedEffects, "bucket", source);
        } else {
            emitDrinkFailed("NOT_DRINKABLE", slot, itemId, source);
        }
        return true;
    }

    /**
     * Push one EAT_COMPLETED event. The wire shape is
     * {@link EventFacts#eatCompleted}; this wrapper only stamps game
     * time and isolates reporting failures (same invariant as
     * MissionReporter).
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
            events.push(EventFacts.eatCompleted(
                    itemId,
                    slot,
                    nutrition,
                    satBefore,
                    satAfter,
                    foodBefore,
                    foodAfter,
                    containerType,
                    source,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong()));
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
            events.push(EventFacts.eatFailed(
                    reason, slot, itemId, source, daySupplier.getAsLong(), todSupplier.getAsLong()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one DRINK_STARTED event. Same failure-isolation wrapper as
     * {@link #emitEatCompleted}; wire shape in {@link EventFacts}.
     */
    private void emitDrinkStarted(String potionId, int slot, float health, String source) {
        try {
            events.push(EventFacts.drinkStarted(
                    potionId, slot, health, source, daySupplier.getAsLong(), todSupplier.getAsLong()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one DRINK_COMPLETED event. Same failure-isolation wrapper as
     * {@link #emitEatCompleted}; wire shape in {@link EventFacts}.
     */
    private void emitDrinkCompleted(String potionId, int slot, String effects, String containerType, String source) {
        try {
            // P2-d: whether the recovered container was dropped because
            // inventory was full (count>1 branch only; count=1 places the
            // container in the selected slot and this is always false).
            events.push(EventFacts.drinkCompleted(
                    potionId,
                    slot,
                    effects,
                    containerType,
                    body.consumption().wasLastContainerDropped(),
                    source,
                    daySupplier.getAsLong(),
                    todSupplier.getAsLong()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one DRINK_FAILED event. Same failure-isolation wrapper as
     * {@link #emitEatCompleted}; wire shape in {@link EventFacts}.
     */
    private void emitDrinkFailed(String reason, int slot, String itemId, String source) {
        try {
            events.push(EventFacts.drinkFailed(
                    reason, slot, itemId, source, daySupplier.getAsLong(), todSupplier.getAsLong()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down.
        }
    }

    /**
     * Push one POTION_THROWN event. Same failure-isolation wrapper as
     * {@link #emitEatCompleted}; wire shape in {@link EventFacts}.
     */
    private void emitPotionThrown(String potionId, int slot, String throwType, String effects, String source) {
        try {
            events.push(EventFacts.potionThrown(
                    potionId, slot, throwType, effects, source, daySupplier.getAsLong(), todSupplier.getAsLong()));
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
            sb.append(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect()))
                    .append(':')
                    .append(e.getAmplifier())
                    .append(':')
                    .append(e.getDuration());
        }
        return sb.toString();
    }

    /**
     * Resolve the container type string for EAT_COMPLETED attrs. Mirrors
     * the body's physical container mapping but returns the wire string
     * instead of the engine item.
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
}
