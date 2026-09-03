package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveTick;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Anvil and grindstone menu interactions (capability:
 * inventory.menu_clicks). Covers the anvil's three operations —
 * enchantment transfer from book, material repair, and prior-work
 * penalty escalation — and the grindstone's disenchant + repair-by-
 * combine paths. Every scenario drives the real BlockEntity through
 * the menu transaction layer.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotAnvilGameTests {

    private BotAnvilGameTests() {}

    /**
     * Scenario: the anvil transfers a sharpness I enchantment from an
     * enchanted book onto an iron sword. The book goes in the right
     * input slot, the sword in the left; the output slot yields an
     * enchanted sword and the operation costs 1 level. Pins the
     * anvil's enchantment-transfer path through the facade's
     * experienceLevel sync (the mayPickup gate reads it).
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void anvilCombinesEnchantmentsFromBook(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos anvilLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(anvilLocal, Blocks.ANVIL);
        BlockPos anvilAbs = helper.absolutePos(anvilLocal);

        rig.body().experience().addLevels(10);
        var sword = new ItemStack(Items.IRON_SWORD);
        var book = new ItemStack(Items.ENCHANTED_BOOK);
        net.minecraft.world.item.EnchantedBookItem.addEnchantment(
                book, new net.minecraft.world.item.enchantment.EnchantmentInstance(Enchantments.SHARPNESS, 1));
        rig.body().getInventory().container().setItem(0, sword);
        rig.body().getInventory().container().setItem(1, book);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(anvilAbs));
        check(view != null, "opening the anvil must succeed");
        checkEquals("anvil", view.type(), "menu type must be anvil");

        int hotbar0 = firstHotbarSlot(view);
        // Sword in left input (slot 0), book in right input (slot 1).
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        check(!view.slot(0).isEmpty(), "sword must land in anvil left input slot 0");
        check(!view.slot(1).isEmpty(), "book must land in anvil right input slot 1");

        // Take the output (slot 2).
        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        check(view.carried() != null && !view.carried().isEmpty(), "the enchanted result must be takeable");
        check(view.slot(0).isEmpty(), "the take must consume the input sword");
        check(view.slot(1).isEmpty(), "the take must consume the book");

        // Put the result back in the hotbar.
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();

        var result = rig.body().getInventory().container().getItem(0);
        check(result.is(Items.IRON_SWORD), "the result must be an iron sword");
        check(
                result.getEnchantmentLevel(Enchantments.SHARPNESS) == 1,
                "the result must carry sharpness I, got " + result.getEnchantmentLevel(Enchantments.SHARPNESS));
        check(rig.body().experience().getLevel() < 10, "the anvil operation must cost experience levels");
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the anvil repairs a damaged iron pickaxe with iron
     * ingots. The pickaxe goes in the left input, an iron ingot in the
     * right; the output slot yields a repaired pickaxe. Pins the
     * anvil's material-repair path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void anvilRepairsItemWithMaterial(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos anvilLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(anvilLocal, Blocks.ANVIL);
        BlockPos anvilAbs = helper.absolutePos(anvilLocal);

        rig.body().experience().addLevels(10);
        var pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(50); // damaged
        rig.body().getInventory().container().setItem(0, pickaxe);
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.IRON_INGOT));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(anvilAbs));
        check(view != null, "opening the anvil must succeed");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);

        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        check(view.carried() != null && !view.carried().isEmpty(), "the repaired result must be takeable");
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();

        var result = rig.body().getInventory().container().getItem(0);
        check(result.is(Items.IRON_PICKAXE), "the result must be an iron pickaxe");
        check(
                result.getDamageValue() < 50,
                "the pickaxe must be repaired (damage < 50), got " + result.getDamageValue());
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the anvil prior-work penalty escalates — renaming the
     * same item twice costs more levels the second time. The first
     * rename costs 1 level; the second rename on the already-worked
     * item costs more. Pins the anvil's repair-cost penalty
     * (the `BaseRepairCost` NBT field that AnvilMenu reads).
     */
    /**
     * Scenario: the anvil's prior-work penalty escalates across
     * operations. A damaged iron sword is repaired with iron ingots
     * (which triggers the RepairCost increase because k==0), then the
     * same item is renamed — the second operation's cost must include
     * the base RepairCost from the first, so it costs strictly more
     * than a fresh rename. Pins the anvil's penalty-escalation path
     * through createResult()'s k2 calculation.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void anvilPriorWorkPenaltyIncreases(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos anvilLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(anvilLocal, Blocks.ANVIL);
        BlockPos anvilAbs = helper.absolutePos(anvilLocal);

        rig.body().experience().addLevels(30);
        // A damaged iron sword — repair will trigger RepairCost increase.
        var damagedSword = new ItemStack(Items.IRON_SWORD);
        damagedSword.setDamageValue(100);
        rig.body().getInventory().container().setItem(0, damagedSword);
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.IRON_INGOT, 4));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(anvilAbs));
        check(view != null, "opening the anvil must succeed");

        int hotbar0 = firstHotbarSlot(view);
        // First operation: repair the damaged sword.
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        view = tx.menuSnapshot();
        check(!view.slot(2).isEmpty(), "the repair result must be available");
        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();
        int levelsAfterRepair = rig.body().experience().getLevel();
        var repaired = rig.body().getInventory().container().getItem(0);
        check(repaired.getBaseRepairCost() > 0, "repair must increase the item's RepairCost");
        check(levelsAfterRepair < 30, "repair must cost experience levels");

        // Second operation: rename the same item. The base cost now
        // includes the RepairCost from the repair, so it costs more
        // than a fresh rename on an undamaged item.
        var tx2 = rig.actor().menuTransactions();
        var view2 = tx2.openMenu(GametestRig.cellOf(anvilAbs));
        check(view2 != null, "reopening the anvil must succeed");
        tx2.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx2.setAnvilName("Renamed");
        view2 = tx2.menuSnapshot();
        check(!view2.slot(2).isEmpty(), "the rename result must be available");
        view2 = tx2.menuClick(2, 0, MenuClick.PICKUP);
        tx2.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx2.closeMenu();
        int levelsAfterRename = rig.body().experience().getLevel();

        int costRepair = 30 - levelsAfterRepair;
        int costRename = levelsAfterRepair - levelsAfterRename;
        check(costRepair >= 1, "repair must cost at least 1 level");
        check(costRename >= 1, "rename must cost at least 1 level");
        // The rename on a penalty-bearing item costs baseRepairCost + 1,
        // which is strictly more than a fresh rename (1 level).
        check(
                costRename > 1,
                "rename on a penalty-bearing item must cost more than 1 level (base RepairCost + rename), got "
                        + costRename);
        var finalItem = rig.body().getInventory().container().getItem(0);
        checkEquals("Renamed", finalItem.getHoverName().getString(), "the item must carry the rename");
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the grindstone removes a sharpness enchantment from an
     * iron sword and returns the experience. The enchanted sword goes
     * in an input slot; the output yields a clean sword, and the bot
     * gains experience levels. Pins the grindstone's disenchant path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 600)
    public static void grindstoneRemovesEnchantmentsAndReturnsXP(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos grindLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(grindLocal, Blocks.GRINDSTONE);
        BlockPos grindAbs = helper.absolutePos(grindLocal);

        var sword = new ItemStack(Items.IRON_SWORD);
        // Sharpness V, not a low level: the grindstone returns
        // ceil(minCost(level)/2) + random(min) points (Sharpness minCost
        // = 11*level - 10), so a Sharpness II yield of 6..11 points
        // sometimes never crosses the 7-point level-up floor. Level V
        // yields 23..45 points - the level increase is deterministic,
        // which is what the scenario pins.
        sword.enchant(Enchantments.SHARPNESS, 5);
        rig.body().getInventory().container().setItem(0, sword);
        int xpBefore = rig.body().experience().getLevel();

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(grindAbs));
        check(view != null, "opening the grindstone must succeed");
        checkEquals("grindstone", view.type(), "menu type must be grindstone");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        check(!view.slot(0).isEmpty() || !view.slot(1).isEmpty(), "the sword must land in a grindstone input slot");

        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        check(view.carried() != null && !view.carried().isEmpty(), "the disenchanted result must be takeable");
        // onTake clears both input slots (ForgeHooks.onGrindstoneTake) — verifies the callback fired.
        check(view.slot(0).isEmpty() && view.slot(1).isEmpty(), "grindstone onTake must clear both input slots");
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();

        var result = rig.body().getInventory().container().getItem(0);
        check(result.is(Items.IRON_SWORD), "the result must be an iron sword");
        check(result.getEnchantmentLevel(Enchantments.SHARPNESS) == 0, "the result must have no sharpness enchantment");

        // Grindstone onTake spawns experience orbs at the block center via
        // ContainerLevelAccess.execute, and the body's tickXpPickup scan
        // absorbs them in customServerAiStep. In production the world's
        // entity tick runs that scan every tick; in a pooled gametest
        // batch the world's entity tick for this body lands at
        // unpredictable offsets (a 200-tick poll has gone without one),
        // so the poll drives the body's own production tick explicitly
        // - the same customServerAiStep the world would run - and never
        // races the scheduler.
        helper.startSequence()
                .thenWaitUntil(() -> {
                    driveTick(rig);
                    rig.body().tick();
                    check(
                            rig.body().experience().getLevel() > xpBefore,
                            "waiting for grindstone XP pickup (level > " + xpBefore + ")");
                })
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            rig.body().experience().getLevel() > xpBefore,
                            "grindstone must return experience (level increased); xpBefore=" + xpBefore + " xpAfter="
                                    + rig.body().experience().getLevel());
                    rig.body().discard();
                    helper.succeed();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the grindstone repairs a damaged item by combining two
     * damaged swords. Two damaged iron swords go in the two input slots;
     * the output yields a repaired sword with combined durability plus
     * a 5% bonus. Pins the grindstone's repair-by-combine path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void grindstoneRepairsItemByCombining(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos grindLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(grindLocal, Blocks.GRINDSTONE);
        BlockPos grindAbs = helper.absolutePos(grindLocal);

        var sword1 = new ItemStack(Items.IRON_SWORD);
        sword1.setDamageValue(100);
        var sword2 = new ItemStack(Items.IRON_SWORD);
        sword2.setDamageValue(100);
        rig.body().getInventory().container().setItem(0, sword1);
        rig.body().getInventory().container().setItem(1, sword2);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(grindAbs));
        check(view != null, "opening the grindstone must succeed");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);

        view = tx.menuClick(2, 0, MenuClick.PICKUP);
        check(view.carried() != null && !view.carried().isEmpty(), "the repaired result must be takeable");
        tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        tx.closeMenu();

        var result = rig.body().getInventory().container().getItem(0);
        check(result.is(Items.IRON_SWORD), "the result must be an iron sword");
        check(
                result.getDamageValue() < 100,
                "the combined sword must be repaired (damage < 100), got " + result.getDamageValue());
        rig.body().discard();
        helper.succeed();
    }

    /** First HOTBAR-role slot index in a menu view. */
    private static int firstHotbarSlot(MenuView view) {
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.HOTBAR) {
                return slot.index();
            }
        }
        throw new IllegalStateException("no HOTBAR slot in menu view");
    }
}
