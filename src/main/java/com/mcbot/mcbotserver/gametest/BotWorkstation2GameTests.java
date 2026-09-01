package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.cellOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.adapter.BotPlayerFacade;
import com.mcbot.mcbotserver.adapter.MenuOpener;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

/**
 * Gametests for the remaining workstation / container blocks not covered
 * by BotWorkstationGameTests or BotContainerGameTests: cartography
 * table, lectern, enchanting table, campfire (no menu), trapped chest,
 * and ender chest.
 *
 * <p>Two core-code fixes are pinned here:
 * <ul>
 *   <li>EnderChestBlock has no getMenuProvider — vanilla opens it via
 *       SimpleMenuProvider in use(). MenuOpener now has an explicit
 *       EnderChestBlock branch that builds ChestMenu.threeRows against
 *       the facade's PlayerEnderChestContainer.</li>
 *   <li>LecternMenu has a single book slot and no player region. The
 *       containerRole fallback mislabeled slot 0 as HOTBAR (size-37 is
 *       negative). MenuSlotLayouts now has an explicit LecternMenu row.</li>
 * </ul>
 */
public final class BotWorkstation2GameTests {

    private BotWorkstation2GameTests() {}

    /**
     * Scenario: a cartography table opens through the generic fallback,
     * exposes 2 input slots (paper + map) and 1 result slot, and the
     * menu type is "cartography_table". CartographyTableMenu is in the
     * KINDS table with anvilRole (INPUT/INPUT/RESULT layout).
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void cartographyTableOpensWithThreeSlots(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos cartLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(cartLocal, Blocks.CARTOGRAPHY_TABLE);
        BlockPos cartAbs = helper.absolutePos(cartLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(cartAbs));
        check(view != null, "cartography table menu must open");
        checkEquals("cartography_table", view.type(), "menu type must be cartography_table");

        // 2 INPUT + 1 RESULT = 3 non-player slots
        int inputCount = 0;
        int resultCount = 0;
        for (int i = 0; i < 3; i++) {
            if (view.slot(i).role() == SlotRole.INPUT) {
                inputCount++;
            } else if (view.slot(i).role() == SlotRole.RESULT) {
                resultCount++;
            }
        }
        checkEquals(2, inputCount, "cartography table must have 2 INPUT slots");
        checkEquals(1, resultCount, "cartography table must have 1 RESULT slot");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a lectern with a book opens through the generic fallback,
     * exposes exactly 1 CONTAINER slot (the book), and the menu type is
     * "lectern". A lectern without a book returns null from
     * getMenuProvider (LecternBlock.HAS_BOOK guard), so MenuOpener
     * returns empty.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void lecternWithBookOpensWithOneSlot(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos lecternLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(lecternLocal, Blocks.LECTERN);
        BlockPos lecternAbs = helper.absolutePos(lecternLocal);

        // Place a book in the lectern and set HAS_BOOK=true.
        var be = helper.getLevel().getBlockEntity(lecternAbs);
        check(be instanceof LecternBlockEntity, "lectern block entity must exist");
        LecternBlockEntity lecternBe = (LecternBlockEntity) be;
        lecternBe.setBook(new ItemStack(Items.WRITABLE_BOOK));
        helper.getLevel()
                .setBlock(
                        lecternAbs,
                        helper.getLevel().getBlockState(lecternAbs).setValue(LecternBlock.HAS_BOOK, true),
                        3);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(lecternAbs));
        check(view != null, "lectern with book must open");
        checkEquals("lectern", view.type(), "menu type must be lectern");
        checkEquals(1, containerSlotsOf(view), "lectern exposes 1 container slot (the book)");
        checkEquals(SlotRole.CONTAINER, view.slot(0).role(), "lectern slot 0 must be CONTAINER");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a lectern without a book cannot be opened —
     * LecternBlock.getMenuProvider returns null when HAS_BOOK is false.
     * Pins the vanilla guard so the bot does not silently open an empty
     * lectern menu.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void lecternWithoutBookDoesNotOpen(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos lecternLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(lecternLocal, Blocks.LECTERN);
        BlockPos lecternAbs = helper.absolutePos(lecternLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(lecternAbs);
        check(menuOpt.isEmpty(), "lectern without a book must not open (HAS_BOOK=false)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: an enchanting table opens through the generic fallback,
     * exposes 2 INPUT slots (item + lapis) and the menu type is
     * "enchanting_table". The 3 enchantment option buttons are
     * clickMenuButton, not slots. EnchantmentMenu is in the KINDS table
     * with enchantingRole.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void enchantingTableOpensWithTwoInputSlots(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos enchantLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(enchantLocal, Blocks.ENCHANTING_TABLE);
        BlockPos enchantAbs = helper.absolutePos(enchantLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(enchantAbs));
        check(view != null, "enchanting table menu must open");
        checkEquals("enchanting_table", view.type(), "menu type must be enchanting_table");

        // 2 INPUT slots (item + lapis)
        int inputCount = 0;
        for (int i = 0; i < 2; i++) {
            if (view.slot(i).role() == SlotRole.INPUT) {
                inputCount++;
            }
        }
        checkEquals(2, inputCount, "enchanting table must have 2 INPUT slots");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a campfire has no menu — it cooks food via right-click
     * placement, not a MenuProvider. MenuOpener must return empty.
     * Pins that the bot does not try to open a campfire menu.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void campfireHasNoMenu(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos campLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(campLocal, Blocks.CAMPFIRE);
        BlockPos campAbs = helper.absolutePos(campLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(campAbs);
        check(menuOpt.isEmpty(), "campfire must not open a menu (no MenuProvider)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a trapped chest opens through the ChestBlock branch
     * (TrappedChestBlock extends ChestBlock), exposes 27 container
     * slots, and the menu type is "chest" (openChest hardcodes the type
     * — trapped and normal chest share ChestMenu). A deposit/retrieve
     * round trip proves the container is the real block entity.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void trappedChestOpensAndStoresItems(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.TRAPPED_CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.GOLD_INGOT, 12));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(chestAbs));
        check(view != null, "trapped chest menu must open");
        checkEquals("chest", view.type(), "trapped chest uses same ChestMenu, type is 'chest'");
        checkEquals(27, containerSlotsOf(view), "trapped chest exposes 27 container slots");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        view = tx.openMenu(cellOf(chestAbs));
        int found = -1;
        for (int i = 0; i < 27; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:gold_ingot")) {
                found = i;
                break;
            }
        }
        check(found >= 0, "gold ingot must persist in trapped chest after close/reopen");
        checkEquals(12, view.slot(found).item().count(), "trapped chest stack count must be 12");

        view = tx.menuClick(found, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold retrieved gold ingot");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: an ender chest opens through the explicit EnderChestBlock
     * branch in MenuOpener (EnderChestBlock has no getMenuProvider —
     * vanilla opens it via SimpleMenuProvider in use()). Exposes 27
     * container slots backed by the facade's PlayerEnderChestContainer,
     * menu type is "ender_chest". A deposit/retrieve round trip proves
     * the ender chest inventory is real and persists.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void enderChestOpensAndStoresItems(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos enderLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(enderLocal, Blocks.ENDER_CHEST);
        BlockPos enderAbs = helper.absolutePos(enderLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.EMERALD, 20));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(enderAbs));
        check(view != null, "ender chest menu must open (explicit EnderChestBlock branch)");
        checkEquals("ender_chest", view.type(), "menu type must be ender_chest");
        checkEquals(27, containerSlotsOf(view), "ender chest exposes 27 container slots");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        // Reopen: ender chest inventory is per-player (per-facade), persists.
        view = tx.openMenu(cellOf(enderAbs));
        int found = -1;
        for (int i = 0; i < 27; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:emerald")) {
                found = i;
                break;
            }
        }
        check(found >= 0, "emerald must persist in ender chest after close/reopen");
        checkEquals(20, view.slot(found).item().count(), "ender chest stack count must be 20");

        view = tx.menuClick(found, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold retrieved emerald");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: an ender chest blocked by a redstone conductor above it
     * cannot be opened — vanilla EnderChestBlock.use checks
     * isRedstoneConductor on the block above. Pins the parity guard.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void enderChestBlockedByRedstoneConductorDoesNotOpen(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos enderLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(enderLocal, Blocks.ENDER_CHEST);
        // Place a solid block (redstone conductor) above the ender chest.
        helper.setBlock(enderLocal.above(), Blocks.STONE);
        BlockPos enderAbs = helper.absolutePos(enderLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(enderAbs);
        check(menuOpt.isEmpty(), "ender chest with redstone conductor above must not open");

        rig.body().discard();
        helper.succeed();
    }

    // ---- helpers (duplicated per test-file; see BotInventoryGameTests) ----

    /** Count of CONTAINER-role slots in a menu snapshot. */
    private static int containerSlotsOf(MenuView view) {
        int total = 0;
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.CONTAINER) {
                total++;
            }
        }
        return total;
    }

    /** Flat index of the first HOTBAR-role slot (player hotbar start). */
    private static int firstHotbarSlot(MenuView view) {
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.HOTBAR) {
                return slot.index();
            }
        }
        throw new IllegalStateException("menu has no HOTBAR slot: type=" + view.type());
    }
}
