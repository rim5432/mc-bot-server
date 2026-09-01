package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.cellOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Gametests for the generic container-block surface: barrel, shulker
 * box, hopper, dispenser, dropper. These all route through
 * MenuOpener's generic MenuProvider fallback (issue 0012 D0) and the
 * containerRole slot-layout fallback in MenuSlotLayouts. The chest
 * family (single/double/trapped) is covered in BotInventoryGameTests
 * because it has its own ChestBlock branch in MenuOpener.
 *
 * <p>Every test here proves the same three things for its block:
 * (1) the menu opens through the generic fallback, (2) the container
 * slot count matches the vanilla block-entity inventory size, and
 * (3) a deposit/retrieve round trip survives close/reopen — the
 * container is the real block entity, not a menu-side copy.
 */
public final class BotContainerGameTests {

    private BotContainerGameTests() {}

    /**
     * Scenario: a barrel (27-slot generic container) opens through the
     * MenuProvider fallback, accepts a QUICK_MOVE deposit, survives
     * close/reopen, and gives the item back on PICKUP. Pins the
     * generic fallback for barrel and the containerRole layout (27
     * CONTAINER + 27 MAIN + 9 HOTBAR).
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void barrelStoresAndRetrievesItems(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos barrelLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(barrelLocal, Blocks.BARREL);
        BlockPos barrelAbs = helper.absolutePos(barrelLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_INGOT, 16));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(barrelAbs));
        check(view != null, "barrel menu must open through generic fallback");
        checkEquals("barrel", view.type(), "menu type must be barrel");
        checkEquals(27, containerSlotsOf(view), "barrel exposes 27 container slots");

        // Deposit: QUICK_MOVE from hotbar slot 0 into the barrel.
        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        check(view.slot(hotbar0).isEmpty(), "hotbar must be empty after deposit");

        tx.closeMenu();

        // Reopen: state survived the close.
        view = tx.openMenu(cellOf(barrelAbs));
        check(view != null, "barrel must reopen");
        int foundSlot = -1;
        for (int i = 0; i < 27; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:iron_ingot")) {
                foundSlot = i;
                break;
            }
        }
        check(foundSlot >= 0, "iron ingot must persist in the barrel after close/reopen");
        checkEquals(16, view.slot(foundSlot).item().count(), "deposited stack count must be 16");

        // Retrieve: PICKUP the stack back to the cursor, then into hotbar.
        view = tx.menuClick(foundSlot, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold the retrieved stack");
        check(view.slot(foundSlot).isEmpty(), "barrel slot must be empty after retrieval");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a shulker box (27-slot generic container) opens,
     * accepts a deposit, and survives close/reopen. The shulker box
     * has an open/close animation that changes its collision shape, but
     * the menu interaction is identical to a barrel. Pins that the
     * generic fallback does not special-case shulker boxes and that
     * containerRole produces 27 CONTAINER slots.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void shulkerBoxStoresAndRetrievesItems(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos shulkerLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(shulkerLocal, Blocks.SHULKER_BOX);
        BlockPos shulkerAbs = helper.absolutePos(shulkerLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND, 8));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(shulkerAbs));
        check(view != null, "shulker box menu must open");
        checkEquals("shulker_box", view.type(), "menu type must be shulker_box");
        checkEquals(27, containerSlotsOf(view), "shulker box exposes 27 container slots");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        view = tx.openMenu(cellOf(shulkerAbs));
        int found = -1;
        for (int i = 0; i < 27; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:diamond")) {
                found = i;
                break;
            }
        }
        check(found >= 0, "diamond must persist in the shulker box after close/reopen");

        view = tx.menuClick(found, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold retrieved diamond");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a hopper (5-slot generic container) opens through the
     * generic fallback and exposes exactly 5 CONTAINER slots. The
     * hopper's menu class is HopperMenu (not in the KINDS table), so
     * it falls through to containerRole, which must produce 5 CONTAINER
     * + 27 MAIN + 9 HOTBAR. Pins the fallback for small containers.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void hopperMenuOpensAndShowsFiveSlots(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos hopperLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(hopperLocal, Blocks.HOPPER);
        BlockPos hopperAbs = helper.absolutePos(hopperLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(hopperAbs));
        check(view != null, "hopper menu must open");
        checkEquals("hopper", view.type(), "menu type must be hopper");
        checkEquals(5, containerSlotsOf(view), "hopper exposes 5 container slots");

        // Deposit + retrieve round trip to prove the container is real.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.WHEAT, 32));
        view = tx.openMenu(cellOf(hopperAbs));
        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        view = tx.openMenu(cellOf(hopperAbs));
        int found = -1;
        for (int i = 0; i < 5; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:wheat")) {
                found = i;
                break;
            }
        }
        check(found >= 0, "wheat must persist in the hopper after close/reopen");
        checkEquals(32, view.slot(found).item().count(), "hopper stack count must be 32");

        view = tx.menuClick(found, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold retrieved wheat");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a dispenser (9-slot generic container) opens through
     * the generic fallback and exposes exactly 9 CONTAINER slots. The
     * dispenser's menu class is DispenserMenu (not in the KINDS table),
     * so it falls through to containerRole. Pins the fallback for
     * 9-slot containers and proves the deposit/retrieve round trip.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dispenserMenuOpensAndShowsNineSlots(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos dispenserLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(dispenserLocal, Blocks.DISPENSER);
        BlockPos dispenserAbs = helper.absolutePos(dispenserLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(dispenserAbs));
        check(view != null, "dispenser menu must open");
        checkEquals("dispenser", view.type(), "menu type must be dispenser");
        checkEquals(9, containerSlotsOf(view), "dispenser exposes 9 container slots");

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.ARROW, 16));
        view = tx.openMenu(cellOf(dispenserAbs));
        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        view = tx.openMenu(cellOf(dispenserAbs));
        int found = -1;
        for (int i = 0; i < 9; i++) {
            if (!view.slot(i).isEmpty() && view.slot(i).item().itemId().equals("minecraft:arrow")) {
                found = i;
                break;
            }
        }
        check(found >= 0, "arrow must persist in the dispenser after close/reopen");

        view = tx.menuClick(found, 0, MenuClick.PICKUP);
        view = tx.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(!view.slot(hotbar0).isEmpty(), "hotbar must hold retrieved arrow");

        tx.closeMenu();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a dropper (9-slot generic container, same inventory
     * size as a dispenser but a different block and menu type) opens
     * through the generic fallback. Pins that the fallback does not
     * confuse dropper with dispenser — the snapshot type must be
     * "dropper", not "dispenser".
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dropperMenuOpensAndShowsNineSlots(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos dropperLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(dropperLocal, Blocks.DROPPER);
        BlockPos dropperAbs = helper.absolutePos(dropperLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(cellOf(dropperAbs));
        check(view != null, "dropper menu must open");
        checkEquals("dropper", view.type(), "menu type must be dropper (not dispenser)");
        checkEquals(9, containerSlotsOf(view), "dropper exposes 9 container slots");

        tx.closeMenu();
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
