package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SuspiciousStewItem;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * The eating family of the consumable scenarios, split from
 * {@link BotHungerGameTests} (which keeps the FoodData mechanics):
 * EAT reflex disclosure, container retention per food family (bowl
 * foods, honey bottles, suspicious stew, chorus fruit, golden
 * apple), and the EAT_COMPLETED nutrition snapshot. Every scenario
 * rides the same {@code eatHeldItem -> finishUsingItem} engine path
 * and primes its FoodData through {@code GametestRig.primeFood}.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotEatingGameTests {

    private BotEatingGameTests() {}

    private static void assertSlotKeepsOneStew(ItemStack slot0) {
        check(
                slot0.is(Items.MUSHROOM_STEW),
                "slot 0 must keep remaining stew, got " + BuiltInRegistries.ITEM.getKey(slot0.getItem()));
        check(slot0.getCount() == 1, "stew count must be 1 (2-1), got " + slot0.getCount());
    }

    // ===== Eat event disclosure =====

    /**
     * Scenario: the EAT reflex fires (foodLevel below trigger, food in
     * inventory) and the adapter consumes one item. Both EAT_STARTED
     * (pre-selection, urgent) and EAT_COMPLETED (result, non-urgent)
     * must appear on the boundary-D event stream with correct attrs.
     * Pins the eat lifecycle disclosure (ledger 34 extension): the
     * harness learns WHY the mission was preempted and WHAT was eaten
     * without polling status.
     */
    // capability: hunger.eat_events
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void eatReflexEmitsStartedAndCompletedEvents(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // foodLevel 10 (<= 16 trigger), saturation 0: the EAT reflex
        // fires on the first tick. Cooked beef: 8 nutrition, 0.8 sat
        // modifier -> foodLevel 10->18, saturation capped at 18.
        GametestRig.primeFood(body, 10, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.COOKED_BEEF));

        helper.startSequence()
                .thenWaitUntil(
                        driveUntil(rig, () -> GametestAsserts.assertEventSeen(rig.events(), EventKind.EAT_COMPLETED)))
                .thenExecuteAfter(0, () -> {
                    var events = GametestAsserts.eventsOf(rig.events());
                    // EAT_STARTED: pre-selection, urgent, carries foodLevel
                    // and slot at decision time.
                    BotEvent started = events.stream()
                            .filter(e -> EventKind.EAT_STARTED.equals(e.kind()))
                            .findFirst()
                            .orElseThrow();
                    check(started.urgent(), "EAT_STARTED must be urgent (mission preempted same tick)");
                    check(
                            "10".equals(started.attrs().get("foodLevel")),
                            "EAT_STARTED foodLevel must be 10, got "
                                    + started.attrs().get("foodLevel"));
                    check(
                            "0".equals(started.attrs().get("slot")),
                            "EAT_STARTED slot must be 0, got " + started.attrs().get("slot"));
                    check(
                            "reflex".equals(started.attrs().get("source")),
                            "EAT_STARTED source must be reflex, got "
                                    + started.attrs().get("source"));
                    // EAT_COMPLETED: result, non-urgent, carries full before/
                    // after state and nutrition.
                    BotEvent completed = events.stream()
                            .filter(e -> EventKind.EAT_COMPLETED.equals(e.kind()))
                            .findFirst()
                            .orElseThrow();
                    check(!completed.urgent(), "EAT_COMPLETED must be non-urgent");
                    check(
                            "minecraft:cooked_beef".equals(completed.attrs().get("itemId")),
                            "EAT_COMPLETED itemId must be cooked_beef, got "
                                    + completed.attrs().get("itemId"));
                    check(
                            "8".equals(completed.attrs().get("nutrition")),
                            "EAT_COMPLETED nutrition must be 8, got "
                                    + completed.attrs().get("nutrition"));
                    check(
                            "10".equals(completed.attrs().get("foodLevelBefore")),
                            "EAT_COMPLETED foodLevelBefore must be 10, got "
                                    + completed.attrs().get("foodLevelBefore"));
                    check(
                            "18".equals(completed.attrs().get("foodLevelAfter")),
                            "EAT_COMPLETED foodLevelAfter must be 18, got "
                                    + completed.attrs().get("foodLevelAfter"));
                    check(
                            "reflex".equals(completed.attrs().get("source")),
                            "EAT_COMPLETED source must be reflex, got "
                                    + completed.attrs().get("source"));
                    // STARTED precedes COMPLETED in stream order (decision
                    // before consumption, same tick but ordered push).
                    long startedIdx = events.indexOf(started);
                    long completedIdx = events.indexOf(completed);
                    check(
                            startedIdx < completedIdx,
                            "EAT_STARTED must precede EAT_COMPLETED (started=" + startedIdx + " completed="
                                    + completedIdx + ")");
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a reflex EAT claim arrives with the selected slot empty.
     * The adapter must emit EAT_FAILED(reason=SLOT_EMPTY, source=reflex)
     * instead of silently falling through to melee. Pins the P2-d reflex
     * intent detection for the EAT family: the owner prefix
     * 'reflex:EAT_' carries unambiguous consumable intent, so an empty
     * slot is a FAILED action, not a silent no-op. The claim is
     * constructed directly (bypassing the reflex sensor) so the test
     * isolates the adapter's failure-path dispatch, not the sensor's
     * food-detection logic.
     */
    // capability: hunger.eat_events
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void eatReflexEmptySlotEmitsSlotEmpty(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Selected slot 0 is empty: no food, no item at all.
        body.getInventory().container().setItem(0, ItemStack.EMPTY);

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 85, "reflex:EAT_WHEN_HUNGRY", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.EAT_FAILED);
                }))
                .thenExecuteAfter(0, () -> {
                    var events = GametestAsserts.eventsOf(rig.events());
                    BotEvent failed = events.stream()
                            .filter(e -> EventKind.EAT_FAILED.equals(e.kind()))
                            .findFirst()
                            .orElseThrow();
                    check(
                            "SLOT_EMPTY".equals(failed.attrs().get("reason")),
                            "EAT_FAILED reason must be SLOT_EMPTY, got "
                                    + failed.attrs().get("reason"));
                    check(
                            "reflex".equals(failed.attrs().get("source")),
                            "EAT_FAILED source must be reflex, got "
                                    + failed.attrs().get("source"));
                    check(
                            "0".equals(failed.attrs().get("slot")),
                            "EAT_FAILED slot must be 0, got " + failed.attrs().get("slot"));
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a reflex EAT claim arrives with a non-food item (stick) in
     * the selected slot. The adapter must emit EAT_FAILED(reason=NOT_EDIBLE,
     * itemId=minecraft:stick, source=reflex) instead of silently meleeing
     * with the stick. Pins the P2-d NOT_EDIBLE failure path for the EAT
     * family: the food-properties check returns null for a stick, and the
     * reflex intent detection routes it to EAT_FAILED rather than the
     * melee fallback. The claim is constructed directly (bypassing the
     * reflex sensor) to isolate the adapter's failure dispatch.
     */
    // capability: hunger.eat_events
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void eatReflexNonFoodEmitsNotEdible(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Selected slot 0: a stick (not food, not a weapon, not a tool).
        body.getInventory().container().setItem(0, new ItemStack(Items.STICK));

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 85, "reflex:EAT_WHEN_HUNGRY", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.EAT_FAILED);
                }))
                .thenExecuteAfter(0, () -> {
                    var events = GametestAsserts.eventsOf(rig.events());
                    BotEvent failed = events.stream()
                            .filter(e -> EventKind.EAT_FAILED.equals(e.kind()))
                            .findFirst()
                            .orElseThrow();
                    check(
                            "NOT_EDIBLE".equals(failed.attrs().get("reason")),
                            "EAT_FAILED reason must be NOT_EDIBLE, got "
                                    + failed.attrs().get("reason"));
                    check(
                            "minecraft:stick".equals(failed.attrs().get("itemId")),
                            "EAT_FAILED itemId must be minecraft:stick, got "
                                    + failed.attrs().get("itemId"));
                    check(
                            "reflex".equals(failed.attrs().get("source")),
                            "EAT_FAILED source must be reflex, got "
                                    + failed.attrs().get("source"));
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Special food container retention =====

    /**
     * Scenario: eating a single bowl food (mushroom_stew, count 1) leaves
     * a bowl in the selected slot. Pins the count-1 branch of
     * {@code BotBodyEntity.eatHeldItem}: finishUsingItem returns the bowl,
     * the slot is replaced with it (vanilla Player behavior).
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void bowlFoodCountOneLeavesBowlInSlot(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // foodLevel 10 (<= 16 trigger): EAT reflex fires immediately.
        GametestRig.primeFood(body, 10, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.MUSHROOM_STEW));

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getFoodData().getFoodLevel() >= 16,
                                "waiting for eat (food=" + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(
                            slot0.is(Items.BOWL),
                            "slot 0 must be bowl after eating count-1 stew, got "
                                    + BuiltInRegistries.ITEM.getKey(slot0.getItem()));
                    check(slot0.getCount() == 1, "bowl count must be 1, got " + slot0.getCount());
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: eating from a stack of bowl food (mushroom_stew, count 2)
     * keeps the remaining stew in the selected slot and routes the bowl to
     * another inventory slot. Pins the count-{@code >}1 branch:
     * finishUsingItem shrinks the stack to count-1 but returns a bowl (the
     * shrunk remainder would be lost if the caller replaced the slot with
     * the bowl). The bot must keep the shrunk stack and add the bowl to
     * inventory, matching vanilla Player behavior.
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void bowlFoodCountTwoKeepsStewAndAddsBowlToInventory(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.MUSHROOM_STEW, 2));

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getFoodData().getFoodLevel() >= 16,
                                "waiting for eat (food=" + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    assertSlotKeepsOneStew(slot0);
                    // Bowl must be somewhere in the main inventory (not slot 0).
                    boolean bowlFound = false;
                    for (int i = 1; i < 36; i++) {
                        if (body.getInventory().container().getItem(i).is(Items.BOWL)) {
                            bowlFound = true;
                            break;
                        }
                    }
                    check(bowlFound, "bowl must be added to inventory after eating count-2 stew");
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: eating from a stack of honey bottles (count 2) keeps the
     * remaining bottle in the selected slot and routes a glass bottle to
     * another inventory slot. Pins the HoneyBottleItem-specific branch:
     * finishUsingItem returns the shrunk stack for count{@code >}1 but only
     * adds the glass bottle to {@code Player} inventories — the bot is a
     * Mob, so without explicit handling the glass bottle is silently lost.
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void honeyBottleCountTwoKeepsBottleAndAddsGlassToInventory(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.HONEY_BOTTLE, 2));

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getFoodData().getFoodLevel() >= 16,
                                "waiting for eat (food=" + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(
                            slot0.is(Items.HONEY_BOTTLE),
                            "slot 0 must keep remaining honey bottle, got "
                                    + BuiltInRegistries.ITEM.getKey(slot0.getItem()));
                    check(slot0.getCount() == 1, "honey bottle count must be 1 (2-1), got " + slot0.getCount());
                    // Glass bottle must be somewhere in the main inventory.
                    boolean glassFound = false;
                    for (int i = 1; i < 36; i++) {
                        if (body.getInventory().container().getItem(i).is(Items.GLASS_BOTTLE)) {
                            glassFound = true;
                            break;
                        }
                    }
                    check(glassFound, "glass bottle must be added to inventory after eating count-2 honey bottle");
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Special food side effects =====

    /**
     * Scenario: eating a suspicious stew with a stored potion effect applies
     * that effect to the carrier. Pins the NBT-driven effect branch of
     * {@code SuspiciousStewItem.finishUsingItem}: {@code listPotionEffects}
     * reads the Effects tag from the (shrunk) stack and calls
     * {@code LivingEntity.addEffect} — works on any LivingEntity, not just
     * Player. Uses count 2 so the shrunk stack retains its NBT (count 1
     * shrinks to air and the tag may be cleared).
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void suspiciousStewAppliesStoredPotionEffect(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        // Suspicious stew with night vision (200 ticks = 10 seconds).
        // saveMobEffect writes the Effects NBT tag that finishUsingItem reads.
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW, 2);
        SuspiciousStewItem.saveMobEffect(stew, MobEffects.NIGHT_VISION, 200);
        body.getInventory().container().setItem(0, stew);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.hasEffect(MobEffects.NIGHT_VISION),
                                "waiting for night vision effect (food="
                                        + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            body.hasEffect(MobEffects.NIGHT_VISION),
                            "carrier must have night vision after eating suspicious stew");
                    // Effect duration should be ~200 ticks (allow some tick drift).
                    int duration = body.getEffect(MobEffects.NIGHT_VISION).getDuration();
                    check(duration > 150, "night vision duration should be ~200 ticks, got " + duration);
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: honey bottle removes the poison effect from the carrier.
     * Pins the server-side {@code removeEffect(MobEffects.POISON)} call in
     * {@code HoneyBottleItem.finishUsingItem} — works on any LivingEntity,
     * not just Player. Poison is applied first (100 ticks), then the eat
     * reflex fires and the honey bottle clears it.
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void honeyBottleRemovesPoisonEffect(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        // Apply poison first (100 ticks = 5 seconds).
        body.addEffect(new MobEffectInstance(MobEffects.POISON, 100));
        check(body.hasEffect(MobEffects.POISON), "carrier must have poison before eating honey bottle");
        body.getInventory().container().setItem(0, new ItemStack(Items.HONEY_BOTTLE));

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                !body.hasEffect(MobEffects.POISON),
                                "waiting for poison removal (food="
                                        + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(!body.hasEffect(MobEffects.POISON), "poison must be removed after eating honey bottle");
                    // Honey bottle gives 6 nutrition: foodLevel 10->16.
                    check(
                            body.getFoodData().getFoodLevel() >= 16,
                            "foodLevel should be >=16 after honey bottle, got "
                                    + body.getFoodData().getFoodLevel());
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: eating a chorus fruit consumes the item and applies nutrition;
     * the random teleport may or may not fire (16 attempts, ±8 blocks,
     * requires a safe landing spot). Pins the deterministic part — item
     * consumption and FoodData application — without asserting on the
     * non-deterministic teleport outcome. The teleport itself is vanilla
     * {@code LivingEntity.randomTeleport} invoked from
     * {@code ChorusFruitItem.finishUsingItem}; it is covered by vanilla's
     * own tests.
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void chorusFruitConsumesItemAndAppliesFood(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.CHORUS_FRUIT));
        double startX = body.getX();
        double startZ = body.getZ();

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getFoodData().getFoodLevel() >= 14,
                                "waiting for chorus fruit eat (food="
                                        + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    // Chorus fruit gives 4 nutrition: foodLevel 10->14.
                    check(
                            body.getFoodData().getFoodLevel() >= 14,
                            "foodLevel should be >=14 after chorus fruit, got "
                                    + body.getFoodData().getFoodLevel());
                    // Item must be consumed (slot 0 empty or not chorus fruit).
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(
                            slot0.isEmpty() || !slot0.is(Items.CHORUS_FRUIT),
                            "chorus fruit must be consumed after eating, slot 0="
                                    + BuiltInRegistries.ITEM.getKey(slot0.getItem()));
                    // Teleport is non-deterministic: record but do not assert.
                    // If it fired, the position changed; if not, the bot is still
                    // at the start position. Both are valid vanilla outcomes.
                    boolean teleported = Math.abs(body.getX() - startX) > 0.1 || Math.abs(body.getZ() - startZ) > 0.1;
                    // Teleport is non-deterministic (16 attempts, ±8 blocks):
                    // record for observability, do not assert. Both fired and
                    // not-fired are valid vanilla outcomes.
                    if (teleported) {
                        // position changed; no assertion needed
                    }
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Always-edible bypass =====

    /**
     * Scenario: a golden apple is consumable at full hunger (foodLevel 20)
     * because {@code FoodProperties.canAlwaysEat()} bypasses the
     * {@code needsFood()} gate. Pins the always-edible bypass in
     * {@code BotBodyEntity.eatHeldItem}: without it, golden apples are
     * completely unusable at full hunger (the reflex only fires at
     * foodLevel{@code <=}16, and the harness use-item path rejects all food).
     * Also verifies the golden apple's signature effects (regeneration II,
     * absorption I) are applied via {@code finishUsingItem} — the nutrition
     * is capped at 20 but the effects must still land.
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 40)
    public static void goldenAppleEdibleAtFullHungerAndAppliesEffects(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Full hunger: normal food would be rejected by the needsFood() gate.
        GametestRig.primeFood(body, 20, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.GOLDEN_APPLE));

        // Direct call (bypassing reflex) to verify the eatHeldItem gate.
        // Before the always-edible fix this returned false at full hunger.
        boolean consumed = body.consumption().eatHeldItem();
        check(consumed, "golden apple must be consumable at full hunger (canAlwaysEat bypass)");
        // Golden apple signature effects: regeneration II (100 ticks) +
        // absorption I (2400 ticks). Both must apply even at full hunger.
        check(body.hasEffect(MobEffects.REGENERATION), "golden apple must apply regeneration effect");
        check(body.hasEffect(MobEffects.ABSORPTION), "golden apple must apply absorption effect");
        // Nutrition is capped at 20 (already full); golden apple gives 4
        // nutrition but the cap prevents overflow.
        check(body.getFoodData().getFoodLevel() == 20, "foodLevel must stay 20 (capped) after golden apple at full");
        // Item consumed: slot 0 should be empty (golden apple count 1 -> 0).
        check(body.getInventory().container().getItem(0).isEmpty(), "golden apple must be consumed (slot 0 empty)");
        body.discard();
        helper.succeed();
    }

    // ===== Deep QA: multi-effect, full-inventory, event stream =====

    /**
     * Scenario: a suspicious stew carrying TWO potion effects (night vision
     * + jump boost) applies both simultaneously. Pins the multi-effect
     * branch of {@code SuspiciousStewItem.finishUsingItem}:
     * {@code listPotionEffects} iterates the full Effects ListTag and calls
     * {@code addEffect} for each entry — the single-effect test only covers
     * the first-iteration path. Uses count 2 so the shrunk stack retains
     * its NBT (count 1 shrinks to air and the tag may be cleared).
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void suspiciousStewWithMultipleEffectsAppliesAll(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        // Two effects: night vision (200 ticks) + jump boost (200 ticks).
        // NOTE: vanilla SuspiciousStewItem.saveMobEffect uses
        // getList("Effects", 9) (TAG_List) while the Effects list holds
        // CompoundTag entries (type 10), so each call starts from an empty
        // list and overwrites — multi-effect must be built manually.
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW, 2);
        CompoundTag stewTag = stew.getOrCreateTag();
        ListTag effectsList = new ListTag();
        CompoundTag nvTag = new CompoundTag();
        nvTag.putInt("EffectId", MobEffect.getId(MobEffects.NIGHT_VISION));
        nvTag.putInt("EffectDuration", 200);
        effectsList.add(nvTag);
        CompoundTag jumpTag = new CompoundTag();
        jumpTag.putInt("EffectId", MobEffect.getId(MobEffects.JUMP));
        jumpTag.putInt("EffectDuration", 200);
        effectsList.add(jumpTag);
        stewTag.put("Effects", effectsList);
        body.getInventory().container().setItem(0, stew);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.hasEffect(MobEffects.NIGHT_VISION) && body.hasEffect(MobEffects.JUMP),
                                "waiting for both effects (nv=" + body.hasEffect(MobEffects.NIGHT_VISION) + " jump="
                                        + body.hasEffect(MobEffects.JUMP) + ")")))
                .thenExecuteAfter(0, () -> {
                    check(body.hasEffect(MobEffects.NIGHT_VISION), "carrier must have night vision");
                    check(body.hasEffect(MobEffects.JUMP), "carrier must have jump boost");
                    // Both effects should have ~200 tick duration (allow tick drift).
                    check(
                            body.getEffect(MobEffects.NIGHT_VISION).getDuration() > 150,
                            "night vision duration should be ~200, got "
                                    + body.getEffect(MobEffects.NIGHT_VISION).getDuration());
                    check(
                            body.getEffect(MobEffects.JUMP).getDuration() > 150,
                            "jump boost duration should be ~200, got "
                                    + body.getEffect(MobEffects.JUMP).getDuration());
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: eating a bowl food from a count-2 stack while the rest of
     * the inventory is FULL forces the bowl to be dropped via
     * {@code spawnAtLocation} (the {@code addOrDrop} fallback). Pins the
     * inventory-full branch: slots 1-35 pre-filled with cobblestone, so
     * the bowl cannot be added to any slot and must be dropped as a world
     * item entity. Verifies the shrunk stack stays in slot 0 and no bowl
     * exists in the main inventory (indirect proof of the drop path).
     */
    // capability: hunger.eat_chain
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void bowlFoodFullInventoryDropsContainer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 10, 0f, 0f);
        // Fill slots 1-35 with cobblestone (inventory full except slot 0).
        for (int i = 1; i < 36; i++) {
            body.getInventory().container().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        // Slot 0: mushroom_stew count 2. After eating one, slot 0 keeps the
        // remaining stew (count 1) and the bowl must be dropped (no empty slot).
        body.getInventory().container().setItem(0, new ItemStack(Items.MUSHROOM_STEW, 2));

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getFoodData().getFoodLevel() >= 16,
                                "waiting for eat (food=" + body.getFoodData().getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    // Slot 0 must keep the remaining stew (count 1), not be
                    // replaced by the bowl (inventory-full path).
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    assertSlotKeepsOneStew(slot0);
                    // No bowl in any main inventory slot (1-35 were full, 0
                    // has stew). The bowl was dropped via spawnAtLocation.
                    boolean bowlInInventory = false;
                    for (int i = 0; i < 36; i++) {
                        if (body.getInventory().container().getItem(i).is(Items.BOWL)) {
                            bowlInInventory = true;
                            break;
                        }
                    }
                    check(!bowlInInventory, "bowl must not be in inventory (was dropped, inventory was full)");
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a reflex-driven eat produces an EAT_COMPLETED event whose
     * attribute map carries the full nutrition snapshot — itemId, slot,
     * nutrition, saturationGained, foodLevelBefore/After,
     * saturationBefore/After, source, and containerType. Pins the
     * boundary-D disclosure contract for eat events: the harness must be
     * able to reconstruct exactly what was eaten and its metabolic impact
     * from a single event, without polling the body. Uses cooked_beef
     * (8 nutrition, 12.8 saturation) at foodLevel 6, saturation 0 so
     * every field has a distinct, verifiable value.
     */
    // capability: hunger.eat_events
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void eatCompletedEventCarriesFullNutritionSnapshot(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // foodLevel 6, saturation 0: cooked_beef (8 nutrition, 12.8 sat)
        // -> foodLevel 14, saturation 12.8. Every field is non-default.
        GametestRig.primeFood(body, 6, 0f, 0f);
        body.getInventory().container().setItem(0, new ItemStack(Items.COOKED_BEEF));

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    long completed = GametestAsserts.eventsOf(rig.events()).stream()
                            .filter(e -> EventKind.EAT_COMPLETED.equals(e.kind()))
                            .count();
                    check(completed >= 1, "waiting for EAT_COMPLETED (got " + completed + ")");
                }))
                .thenExecuteAfter(0, () -> {
                    var completed = GametestAsserts.eventsOf(rig.events()).stream()
                            .filter(e -> EventKind.EAT_COMPLETED.equals(e.kind()))
                            .findFirst()
                            .orElseThrow();
                    var attrs = completed.attrs();
                    // Identity fields.
                    check(
                            "minecraft:cooked_beef".equals(attrs.get("itemId")),
                            "itemId must be minecraft:cooked_beef, got " + attrs.get("itemId"));
                    check("0".equals(attrs.get("slot")), "slot must be 0, got " + attrs.get("slot"));
                    check("reflex".equals(attrs.get("source")), "source must be reflex, got " + attrs.get("source"));
                    check(
                            "none".equals(attrs.get("containerType")),
                            "containerType must be none (cooked_beef has no container), got "
                                    + attrs.get("containerType"));
                    // Nutrition fields. cooked_beef: nutrition=8 (FoodProperties
                    // units), saturationModifier=1.6. satAfter = min(0 + 8*1.6,
                    // foodLevel=14) = 12.8.
                    check(
                            "8".equals(attrs.get("nutrition")),
                            "nutrition must be 8 (cooked_beef), got " + attrs.get("nutrition"));
                    check(
                            "12.8".equals(attrs.get("saturationGained")),
                            "saturationGained must be 12.8, got " + attrs.get("saturationGained"));
                    // Food level before/after: 6 -> 14 (6 + 8 = 14).
                    check(
                            "6".equals(attrs.get("foodLevelBefore")),
                            "foodLevelBefore must be 6, got " + attrs.get("foodLevelBefore"));
                    check(
                            "14".equals(attrs.get("foodLevelAfter")),
                            "foodLevelAfter must be 14, got " + attrs.get("foodLevelAfter"));
                    // Saturation before/after: 0.0 -> 12.8.
                    check(
                            "0.0".equals(attrs.get("saturationBefore")),
                            "saturationBefore must be 0.0, got " + attrs.get("saturationBefore"));
                    check(
                            "12.8".equals(attrs.get("saturationAfter")),
                            "saturationAfter must be 12.8, got " + attrs.get("saturationAfter"));
                    body.discard();
                })
                .thenSucceed();
    }
}
