package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestAsserts.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Potion consumption in-engine: the drinkHeldItem path applies potion
 * effects via finishUsingItem and recovers the glass bottle container.
 * Every scenario drives the production entity so a green means the
 * vanilla-mob-safe chain runs on a real carrier.
 *
 * <p>Contract: capability face {@code consumable.potion} (Phase 1).
 * Potions share the finishUsingItem mechanism with food but have no
 * FoodProperties, no hunger gate, and carry MobEffectInstance lists
 * instead of nutrition/saturation. The Mob path in PotionItem
 * .finishUsingItem skips shrink and container recovery (lines 60-75
 * are Player-only), so drinkHeldItem implements the vanilla Player
 * resolution manually.
 *
 * <p>Branch map (what each test pins):
 * <ul>
 *   <li>{@link #healingPotionRestoresHealthAndLeavesGlassBottle} —
 *       instant_health I heals 8 HP (4 hearts) via
 *       applyInstantenousEffect; count 1 replaces the slot with a
 *       glass bottle. Pins the effect-application path (source=null
 *       still heals) and the count-1 container replacement.</li>
 *   <li>{@link #potionStackCountTwoKeepsRemainderAndAddsBottle} —
 *       count{@code >}1 keeps the shrunk stack in the slot and routes
 *       the glass bottle to inventory via addOrDrop. Pins the
 *       count{@code >}1 branch (the non-obvious part: finishUsingItem
 *       does not shrink for Mob, so manual shrink + separate bottle
 *       routing is required).</li>
 * </ul>
 *
 * <p>Future extension hooks: splash/lingering potions (thrown, not
 * drunk), milk bucket (effect-clearing consumable), drink reflex
 * (low-health -> healing potion), and DRINK_* event verification
 * through BindingActor.onUsePressEdge.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotPotionGameTests {

    private BotPotionGameTests() {}

    /**
     * Scenario: a healing potion (instant_health I) restores 8 HP and
     * leaves a glass bottle in the selected slot when consumed from a
     * stack of 1. Pins the effect-application path —
     * PotionItem.finishUsingItem calls applyInstantenousEffect with a
     * null source (the bot is a Mob, not a Player), but HEAL effect's
     * applyInstantenousEffect heals regardless of source. Also pins the
     * count-1 container replacement: the slot becomes Items.GLASS_BOTTLE.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 40)
    public static void healingPotionRestoresHealthAndLeavesGlassBottle(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BotBodyEntity body = rig.body();
        // Damage the body so instant_health has room to heal.
        float healthBefore = 10.0f;
        body.setHealth(healthBefore);
        // Healing potion = instant_health I: heals 2 hearts = 4 HP.
        ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        body.getInventory().container().setItem(0, potion);

        boolean consumed = body.consumption().drinkHeldItem();
        check(consumed, "healing potion must be consumable via drinkHeldItem");
        // instant_health I heals 4 HP (2 hearts): the vanilla formula is
        // (4 << amplifier) * healthFactor, amplifier=0, healthFactor=1.0.
        check(
                body.getHealth() == healthBefore + 4.0f,
                "healing potion must restore 4 HP; got " + body.getHealth() + " (expected " + (healthBefore + 4.0f)
                        + ")");
        // Count 1: slot becomes glass bottle.
        ItemStack slot0 = body.getInventory().container().getItem(0);
        check(slot0.is(Items.GLASS_BOTTLE), "slot 0 must have glass bottle after count-1 drink; got " + slot0);
        check(slot0.getCount() == 1, "glass bottle count must be 1; got " + slot0.getCount());
        body.discard();
        helper.succeed();
    }

    /**
     * Scenario: drinking from a potion stack of 2 keeps the remaining
     * potion (count 1) in the selected slot and routes the glass bottle
     * to the first empty inventory slot via addOrDrop. Pins the
     * count{@code >}1 branch — the non-obvious part is that
     * PotionItem.finishUsingItem does NOT shrink the stack for Mob
     * callers (the shrink is Player-only at line 63), so drinkHeldItem
     * must shrink manually and then route the bottle separately (the
     * bottle is never returned by finishUsingItem for count{@code >}1
     * because the stack is not empty).
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 40)
    public static void potionStackCountTwoKeepsRemainderAndAddsBottle(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BotBodyEntity body = rig.body();
        body.setHealth(10.0f);
        // Stack of 2 healing potions.
        ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION, 2), Potions.HEALING);
        body.getInventory().container().setItem(0, potion);

        boolean consumed = body.consumption().drinkHeldItem();
        check(consumed, "potion stack of 2 must be consumable via drinkHeldItem");
        // Slot 0 keeps the shrunk stack: count 1 remaining.
        ItemStack slot0 = body.getInventory().container().getItem(0);
        check(slot0.is(Items.POTION), "slot 0 must keep remaining potion; got " + slot0);
        check(slot0.getCount() == 1, "remaining potion count must be 1; got " + slot0.getCount());
        // Glass bottle routed to first empty slot (slot 1, since slot 0
        // is occupied by the remaining potion).
        boolean foundBottle = false;
        for (int i = 1; i < 36; i++) {
            if (body.getInventory().container().getItem(i).is(Items.GLASS_BOTTLE)) {
                foundBottle = true;
                break;
            }
        }
        check(foundBottle, "glass bottle must be added to inventory after count>1 drink");
        body.discard();
        helper.succeed();
    }

    /**
     * Scenario: a milk bucket clears all active potion effects and leaves
     * an iron bucket in the selected slot. Pins the milk drink path —
     * MilkBucketItem.finishUsingItem calls curePotionEffects (line 26)
     * but the shrink (line 34-36) and bucket return (line 38) are
     * Player-only, so drinkMilk must shrink manually and recover the
     * bucket via the shared consumeDrink helper.
     *
     * <p>Night vision is used as the canary effect: it is curable
     * (default for all effects except those constructed with
     * curative=false), so milk must remove it. The test verifies both
     * the effect-clearing side effect and the container recovery.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 40)
    public static void milkBucketClearsEffectsAndLeavesBucket(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BotBodyEntity body = rig.body();
        // Apply night vision (200 ticks, amplifier 0) as the canary effect.
        body.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 200, 0));
        check(body.hasEffect(MobEffects.NIGHT_VISION), "night vision must be active before milk");
        body.getInventory().container().setItem(0, new ItemStack(Items.MILK_BUCKET));

        boolean consumed = body.consumption().drinkMilk();
        check(consumed, "milk bucket must be consumable via drinkMilk");
        // Milk cures all curable effects: night vision must be gone.
        check(!body.hasEffect(MobEffects.NIGHT_VISION), "milk must clear night vision effect");
        // Count 1: slot becomes the iron bucket.
        ItemStack slot0 = body.getInventory().container().getItem(0);
        check(slot0.is(Items.BUCKET), "slot 0 must have iron bucket after milk; got " + slot0);
        check(slot0.getCount() == 1, "bucket count must be 1; got " + slot0.getCount());
        body.discard();
        helper.succeed();
    }

    /**
     * Scenario: a harness-driven USE claim with a healing potion in hand
     * triggers the rising-edge {@code onUsePressEdge} potion branch,
     * emitting DRINK_STARTED (urgent, pre-consumption) and
     * DRINK_COMPLETED (non-urgent, result) on the boundary-D event
     * stream with correct attrs. Pins the drink lifecycle disclosure
     * through the BindingActor claim path — the body-level tests call
     * {@code drinkHeldItem} directly and never exercise event emission,
     * claim routing, or source resolution.
     *
     * <p>The rising edge fires once: {@code applyUse} sees
     * pressing=true with lastUsePressing=false on the first claimed
     * tick, calls {@code onUsePressEdge}, which detects the PotionItem
     * and drinks. Subsequent ticks with the claim held do not re-fire
     * (lastUsePressing stays true), so one claim = one drink, matching
     * vanilla right-click semantics.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void harnessUseClaimEmitsDrinkStartedAndCompleted(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Damage the body so instant_health has room to heal and the
        // DRINK_STARTED health attr carries a meaningful pre-drink value.
        float healthBefore = 10.0f;
        body.setHealth(healthBefore);
        body.getInventory().container().setItem(0, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING));

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    // Re-armed every tick: claims expire at flush. The
                    // first tick with pressing=true fires the rising edge
                    // in applyUse -> onUsePressEdge -> potion branch.
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:drink", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_COMPLETED);
                }))
                .thenExecuteAfter(0, () -> {
                    var events = GametestAsserts.eventsOf(rig.events());
                    // DRINK_STARTED: urgent, pre-consumption, carries
                    // potionId, slot, health at decision time, source.
                    BotEvent started = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_STARTED);
                    check(started.urgent(), "DRINK_STARTED must be urgent (pre-consumption decision)");
                    check(
                            "minecraft:healing".equals(started.attrs().get("potionId")),
                            "DRINK_STARTED potionId must be minecraft:healing, got "
                                    + started.attrs().get("potionId"));
                    check(
                            "0".equals(started.attrs().get("slot")),
                            "DRINK_STARTED slot must be 0, got "
                                    + started.attrs().get("slot"));
                    check(
                            "10.0".equals(started.attrs().get("health")),
                            "DRINK_STARTED health must be 10.0, got "
                                    + started.attrs().get("health"));
                    check(
                            "harness".equals(started.attrs().get("source")),
                            "DRINK_STARTED source must be harness, got "
                                    + started.attrs().get("source"));
                    // DRINK_COMPLETED: non-urgent, carries potionId, slot,
                    // serialized effects, containerType, source.
                    BotEvent completed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_COMPLETED);
                    check(!completed.urgent(), "DRINK_COMPLETED must be non-urgent");
                    check(
                            "minecraft:healing".equals(completed.attrs().get("potionId")),
                            "DRINK_COMPLETED potionId must be minecraft:healing, got "
                                    + completed.attrs().get("potionId"));
                    check(
                            "0".equals(completed.attrs().get("slot")),
                            "DRINK_COMPLETED slot must be 0, got "
                                    + completed.attrs().get("slot"));
                    check(
                            "minecraft:instant_health:0:1"
                                    .equals(completed.attrs().get("effects")),
                            "DRINK_COMPLETED effects must be instant_health:0:1 (vanilla HEALING registers"
                                    + " MobEffectInstance(HEAL, 1, 0) — instant effects carry 1 tick), got "
                                    + completed.attrs().get("effects"));
                    check(
                            "glass_bottle".equals(completed.attrs().get("containerType")),
                            "DRINK_COMPLETED containerType must be glass_bottle, got "
                                    + completed.attrs().get("containerType"));
                    check(
                            "harness".equals(completed.attrs().get("source")),
                            "DRINK_COMPLETED source must be harness, got "
                                    + completed.attrs().get("source"));
                    // STARTED precedes COMPLETED in stream order (decision
                    // before consumption, same tick but ordered push).
                    long startedIdx = events.indexOf(started);
                    long completedIdx = events.indexOf(completed);
                    check(
                            startedIdx < completedIdx,
                            "DRINK_STARTED must precede DRINK_COMPLETED (started=" + startedIdx + " completed="
                                    + completedIdx + ")");
                    // Side effect: the body healed 4 HP (instant_health I:
                    // (4 << amplifier) * healthFactor = 4).
                    check(
                            body.getHealth() == healthBefore + 4.0f,
                            "body must heal 4 HP after drinking healing potion; got " + body.getHealth() + " (expected "
                                    + (healthBefore + 4.0f) + ")");
                    // Side effect: slot 0 now holds the glass bottle.
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(slot0.is(Items.GLASS_BOTTLE), "slot 0 must have glass bottle after drink; got " + slot0);
                    check(slot0.getCount() == 1, "glass bottle count must be 1; got " + slot0.getCount());
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the DRINK_ON_LOW_HEALTH reflex fires when health drops
     * to the trigger (12) and a healing potion is in the hotbar — the
     * reflex layer submits its own USE claim (owner "reflex:DRINK_ON_LOW_HEALTH"),
     * the rising edge in applyUse fires onUsePressEdge, and the potion
     * branch emits DRINK_STARTED + DRINK_COMPLETED with source=reflex.
     * Pins the full reflex path: sensor stamps potionSlot -> rule fires
     * -> injector submits SLOT+USE -> adapter drinks -> events disclose.
     * The harness-path test (harnessUseClaimEmitsDrinkStartedAndCompleted)
     * covers the claim routing with source=harness; this test covers the
     * reflex-owned claim with source=reflex and the automatic trigger.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void drinkReflexOnLowHealthEmitsStartedAndCompleted(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Health 12 = DRINK_ON_LOW_HEALTH trigger: the reflex fires on
        // the first tick. instant_health I heals 4 HP -> 12->16, which
        // is above the trigger, so the rule releases after one drink.
        float healthBefore = 12.0f;
        body.setHealth(healthBefore);
        body.getInventory().container().setItem(0, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING));

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(
                        rig, () -> GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_COMPLETED)))
                .thenExecuteAfter(0, () -> {
                    var events = GametestAsserts.eventsOf(rig.events());
                    // DRINK_STARTED: urgent, pre-consumption, source=reflex
                    // (the claim owner is "reflex:DRINK_ON_LOW_HEALTH").
                    BotEvent started = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_STARTED);
                    check(started.urgent(), "DRINK_STARTED must be urgent (reflex preempts mission)");
                    check(
                            "minecraft:healing".equals(started.attrs().get("potionId")),
                            "DRINK_STARTED potionId must be minecraft:healing, got "
                                    + started.attrs().get("potionId"));
                    check(
                            "0".equals(started.attrs().get("slot")),
                            "DRINK_STARTED slot must be 0, got "
                                    + started.attrs().get("slot"));
                    check(
                            "12.0".equals(started.attrs().get("health")),
                            "DRINK_STARTED health must be 12.0, got "
                                    + started.attrs().get("health"));
                    check(
                            "reflex".equals(started.attrs().get("source")),
                            "DRINK_STARTED source must be reflex (reflex-owned claim), got "
                                    + started.attrs().get("source"));
                    // DRINK_COMPLETED: non-urgent, source=reflex, effects,
                    // containerType=glass_bottle.
                    BotEvent completed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_COMPLETED);
                    check(!completed.urgent(), "DRINK_COMPLETED must be non-urgent");
                    check(
                            "minecraft:healing".equals(completed.attrs().get("potionId")),
                            "DRINK_COMPLETED potionId must be minecraft:healing, got "
                                    + completed.attrs().get("potionId"));
                    check(
                            "0".equals(completed.attrs().get("slot")),
                            "DRINK_COMPLETED slot must be 0, got "
                                    + completed.attrs().get("slot"));
                    check(
                            "minecraft:instant_health:0:1"
                                    .equals(completed.attrs().get("effects")),
                            "DRINK_COMPLETED effects must be instant_health:0:1, got "
                                    + completed.attrs().get("effects"));
                    check(
                            "glass_bottle".equals(completed.attrs().get("containerType")),
                            "DRINK_COMPLETED containerType must be glass_bottle, got "
                                    + completed.attrs().get("containerType"));
                    check(
                            "reflex".equals(completed.attrs().get("source")),
                            "DRINK_COMPLETED source must be reflex, got "
                                    + completed.attrs().get("source"));
                    // STARTED precedes COMPLETED.
                    long startedIdx = events.indexOf(started);
                    long completedIdx = events.indexOf(completed);
                    check(
                            startedIdx < completedIdx,
                            "DRINK_STARTED must precede DRINK_COMPLETED (started=" + startedIdx + " completed="
                                    + completedIdx + ")");
                    // Side effect: body healed 4 HP (12 -> 16).
                    check(
                            body.getHealth() == healthBefore + 4.0f,
                            "body must heal 4 HP after reflex drink; got " + body.getHealth() + " (expected "
                                    + (healthBefore + 4.0f) + ")");
                    // Side effect: slot 0 now holds the glass bottle.
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(
                            slot0.is(Items.GLASS_BOTTLE),
                            "slot 0 must have glass bottle after reflex drink; got " + slot0);
                    check(slot0.getCount() == 1, "glass bottle count must be 1; got " + slot0.getCount());
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the harness submits a USE claim while a splash poison
     * potion is in slot 0 — the rising edge in applyUse fires
     * onUsePressEdge, the SplashPotionItem branch calls
     * body.consumption().throwHeldPotion(), which spawns a ThrownPotion entity with
     * the bot's rotation (vanilla -20deg pitch offset, 0.5 velocity)
     * and shrinks the stack. POTION_THROWN is emitted with potionId,
     * slot, throwType=splash, effects, source=harness. Pins the
     * ThrowableItem path: unlike drinkable potions (finishUsingItem,
     * 32-tick animation, glass bottle left behind), thrown potions are
     * one-shot, leave no container, and the effects resolve on impact
     * via the ThrownPotion entity (splash AoE).
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void harnessUseClaimThrowsSplashPotion(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        body.getInventory()
                .container()
                .setItem(0, PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.POISON));

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    // Re-armed every tick: claims expire at flush. The
                    // first tick with pressing=true fires the rising edge
                    // in applyUse -> onUsePressEdge -> splash branch.
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:throw", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.POTION_THROWN);
                }))
                .thenExecuteAfter(0, () -> {
                    // POTION_THROWN: non-urgent (one-shot, no preemption),
                    // carries potionId, slot, throwType, effects, source.
                    BotEvent thrown = GametestAsserts.eventOf(rig.events(), EventKind.POTION_THROWN);
                    check(!thrown.urgent(), "POTION_THROWN must be non-urgent (one-shot throw)");
                    check(
                            "minecraft:poison".equals(thrown.attrs().get("potionId")),
                            "POTION_THROWN potionId must be minecraft:poison, got "
                                    + thrown.attrs().get("potionId"));
                    check(
                            "0".equals(thrown.attrs().get("slot")),
                            "POTION_THROWN slot must be 0, got "
                                    + thrown.attrs().get("slot"));
                    check(
                            "splash".equals(thrown.attrs().get("throwType")),
                            "POTION_THROWN throwType must be splash, got "
                                    + thrown.attrs().get("throwType"));
                    check(
                            thrown.attrs().get("effects") != null
                                    && thrown.attrs().get("effects").contains("poison"),
                            "POTION_THROWN effects must contain poison, got "
                                    + thrown.attrs().get("effects"));
                    check(
                            "harness".equals(thrown.attrs().get("source")),
                            "POTION_THROWN source must be harness, got "
                                    + thrown.attrs().get("source"));
                    // Side effect: slot 0 is empty (thrown potions leave
                    // no container — unlike drinkable potions which leave
                    // a glass bottle).
                    ItemStack slot0 = body.getInventory().container().getItem(0);
                    check(slot0.isEmpty(), "slot 0 must be empty after throw; got " + slot0);
                    // Side effect: a ThrownPotion entity was spawned in the
                    // level. The projectile carries the splash potion stack
                    // and will apply poison on impact.
                    var thrownPotions = helper.getLevel()
                            .getEntitiesOfClass(
                                    net.minecraft.world.entity.projectile.ThrownPotion.class,
                                    body.getBoundingBox().inflate(8.0));
                    check(!thrownPotions.isEmpty(), "a ThrownPotion entity must exist after throw");
                    body.discard();
                })
                .thenSucceed();
    }

    // -----------------------------------------------------------------------
    // P2-d: FAILED reason subdivision (SLOT_EMPTY / NO_POTION / containerDropped)
    // -----------------------------------------------------------------------

    /**
     * Scenario (P2-d): the DRINK_ON_LOW_HEALTH reflex submits a USE claim
     * but the selected slot is empty — onUsePressEdge detects the reflex
     * owner prefix and emits DRINK_FAILED("SLOT_EMPTY") instead of silently
     * falling through to melee. The reflex owner prefix ("reflex:DRINK_")
     * carries unambiguous consumable intent; generic harness claims are
     * ambiguous and do NOT emit this failure.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void drinkReflexEmptySlotEmitsSlotEmpty(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Slot 0 explicitly empty: no potion, no item.
        body.getInventory().container().setItem(0, ItemStack.EMPTY);

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    // Reflex-owned claim: owner prefix triggers intent detection.
                    rig.actor().submit(new Claim(Channel.USE, 50, "reflex:DRINK_ON_LOW_HEALTH", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_FAILED);
                }))
                .thenExecuteAfter(0, () -> {
                    BotEvent failed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_FAILED);
                    check(
                            "SLOT_EMPTY".equals(failed.attrs().get("reason")),
                            "DRINK_FAILED reason must be SLOT_EMPTY, got "
                                    + failed.attrs().get("reason"));
                    check(
                            "0".equals(failed.attrs().get("slot")),
                            "DRINK_FAILED slot must be 0, got " + failed.attrs().get("slot"));
                    check(
                            "reflex".equals(failed.attrs().get("source")),
                            "DRINK_FAILED source must be reflex, got "
                                    + failed.attrs().get("source"));
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario (P2-d): the DRINK_ON_LOW_HEALTH reflex submits a USE claim
     * but the selected slot holds a non-potion item (a stick) —
     * onUsePressEdge emits DRINK_FAILED("NO_POTION") with the itemId of
     * the wrong item, instead of silently meleeing with it. Pins the
     * NOT_EDIBLE/NO_POTION branch: reflex intent + non-consumable item =
     * precise failure, not a weapon swing.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void drinkReflexNonPotionEmitsNoPotion(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        // Slot 0 holds a stick: not a potion, not milk, not food.
        body.getInventory().container().setItem(0, new ItemStack(Items.STICK));

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "reflex:DRINK_ON_LOW_HEALTH", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_FAILED);
                }))
                .thenExecuteAfter(0, () -> {
                    BotEvent failed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_FAILED);
                    check(
                            "NO_POTION".equals(failed.attrs().get("reason")),
                            "DRINK_FAILED reason must be NO_POTION, got "
                                    + failed.attrs().get("reason"));
                    check(
                            "minecraft:stick".equals(failed.attrs().get("itemId")),
                            "DRINK_FAILED itemId must be minecraft:stick, got "
                                    + failed.attrs().get("itemId"));
                    check(
                            "0".equals(failed.attrs().get("slot")),
                            "DRINK_FAILED slot must be 0, got " + failed.attrs().get("slot"));
                    check(
                            "reflex".equals(failed.attrs().get("source")),
                            "DRINK_FAILED source must be reflex, got "
                                    + failed.attrs().get("source"));
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Fill slots {@code fillFrom}..40 with cobblestone and place a
     * count-2 healing potion in slot 0 - the full-inventory fixture the
     * count-2 drink scenarios share (no empty slots; the only non-cobble
     * items are whatever the caller placed below {@code fillFrom}).
     *
     * @param rig      the wired rig; never null
     * @param fillFrom first slot to fill with cobblestone; 1..40
     */
    private static void fillInventoryAroundCountTwoPotion(GametestRig.Rig rig, int fillFrom) {
        var body = rig.body();
        for (int i = fillFrom; i < com.mcbot.mcbotserver.adapter.inventory.BindingInventory.CONTAINER_SIZE; i++) {
            body.getInventory().container().setItem(i, new ItemStack(Items.COBBLESTONE));
        }
        body.getInventory()
                .container()
                .setItem(0, PotionUtils.setPotion(new ItemStack(Items.POTION, 2), Potions.HEALING));
    }

    /**
     * Scenario (P2-d): drinking from a count-2 potion stack while the
     * entire inventory (slots 1-40, all non-selected) is full — the
     * glass bottle cannot fit and is dropped at the body's location.
     * DRINK_COMPLETED carries {@code containerDropped=true}. Pins the
     * consumeDrink count{@code >}1 branch: the shrunk potion stack stays
     * in slot 0, and addOrDrop scans all 41 slots, finds none empty,
     * and calls spawnAtLocation.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void drinkCountTwoFullInventoryDropsContainer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        body.setHealth(10.0f);
        // Fill every non-selected slot (1 through 40 = main + armor + offhand)
        // so addOrDrop has nowhere to put the glass bottle.
        fillInventoryAroundCountTwoPotion(rig, 1);

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:drink", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_COMPLETED);
                }))
                .thenExecuteAfter(0, () -> {
                    BotEvent completed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_COMPLETED);
                    GametestAsserts.assertAttr(completed, "containerDropped", "true");
                    GametestAsserts.assertAttr(completed, "containerType", "glass_bottle");
                    // Slot 0 keeps the shrunk potion stack (count 1).
                    GametestAsserts.assertSlot(body.getInventory().container(), 0, Items.POTION, 1);
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: count-2 potion with a glass bottle already in slot 1 and
     * all other slots full of cobblestone — the recovered bottle must
     * MERGE into the existing stack (slot 1 count 1→2), not be dropped.
     * This pins the vanilla {@code placeItemBackInInventory} merge path
     * (getSlotWithRemainingSpace: selected → offhand → main 0..35).
     * Before the addOrDrop rewrite, the bottle was dropped on the ground
     * and containerDropped was reported true — a vanilla fidelity gap.
     */
    // capability: consumable.potion
    @GameTest(template = "empty16x8x16", timeoutTicks = 60)
    public static void drinkCountTwoMergesBottleIntoExistingStack(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        body.setHealth(10.0f);
        // Slot 1: one glass bottle — the merge target.
        body.getInventory().container().setItem(1, new ItemStack(Items.GLASS_BOTTLE));
        // Fill slots 2 through 40 (main remainder + armor + offhand) with
        // cobblestone so there are no empty slots and no other merge targets.
        fillInventoryAroundCountTwoPotion(rig, 2);

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:drink", new Intent.Use(true)));
                    GametestAsserts.assertEventSeen(rig.events(), EventKind.DRINK_COMPLETED);
                }))
                .thenExecuteAfter(0, () -> {
                    BotEvent completed = GametestAsserts.eventOf(rig.events(), EventKind.DRINK_COMPLETED);
                    GametestAsserts.assertAttr(completed, "containerDropped", "false");
                    // Slot 0 keeps the shrunk potion (count 1); slot 1's
                    // glass bottle merged in (count 1 → 2).
                    GametestAsserts.assertSlot(body.getInventory().container(), 0, Items.POTION, 1);
                    GametestAsserts.assertSlot(body.getInventory().container(), 1, Items.GLASS_BOTTLE, 2);
                    body.discard();
                })
                .thenSucceed();
    }

    // -----------------------------------------------------------------------
    // Brewing stand menu interactions (capability: inventory.menu_clicks)
    // -----------------------------------------------------------------------

    /**
     * Scenario: the brewing stand full brew cycle end to end — water
     * bottle in slot 0, nether wart in the ingredient slot, blaze powder
     * in the fuel slot; the stand brews for 400 ticks and the water
     * bottle becomes an awkward potion. Pins the menu slot routing
     * (BOTTLE / INGREDIENT / FUEL roles) and the engine-side brewing
     * tick through the real BlockEntity.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 1200)
    public static void brewingStandBrewsWaterBottleWithNetherWart(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos standLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(standLocal, Blocks.BREWING_STAND);
        BlockPos standAbs = helper.absolutePos(standLocal);

        rig.body()
                .getInventory()
                .container()
                .setItem(0, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.NETHER_WART));
        rig.body().getInventory().container().setItem(2, new ItemStack(Items.BLAZE_POWDER));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(standAbs));
        check(view != null, "opening the brewing stand must succeed");
        checkEquals("brewing_stand", view.type(), "menu type must be brewing_stand");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 2, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);

        check(!view.slot(0).isEmpty(), "water bottle must land in bottle slot 0");
        check(!view.slot(3).isEmpty(), "nether wart must land in ingredient slot 3");
        check(!view.slot(4).isEmpty(), "blaze powder must land in fuel slot 4");
        tx.closeMenu();

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    var be = helper.getLevel().getBlockEntity(standAbs);
                    check(
                            be instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity,
                            "brewing stand block entity must exist");
                    var result = ((net.minecraft.world.level.block.entity.BrewingStandBlockEntity) be).getItem(0);
                    check(!result.isEmpty(), "bottle slot must not be empty");
                    check(
                            Potions.AWKWARD.equals(PotionUtils.getPotion(result)),
                            "the result must be awkward potion, got " + PotionUtils.getPotion(result));
                }))
                .thenExecuteAfter(0, () -> {
                    var tx2 = rig.actor().menuTransactions();
                    var view2 = tx2.openMenu(GametestRig.cellOf(standAbs));
                    check(view2 != null, "reopening must succeed");
                    var result = view2.slot(0);
                    check(!result.isEmpty(), "result slot must contain a potion");
                    checkEquals("minecraft:potion", result.item().itemId(), "result item must be minecraft:potion");
                    tx2.closeMenu();
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the brewing stand fuel slot accepts blaze powder but
     * rejects non-fuel items via QUICK_MOVE routing. A coal in the
     * hotbar must stay in the hotbar after a QUICK_MOVE — the vanilla
     * BrewingStandMenu slot logic refuses it in the fuel slot.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void brewingStandFuelSlotRejectsNonBlazePowder(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos standLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(standLocal, Blocks.BREWING_STAND);
        BlockPos standAbs = helper.absolutePos(standLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIRT));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(standAbs));
        check(view != null, "opening the brewing stand must succeed");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);

        check(view.slot(4).isEmpty(), "fuel slot must not accept non-fuel items");
        boolean dirtStillInInventory = false;
        for (var slot : view.slots()) {
            if ((slot.role() == com.mcbot.mcbotserver.api.menu.SlotRole.HOTBAR
                            || slot.role() == com.mcbot.mcbotserver.api.menu.SlotRole.MAIN)
                    && !slot.isEmpty()
                    && "minecraft:dirt".equals(slot.item().itemId())) {
                dirtStillInInventory = true;
                break;
            }
        }
        check(dirtStillInInventory, "non-fuel item must remain in the player inventory after rejected QUICK_MOVE");
        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: three water bottles in all three bottle slots brew
     * simultaneously — after 400 ticks all three become awkward potions
     * and the nether wart is consumed. Pins the multi-bottle brew path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 1200)
    public static void brewingStandThreeBottlesBrewSimultaneously(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos standLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(standLocal, Blocks.BREWING_STAND);
        BlockPos standAbs = helper.absolutePos(standLocal);

        for (int i = 0; i < 3; i++) {
            rig.body()
                    .getInventory()
                    .container()
                    .setItem(i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        }
        rig.body().getInventory().container().setItem(3, new ItemStack(Items.NETHER_WART));
        rig.body().getInventory().container().setItem(4, new ItemStack(Items.BLAZE_POWDER));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(standAbs));
        check(view != null, "opening the brewing stand must succeed");

        int hotbar0 = firstHotbarSlot(view);
        for (int i = 0; i < 5; i++) {
            view = tx.menuClick(hotbar0 + i, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);
        }

        for (int s = 0; s < 3; s++) {
            check(!view.slot(s).isEmpty(), "bottle slot " + s + " must contain a water bottle");
        }
        check(!view.slot(3).isEmpty(), "ingredient slot must contain nether wart");
        check(!view.slot(4).isEmpty(), "fuel slot must contain blaze powder");
        tx.closeMenu();

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    var be = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity)
                            helper.getLevel().getBlockEntity(standAbs);
                    check(be != null, "brewing stand must exist");
                    for (int s = 0; s < 3; s++) {
                        var result = be.getItem(s);
                        check(!result.isEmpty(), "bottle slot " + s + " must not be empty");
                        check(
                                Potions.AWKWARD.equals(PotionUtils.getPotion(result)),
                                "slot " + s + " must be awkward potion, got " + PotionUtils.getPotion(result));
                    }
                }))
                .thenExecuteAfter(0, () -> {
                    var be = (net.minecraft.world.level.block.entity.BrewingStandBlockEntity)
                            helper.getLevel().getBlockEntity(standAbs);
                    check(be.getItem(3).isEmpty(), "nether wart must be consumed after brewing");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the brewing stand slot roles are correct — slots 0-2
     * are BOTTLE, slot 3 is INGREDIENT, slot 4 is FUEL, and the player
     * region splits into MAIN and HOTBAR. Pins the MenuSlotLayouts
     * brewingRole table against the live engine menu.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void brewingStandSlotRolesAreCorrect(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos standLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(standLocal, Blocks.BREWING_STAND);
        BlockPos standAbs = helper.absolutePos(standLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(standAbs));
        check(view != null, "opening the brewing stand must succeed");

        for (int s = 0; s < 3; s++) {
            checkEquals(
                    com.mcbot.mcbotserver.api.menu.SlotRole.BOTTLE,
                    view.slot(s).role(),
                    "slot " + s + " must be BOTTLE");
        }
        checkEquals(
                com.mcbot.mcbotserver.api.menu.SlotRole.INGREDIENT, view.slot(3).role(), "slot 3 must be INGREDIENT");
        checkEquals(com.mcbot.mcbotserver.api.menu.SlotRole.FUEL, view.slot(4).role(), "slot 4 must be FUEL");

        boolean hasMain = false;
        boolean hasHotbar = false;
        for (var slot : view.slots()) {
            if (slot.role() == com.mcbot.mcbotserver.api.menu.SlotRole.MAIN) hasMain = true;
            if (slot.role() == com.mcbot.mcbotserver.api.menu.SlotRole.HOTBAR) hasHotbar = true;
        }
        check(hasMain, "menu must have MAIN slots");
        check(hasHotbar, "menu must have HOTBAR slots");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /** First HOTBAR-role slot index in a menu view. */
    private static int firstHotbarSlot(com.mcbot.mcbotserver.api.menu.MenuView view) {
        for (var slot : view.slots()) {
            if (slot.role() == com.mcbot.mcbotserver.api.menu.SlotRole.HOTBAR) {
                return slot.index();
            }
        }
        throw new IllegalStateException("no HOTBAR slot in menu view");
    }
}
