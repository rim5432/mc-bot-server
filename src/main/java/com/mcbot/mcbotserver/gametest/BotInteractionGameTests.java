package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
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
}
