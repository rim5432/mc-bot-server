package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Interaction-family engine proofs: the scenarios drive the real
 * right-click chain (the facade-backed executor the /bot use verb
 * serves) and assert world-side truth - block states after the fact,
 * never the executor's own word.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotInteractionGameTests {

    private BotInteractionGameTests() {}

    /**
     * Use-block chain (issue 0007 Phase 2): a right-click on a closed
     * door toggles it open through {@code BlockState.use} - the facade
     * supplies the Player the vanilla door code reads, no Player
     * parameter is faked. Proves the chain mutates block states, not
     * just places blocks.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void doorOpensThroughUseChain(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Closed oak door one block east of the body: lower + upper
        // halves, facing east, hinge left, shut.
        var lower = Blocks.OAK_DOOR
                .defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false);
        helper.setBlock(new BlockPos(8, GametestRig.WALK_Y, 7), lower);
        helper.setBlock(
                new BlockPos(8, GametestRig.WALK_Y + 1, 7), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        CellPos doorCell = localToCell(helper, new BlockPos(8, GametestRig.WALK_Y, 7));

        helper.startSequence()
                .thenExecute(() -> {
                    boolean used = rig.actor()
                            .interactExecutor()
                            .use(new Intent.InteractBlock(
                                    doorCell,
                                    Direction.WEST,
                                    new com.mcbot.mcbotserver.api.types.Vec3(
                                            doorCell.x() + 0.5, doorCell.y() + 0.5, doorCell.z() + 0.5)));
                    check(used, "the door use must consume the click");
                })
                .thenExecute(() -> check(
                        helper.getBlockState(new BlockPos(8, GametestRig.WALK_Y, 7))
                                .getValue(DoorBlock.OPEN),
                        "the door must stand open after the use"))
                .thenSucceed();
    }

    /**
     * Direction-aware placement (the Phase 1 debt retired): stairs
     * placed through the use chain face their placer - BlockItem.place
     * derives the facing from the acting entity's look, and the facade
     * mirrors the body's yaw before the chain fires. Under the retired
     * setBlock replica every staircase faced north.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void stairsFaceTheirPlacer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Stairs in the hand, hand selected, body facing west: yaw 90
        // is -X. The facade mirrors the yaw, the placement mirrors the
        // yaw as the stairs' horizontal facing.
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.OAK_STAIRS));
        rig.body().selectedSlot = 0;
        rig.body().getInventory().setSelectedSlot(0);
        rig.body().setYRot(90f);
        BlockPos floorLocal = new BlockPos(6, GametestRig.FLOOR_Y, 7);
        helper.setBlock(floorLocal, Blocks.STONE);
        CellPos floor = localToCell(helper, floorLocal);

        helper.startSequence()
                .thenExecute(() -> {
                    boolean used = rig.actor()
                            .interactExecutor()
                            .use(new Intent.InteractBlock(
                                    floor,
                                    Direction.UP,
                                    new com.mcbot.mcbotserver.api.types.Vec3(
                                            floor.x() + 0.5, floor.y() + 1.0, floor.z() + 0.5)));
                    check(used, "the stair placement must consume the click");
                })
                .thenExecute(() -> {
                    var stairs = helper.getBlockState(new BlockPos(6, GametestRig.WALK_Y, 7));
                    check(
                            stairs.getBlock() instanceof StairBlock,
                            "stairs must stand above the clicked floor, got " + stairs.getBlock());
                    check(
                            stairs.getValue(StairBlock.FACING) == net.minecraft.core.Direction.WEST,
                            "stairs must face the placer (the facade's mirrored yaw), got "
                                    + stairs.getValue(StairBlock.FACING));
                })
                .thenSucceed();
    }
    /**
     * Scenario: the sleep verb's observable contract, all four
     * branches. Sleep as shipped is a bed-anchored time skip (the
     * deviation from vanilla's in-bed wait is BindingActor's
     * documented ruling: no player respawn flow to bind), so the
     * pins are the refusal reasons - daytime, non-bed, out of reach
     * - and the morning advance: success lands the day time exactly
     * on a multiple of 24000 with isDay true.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void sleepVerbSkipsToMorningOnlyFromABed(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(5, GametestRig.WALK_Y, 8));
        BlockPos bedLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(bedLocal, Blocks.RED_BED);
        // The out-of-reach branch needs a real BED far away: a plain
        // air cell trips the not-a-bed guard first.
        helper.setBlock(new BlockPos(14, GametestRig.WALK_Y, 8), Blocks.RED_BED);
        var level = helper.getLevel();

        helper.startSequence()
                .thenExecuteFor(GametestRig.SETTLE_TICKS, GametestRig.driveOnly(rig))
                // The daytime branch: skyDarken samples the day time
                // only every 20 ticks, so the refusal needs settle
                // ticks AFTER the time write to read the new phase.
                .thenExecuteAfter(0, () -> level.setDayTime(1000L))
                .thenExecuteFor(30, GametestRig.driveOnly(rig))
                .thenExecuteAfter(
                        0,
                        () -> checkEquals(
                                "already daytime",
                                rig.actor().sleepAt(GametestRig.localToCell(helper, bedLocal)),
                                "daytime must refuse with the structured reason"))
                .thenExecuteAfter(0, () -> level.setDayTime(13000L))
                .thenExecuteFor(30, GametestRig.driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    checkEquals(
                            "not a bed",
                            rig.actor()
                                    .sleepAt(GametestRig.localToCell(helper, new BlockPos(8, GametestRig.WALK_Y, 8))),
                            "a non-bed cell must refuse");
                    checkEquals(
                            "out of reach",
                            rig.actor()
                                    .sleepAt(GametestRig.localToCell(helper, new BlockPos(14, GametestRig.WALK_Y, 8))),
                            "a distant bed must refuse");
                    check(
                            rig.actor().sleepAt(GametestRig.localToCell(helper, bedLocal)) == null,
                            "night + bed in reach must succeed");
                    checkEquals(0L, level.getDayTime() % 24000L, "success must land exactly on a morning tick");
                })
                // isDay reads skyDarken, which samples the day time
                // only every 20 ticks - give it the settle window.
                .thenExecuteFor(30, GametestRig.driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(level.isDay(), "the skipped-to morning must read as daytime");
                    rig.body().discard();
                })
                .thenSucceed();
    }
}
