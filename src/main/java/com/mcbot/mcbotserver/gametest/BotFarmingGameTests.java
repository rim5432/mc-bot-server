package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Direction;
import com.mcbot.mcbotserver.api.types.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Farming machinery acceptance, in-engine: the hoe's use-on chain
 * through the production INTERACT channel converts tillable blocks
 * into farmland. The tilling predicate (onlyIfAirAbove: clicked face
 * not DOWN, block above is air) is vanilla HoeItem logic — the
 * executor dispatches to ItemStack.useOn directly, so the predicate
 * runs unchanged.
 *
 * <p>Contract: farming.till_soil capability face; HoeItem.useOn
 * (decompiled 1.20.1 line 48-75); FarmBlock default state MOISTURE=0.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotFarmingGameTests {

    private BotFarmingGameTests() {}

    /**
     * Scenario: holding a hoe, right-click the top face of a dirt
     * block — HoeItem.useOn fires, the block becomes farmland, and the
     * click is consumed. The hit point sits on the top face (y+1.0)
     * so the UseOnContext reports clicked face = UP, satisfying
     * onlyIfAirAbove.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void hoeTillsDirtIntoFarmland(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        BlockPos dirtLocal = new BlockPos(5, GametestRig.FLOOR_Y, 8);
        helper.setBlock(dirtLocal, Blocks.DIRT);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_HOE));
        rig.body().selectedSlot = 0;

        CellPos dirtCell = localToCell(helper, dirtLocal);
        Vec3 hitTop = new Vec3(dirtCell.x() + 0.5, dirtCell.y() + 1.0, dirtCell.z() + 0.5);

        helper.startSequence()
                .thenExecuteAfter(0, () -> {
                    boolean consumed = rig.actor()
                            .interactExecutor()
                            .use(new Intent.InteractBlock(dirtCell, Direction.UP, hitTop));
                    check(consumed, "the hoe use-on must consume the click");
                })
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    var state = helper.getBlockState(dirtLocal);
                    check(
                            state.is(Blocks.FARMLAND),
                            "dirt must become farmland after hoe use, got " + state.getBlock());
                })
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /**
     * Scenario: grass_block also tills to farmland — the TILLABLES
     * map lists grass_block, dirt_path, and dirt all mapping to
     * farmland. This pins the grass_block branch so a future
     * tool-action refit does not silently drop it.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void hoeTillsGrassBlockIntoFarmland(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        BlockPos grassLocal = new BlockPos(5, GametestRig.FLOOR_Y, 8);
        helper.setBlock(grassLocal, Blocks.GRASS_BLOCK);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_HOE));
        rig.body().selectedSlot = 0;

        CellPos grassCell = localToCell(helper, grassLocal);
        Vec3 hitTop = new Vec3(grassCell.x() + 0.5, grassCell.y() + 1.0, grassCell.z() + 0.5);

        helper.startSequence()
                .thenExecuteAfter(0, () -> {
                    boolean consumed = rig.actor()
                            .interactExecutor()
                            .use(new Intent.InteractBlock(grassCell, Direction.UP, hitTop));
                    check(consumed, "the hoe use-on grass must consume the click");
                })
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    var state = helper.getBlockState(grassLocal);
                    check(
                            state.is(Blocks.FARMLAND),
                            "grass_block must become farmland after hoe use, got " + state.getBlock());
                })
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /**
     * Scenario: a dirt block with a non-air block above does NOT till.
     * Forge 1.20.1 routes hoe tilling through IForgeBlock.getToolModifiedState
     * (HOE_TILL), whose default guard is {@code above().isAir()} only — it
     * does not replicate vanilla's onlyIfAirAbove clicked-face check. This
     * pins the guard that actually runs: a covered dirt cell stays dirt.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void hoeDoesNotTillWhenCovered(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        BlockPos dirtLocal = new BlockPos(5, GametestRig.FLOOR_Y, 8);
        helper.setBlock(dirtLocal, Blocks.DIRT);
        helper.setBlock(new BlockPos(5, GametestRig.FLOOR_Y + 1, 8), Blocks.STONE);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_HOE));
        rig.body().selectedSlot = 0;

        CellPos dirtCell = localToCell(helper, dirtLocal);
        Vec3 hitTop = new Vec3(dirtCell.x() + 0.5, dirtCell.y() + 1.0, dirtCell.z() + 0.5);

        helper.startSequence()
                .thenExecuteAfter(0, () -> {
                    rig.actor().interactExecutor().use(new Intent.InteractBlock(dirtCell, Direction.UP, hitTop));
                })
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    var state = helper.getBlockState(dirtLocal);
                    check(state.is(Blocks.DIRT), "covered dirt must remain dirt, got " + state.getBlock());
                })
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }
}
