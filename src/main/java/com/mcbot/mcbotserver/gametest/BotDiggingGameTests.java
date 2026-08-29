package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.SETTLE_TICKS;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DigProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Dig-outcome acceptance, in-engine: what a broken block leaves
 * behind - loot-table drops, XP orbs, tool wear, and food
 * exhaustion. The offline suite pins the pacing formulas
 * (DigPacingTest); these scenarios pin the vanilla parity of the
 * break sequence itself (DigExecutor's dropResources-with-TOOL
 * chain), which no offline test can observe.
 *
 * <p>Contract: issue 0013 R1 (dig is a task) for the mission shape;
 * the 2026-08-29 mechanics QA run REQ-MCBOTMECHANI-004/010/011 for
 * the outcome pins. Harness wiring lives in {@link GametestRig}.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotDiggingGameTests {

    /** Bare-hand stone takes 150 ticks (DigPacingTest pins the
     * formula); the budget covers approach plus the slow break. */
    private static final long BARE_HAND_BUDGET = 250;

    private BotDiggingGameTests() {}

    /**
     * Scenario: an iron pickaxe breaks stone and cobblestone lands -
     * the walk onto the drop site banks it. The discriminative pin
     * is the drop CONTENT (cobblestone, not stone and not nothing)
     * behind the vanilla harvest gate: the survival caller runs
     * playerDestroy (drops + exhaustion) only when the held tool can
     * harvest, which is exactly what a broken TOOL context or a
     * missing gate would corrupt.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void pickaxeDigsStoneIntoCobblestone(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        BlockPos stoneLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(stoneLocal, Blocks.STONE);
        DigProcess mission = submitDig(rig, localToCell(helper, stoneLocal));

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the break")))
                .thenExecuteAfter(0, () -> GametestRig.submitGoto(rig, localToCell(helper, stoneLocal)))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                GametestRig.countItems(rig, Items.COBBLESTONE) >= 1,
                                "walking onto the drop site must bank the cobblestone"
                                        + " (dig stand-off is 2 cells; GroundPickup scans ~1.5)")))
                .thenExecuteAfter(0, () -> {
                    check(mission.missionSucceeded(), "the dig must succeed, failure=" + mission.failureReasonOrNull());
                    check(
                            GametestRig.countItems(rig, Items.STONE) == 0,
                            "stone must not drop as itself (silk-touch-style leak)");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: bare hands break stone eventually (150 pinned ticks)
     * but the vanilla harvest gate denies the drop - no cobblestone
     * item may exist, in the container or on the ground. The negative
     * half of the drop-fidelity pin: same break, same table, tool
     * that cannot harvest (ServerPlayerGameMode's canHarvestBlock
     * gating, which DigExecutor mirrors on hasCorrectTool).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT + 100)
    public static void bareHandedStoneLeavesNoDrop(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        BlockPos stoneLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(stoneLocal, Blocks.STONE);
        DigProcess mission = submitDig(rig, localToCell(helper, stoneLocal), BARE_HAND_BUDGET);

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the slow break")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "bare-hand stone still breaks, failure=" + mission.failureReasonOrNull());
                    check(
                            GametestRig.countItems(rig, Items.COBBLESTONE) == 0,
                            "no pickaxe in the TOOL context means no cobblestone in the container");
                    helper.assertItemEntityNotPresent(Items.COBBLESTONE, stoneLocal, 2.0);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: shears on oak leaves flip the leaves loot table to
     * its shears branch - the leaf block drops itself and GroundPickup
     * banks it. The leaves are set PERSISTENT so decay ticks cannot
     * confound the drop with a naturally-vanished block.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void shearsClipLeavesIntoBlocks(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.SHEARS));
        BlockPos leavesLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(leavesLocal, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        DigProcess mission = submitDig(rig, localToCell(helper, leavesLocal));

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the clip")))
                .thenExecuteAfter(0, () -> GametestRig.submitGoto(rig, localToCell(helper, leavesLocal)))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                GametestRig.countItems(rig, Items.OAK_LEAVES) >= 1,
                                "walking onto the drop site must bank the leaves")))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "the clip must succeed, failure=" + mission.failureReasonOrNull());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: diamond ore broken with a held pickaxe seeds XP orbs
     * (spawnAfterBreak) and the body absorbs them (tickXpPickup) once
     * it walks onto the break site. The pin is ANY absorption
     * (total >= 1): orbs scatter on spawn and the pickup box is
     * ~1.5, so the exact absorbed count is environment-noisy - the
     * chain (ore break -> orb spawn -> proximity absorption) is the
     * contract. BLOCK_BROKEN is deliberately NOT pinned here: that
     * disclosure belongs to the command-handler sweep, and the
     * direct-arbiter submission path this scenario shares with every
     * other rig mission never emits it.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void diamondOreSeedsXpOrbs(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        BlockPos oreLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(oreLocal, Blocks.DIAMOND_ORE);
        DigProcess mission = submitDig(rig, localToCell(helper, oreLocal));

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the ore break")))
                .thenExecuteAfter(0, () -> GametestRig.submitGoto(rig, localToCell(helper, oreLocal)))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getTotalExperience() >= 1,
                                "walking onto the break site must absorb ore XP, total="
                                        + rig.body().getTotalExperience())))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "the ore break must succeed, failure=" + mission.failureReasonOrNull());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: DiggerItem wear is 1 per broken block - two stone
     * breaks with the same fresh iron pickaxe must land damage
     * exactly 2 on the held stack. Unbreaking plays no role on an
     * unenchanted tool, so the count is deterministic.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void digWearsThePickaxeOncePerBlock(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        BlockPos[] cells = {new BlockPos(5, GametestRig.WALK_Y, 8), new BlockPos(7, GametestRig.WALK_Y, 8)};
        for (BlockPos cell : cells) {
            helper.setBlock(cell, Blocks.STONE);
        }
        final DigProcess[] current = {submitDig(rig, localToCell(helper, cells[0]))};
        final int[] next = {1};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    if (!current[0].isActive() && next[0] < cells.length) {
                        current[0] = submitDig(rig, localToCell(helper, cells[next[0]++]));
                    }
                    check(next[0] >= cells.length && !current[0].isActive(), "waiting for the last stone break");
                }))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    ItemStack held = container.getItem(0);
                    check(Items.IRON_PICKAXE.equals(held.getItem()), "the pickaxe must stay held");
                    checkEquals(2, held.getDamageValue(), "two stone breaks must wear the pickaxe exactly 2");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: SwordItem wears 2 per broken block - double the
     * digger rate (REQ-MCBOTMECHANI-010's vanilla parity row). Dirt
     * keeps the break quick; the wear does not depend on hardness.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void swordDigsAtDoubleWear(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        BlockPos dirtLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(dirtLocal, Blocks.DIRT);
        DigProcess mission = submitDig(rig, localToCell(helper, dirtLocal));

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the dirt break")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    ItemStack held = container.getItem(0);
                    checkEquals(2, held.getDamageValue(), "one sword-dug block must wear exactly 2");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: each broken block adds the vanilla 0.005 exhaustion
     * (Block.playerDestroy parity). Four bare-hand gravel breaks
     * (18 pinned ticks each) must accumulate at least 0.019 - the
     * epsilon keeps the float comparison honest while still failing
     * a missing addExhaustion call outright.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void breakingBlocksBuildsExhaustion(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        BlockPos[] cells = new BlockPos[4];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new BlockPos(5 + i, GametestRig.WALK_Y, 8);
            helper.setBlock(cells[i], Blocks.GRAVEL);
        }
        final DigProcess[] current = {submitDig(rig, localToCell(helper, cells[0]))};
        final int[] next = {1};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    if (!current[0].isActive() && next[0] < cells.length) {
                        current[0] = submitDig(rig, localToCell(helper, cells[next[0]++]));
                    }
                    check(next[0] >= cells.length && !current[0].isActive(), "waiting for the last gravel break");
                }))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    float exhaustion = rig.body().getFoodData().getExhaustionLevel();
                    check(exhaustion >= 0.019f, "four breaks must build ~0.02 exhaustion, got " + exhaustion);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Registers and takes control of a dig mission with the shared
     * mission budget.
     *
     * @param rig    the wired rig; never null
     * @param target world-absolute block cell; never null
     * @return the active mission; never null
     */
    private static DigProcess submitDig(GametestRig.Rig rig, CellPos target) {
        return submitDig(rig, target, GametestRig.MISSION_BUDGET);
    }

    /**
     * Registers and takes control of a dig mission against one
     * block cell.
     *
     * @param rig    the wired rig; never null
     * @param target world-absolute block cell; never null
     * @param budget mission-side hard cutoff in ticks; positive
     * @return the active mission; never null
     */
    private static DigProcess submitDig(GametestRig.Rig rig, CellPos target, long budget) {
        String taskId = "gt-dg-" + Integer.toHexString(System.identityHashCode(target));
        DigProcess mission = new DigProcess(taskId, target, 50, budget);
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }
}
