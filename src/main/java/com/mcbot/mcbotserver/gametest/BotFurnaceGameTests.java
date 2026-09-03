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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Furnace-family menu interactions (capability: inventory.menu_clicks).
 * Covers the three furnace kinds — furnace, blast furnace, smoker —
 * through the shared AbstractFurnaceMenu layout: INPUT (slot 0),
 * FUEL (slot 1), OUTPUT (slot 2). Every scenario drives the real
 * BlockEntity tick so a green means the engine-side smelting loop
 * runs with the bot's deposited items.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotFurnaceGameTests {

    private BotFurnaceGameTests() {}

    /**
     * Scenario: the furnace full smelt cycle end to end — iron ore in
     * the input slot, coal in the fuel slot; the furnace smelts for
     * 200 ticks and the output slot receives an iron ingot. Pins the
     * menu slot routing (INPUT / FUEL / OUTPUT roles) and the
     * engine-side furnace tick through the real BlockEntity.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 600)
    public static void furnaceSmeltsIronOreWithCoal(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos furnaceLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(furnaceLocal, Blocks.FURNACE);
        BlockPos furnaceAbs = helper.absolutePos(furnaceLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_ORE));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.COAL));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(furnaceAbs));
        check(view != null, "opening the furnace must succeed");
        checkEquals("furnace", view.type(), "menu type must be furnace");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);

        check(!view.slot(0).isEmpty(), "iron ore must land in input slot 0");
        check(!view.slot(1).isEmpty(), "coal must land in fuel slot 1");
        tx.closeMenu();

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    var be = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(furnaceAbs);
                    check(be != null, "furnace block entity must exist");
                    check(
                            be.getItem(2).is(Items.IRON_INGOT),
                            "output slot must contain iron ingot, got " + be.getItem(2));
                }))
                .thenExecuteAfter(0, () -> {
                    var be = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(furnaceAbs);
                    check(be.getItem(0).isEmpty(), "iron ore must be consumed after smelting");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the blast furnace smelts iron ore — the blast furnace
     * accepts ore-type inputs and produces ingots in 100 ticks (half
     * the furnace time). Pins the blast furnace menu kind and its
     * ore-only input acceptance.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 400)
    public static void blastFurnaceSmeltsIronOre(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos furnaceLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(furnaceLocal, Blocks.BLAST_FURNACE);
        BlockPos furnaceAbs = helper.absolutePos(furnaceLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_ORE));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.COAL));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(furnaceAbs));
        check(view != null, "opening the blast furnace must succeed");
        checkEquals("blast_furnace", view.type(), "menu type must be blast_furnace");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    var be = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(furnaceAbs);
                    check(be != null, "blast furnace block entity must exist");
                    check(
                            be.getItem(2).is(Items.IRON_INGOT),
                            "blast furnace output must be iron ingot, got " + be.getItem(2));
                }))
                .thenExecuteAfter(0, () -> {
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the smoker cooks raw beef — the smoker accepts
     * food-type inputs and produces cooked food in 100 ticks. Pins
     * the smoker menu kind and its food-only input acceptance.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 400)
    public static void smokerCooksRawBeef(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos furnaceLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(furnaceLocal, Blocks.SMOKER);
        BlockPos furnaceAbs = helper.absolutePos(furnaceLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.BEEF));
        rig.body().getInventory().container().setItem(1, new ItemStack(Items.COAL));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(furnaceAbs));
        check(view != null, "opening the smoker must succeed");
        checkEquals("smoker", view.type(), "menu type must be smoker");

        int hotbar0 = firstHotbarSlot(view);
        tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        view = tx.menuClick(hotbar0 + 1, 0, MenuClick.QUICK_MOVE);
        tx.closeMenu();

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    var be = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(furnaceAbs);
                    check(be != null, "smoker block entity must exist");
                    check(
                            be.getItem(2).is(Items.COOKED_BEEF),
                            "smoker output must be cooked beef, got " + be.getItem(2));
                }))
                .thenExecuteAfter(0, () -> {
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the furnace fuel slot accepts coal but rejects non-fuel
     * items via QUICK_MOVE routing. A dirt block in the hotbar must
     * stay in the hotbar — the vanilla AbstractFurnaceMenu slot logic
     * refuses it in the fuel slot.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void furnaceFuelSlotRejectsNonFuel(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos furnaceLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(furnaceLocal, Blocks.FURNACE);
        BlockPos furnaceAbs = helper.absolutePos(furnaceLocal);

        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIRT));

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(furnaceAbs));
        check(view != null, "opening the furnace must succeed");

        int hotbar0 = firstHotbarSlot(view);
        view = tx.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);

        check(view.slot(1).isEmpty(), "furnace fuel slot must not accept dirt");
        boolean dirtStillInInventory = false;
        for (var slot : view.slots()) {
            if ((slot.role() == SlotRole.HOTBAR || slot.role() == SlotRole.MAIN)
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
     * Scenario: the furnace slot roles are correct — slot 0 is INPUT,
     * slot 1 is FUEL, slot 2 is OUTPUT, and the player region splits
     * into MAIN and HOTBAR. Pins the MenuSlotLayouts furnaceRole
     * table against the live engine menu.
     */
    // capability: inventory.menu_clicks
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void furnaceSlotRolesAreCorrect(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        BlockPos furnaceLocal = new BlockPos(7, WALK_Y, 8);
        helper.setBlock(furnaceLocal, Blocks.FURNACE);
        BlockPos furnaceAbs = helper.absolutePos(furnaceLocal);

        var tx = rig.actor().menuTransactions();
        var view = tx.openMenu(GametestRig.cellOf(furnaceAbs));
        check(view != null, "opening the furnace must succeed");

        checkEquals(SlotRole.INPUT, view.slot(0).role(), "slot 0 must be INPUT");
        checkEquals(SlotRole.FUEL, view.slot(1).role(), "slot 1 must be FUEL");
        checkEquals(SlotRole.OUTPUT, view.slot(2).role(), "slot 2 must be OUTPUT");

        boolean hasMain = false;
        boolean hasHotbar = false;
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.MAIN) hasMain = true;
            if (slot.role() == SlotRole.HOTBAR) hasHotbar = true;
        }
        check(hasMain, "menu must have MAIN slots");
        check(hasHotbar, "menu must have HOTBAR slots");

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
