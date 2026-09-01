package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
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
        // Healing potion = instant_health I: heals 4 hearts = 8 HP.
        ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING);
        body.getInventory().container().setItem(0, potion);

        boolean consumed = body.drinkHeldItem();
        check(consumed, "healing potion must be consumable via drinkHeldItem");
        // instant_health I heals 8 HP (4 << 0 = 4 hearts = 8 HP).
        check(
                body.getHealth() == healthBefore + 8.0f,
                "healing potion must restore 8 HP; got " + body.getHealth() + " (expected " + (healthBefore + 8.0f)
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
}
