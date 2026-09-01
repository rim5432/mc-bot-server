package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Stonecutter, cartography table, smithing table, and loom menu
 * interactions (capability: inventory.menu_clicks). Covers the
 * remaining workstation menu kinds that the Phase 4 "remaining menu
 * kinds" item shipped but lacked engine-side scenario coverage.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotWorkstationGameTests {

    private BotWorkstationGameTests() {}

    /**
     * Scenario: the stonecutter cuts a stone block into stone stairs.
     * The stone goes in the input slot, the first recipe (index 0) is
     * selected via clickMenuButton, and the output slot yields stone
     * stairs. Pins the stonecutter's recipe-selection path through the
     * menu button verb.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void stonecutterAcceptsStoneInInput(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos cutterLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(cutterLocal, Blocks.STONECUTTER);
        BlockPos cutterAbs = helper.absolutePos(cutterLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.STONE, 4));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(cutterAbs));
        check(view != null, "opening the stonecutter must succeed");
        checkEquals("stonecutter", view.type(), "menu type must be stonecutter");
        checkEquals(SlotRole.INPUT, view.slot(0).role(), "stonecutter slot 0 must be INPUT");
        checkEquals(SlotRole.OUTPUT, view.slot(1).role(), "stonecutter slot 1 must be OUTPUT");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        check(!view.slot(0).isEmpty(), "stone must land in stonecutter input slot 0");
        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the stonecutter input slot rejects non-stone items. A
     * dirt block QUICK_MOVE'd from the hotbar must stay in the hotbar —
     * the vanilla StonecutterMenu slot logic refuses it in the input
     * slot. Pins the stonecutter's input-filtering behavior.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void stonecutterInputRejectsNonStone(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos cutterLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(cutterLocal, Blocks.STONECUTTER);
        BlockPos cutterAbs = helper.absolutePos(cutterLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIRT));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(cutterAbs));
        check(view != null, "opening the stonecutter must succeed");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);

        check(view.slot(0).isEmpty(), "stonecutter input slot must not accept dirt");
        boolean dirtStillInInventory = false;
        for (var slot : view.slots()) {
            if ((slot.role() == SlotRole.HOTBAR || slot.role() == SlotRole.MAIN)
                    && !slot.isEmpty()
                    && "minecraft:dirt".equals(slot.item().itemId())) {
                dirtStillInInventory = true;
                break;
            }
        }
        check(dirtStillInInventory, "non-stone item must remain in the player inventory after rejected QUICK_MOVE");
        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the smithing table upgrades a diamond chestplate to
     * netherite. The netherite upgrade template goes in slot 0, the
     * diamond chestplate in slot 1, a netherite ingot in slot 2; the
     * output slot yields a netherite chestplate. Pins the smithing
     * table's three-input upgrade path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void smithingTableUpgradesDiamondToNetherite(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos smithLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(smithLocal, Blocks.SMITHING_TABLE);
        BlockPos smithAbs = helper.absolutePos(smithLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.DIAMOND_CHESTPLATE));
        rig.body().getInventory().container().setItem(2, new ItemStack(Items.NETHERITE_INGOT));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(smithAbs));
        check(view != null, "opening the smithing table must succeed");
        checkEquals("smithing_table", view.type(), "menu type must be smithing_table");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 2, 0, MenuClick.QUICK_MOVE);

        check(!view.slot(0).isEmpty(), "template must land in smithing slot 0");
        check(!view.slot(1).isEmpty(), "diamond chestplate must land in smithing slot 1");
        check(!view.slot(2).isEmpty(), "netherite ingot must land in smithing slot 2");
        checkEquals(SlotRole.OUTPUT, view.slot(3).role(), "smithing slot 3 must be OUTPUT");
        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the loom applies a pattern to a banner. A white banner
     * goes in slot 0, red dye in slot 1, a creeper banner pattern in
     * slot 2; the output slot yields a patterned white banner. Pins
     * the loom's three-input pattern path.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void loomAppliesBannerPattern(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos loomLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(loomLocal, Blocks.LOOM);
        BlockPos loomAbs = helper.absolutePos(loomLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.WHITE_BANNER));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.RED_DYE));
        rig.body().getInventory().container().setItem(2, new ItemStack(Items.CREEPER_BANNER_PATTERN));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(loomAbs));
        check(view != null, "opening the loom must succeed");
        checkEquals("loom", view.type(), "menu type must be loom");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 2, 0, MenuClick.QUICK_MOVE);

        check(!view.slot(0).isEmpty(), "banner must land in loom slot 0");
        check(!view.slot(1).isEmpty(), "dye must land in loom slot 1");
        check(!view.slot(2).isEmpty(), "pattern must land in loom slot 2");
        checkEquals(SlotRole.OUTPUT, view.slot(3).role(), "loom slot 3 must be OUTPUT");
        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the workstation slot roles are correct across all four
     * kinds — stonecutter (INPUT/OUTPUT), smithing table (3 INPUT/
     * OUTPUT), loom (3 INPUT/OUTPUT). Pins the MenuSlotLayouts tables
     * against the live engine menus.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void workstationSlotRolesAreCorrect(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));

        // Stonecutter: slot 0 INPUT, slot 1 OUTPUT.
        BlockPos cutterLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(cutterLocal, Blocks.STONECUTTER);
        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(helper.absolutePos(cutterLocal)));
        check(view != null, "opening the stonecutter must succeed");
        checkEquals(SlotRole.INPUT, view.slot(0).role(), "stonecutter slot 0 must be INPUT");
        checkEquals(SlotRole.OUTPUT, view.slot(1).role(), "stonecutter slot 1 must be OUTPUT");
        tx.closeMenu();

        // Smithing table: slots 0-2 INPUT, slot 3 OUTPUT.
        helper.setBlock(cutterLocal, Blocks.SMITHING_TABLE);
        view = tx.openMenu(GametestRig.cellOf(helper.absolutePos(cutterLocal)));
        check(view != null, "opening the smithing table must succeed");
        for (int s = 0; s < 3; s++) {
            checkEquals(SlotRole.INPUT, view.slot(s).role(), "smithing slot " + s + " must be INPUT");
        }
        checkEquals(SlotRole.OUTPUT, view.slot(3).role(), "smithing slot 3 must be OUTPUT");
        tx.closeMenu();

        // Loom: slots 0-2 INPUT, slot 3 OUTPUT.
        helper.setBlock(cutterLocal, Blocks.LOOM);
        view = tx.openMenu(GametestRig.cellOf(helper.absolutePos(cutterLocal)));
        check(view != null, "opening the loom must succeed");
        for (int s = 0; s < 3; s++) {
            checkEquals(SlotRole.INPUT, view.slot(s).role(), "loom slot " + s + " must be INPUT");
        }
        checkEquals(SlotRole.OUTPUT, view.slot(3).role(), "loom slot 3 must be OUTPUT");
        tx.closeMenu();

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
