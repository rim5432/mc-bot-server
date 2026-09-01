package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
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

        boolean consumed = body.drinkHeldItem();
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

        boolean consumed = body.drinkHeldItem();
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

        boolean consumed = body.drinkMilk();
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
        view = tx.menuClick(hotbar0, 0, com.mcbot.mcbotserver.api.menu.MenuClick.QUICK_MOVE);
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
