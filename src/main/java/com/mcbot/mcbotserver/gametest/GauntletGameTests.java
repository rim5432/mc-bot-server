package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.submitGoto;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Stage 2 closeout gauntlet, in-engine: one corridor that makes the
 * body exercise every v1 locomotion section back to back - bottom-slab
 * carpet (auto-step), a one-block plateau (JumpUp execution via the
 * steering jump flag), a drop back down, and a fence wall whose single
 * missing-post gap is the only way through.
 *
 * <p>Contract: see workplan Stage 2 closeout follow-up 2 and issue
 * 0002 Resolution (fence = wall; slabs standable; the way past a
 * fence is the missing-post EMPTY cell). Swim/SwimUp vocabulary
 * shipped after this suite was built (workplan closeout follow-up
 * 5); water traversal is gametest-gated in BotLocomotionGameTests
 * (trench + three-deep pool), and lava traversal has its own
 * scenario there (crossesLavaTrench, fire-resistance harness).
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt, paved by
 * {@link GametestRig#rig}.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class GauntletGameTests {

    private static final int TIMEOUT = 600;

    /** Lane center the corridor forces the route through. */
    private static final int LANE_Z = 8;

    private GauntletGameTests() {}

    /**
     * End-to-end corridor: spawn on flat ground, cross the bottom-slab
     * carpet, climb the plateau, drop off its far edge, thread the
     * fence gap, arrive. Success means every section answered exactly
     * as the settled shape contract predicts.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void gauntletEndToEnd(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(2, GametestRig.WALK_Y, LANE_Z));
        buildCorridor(helper);
        CellPos goalCell = localToCell(helper, new BlockPos(14, GametestRig.WALK_Y, LANE_Z));
        var mission = submitGoto(rig, goalCell, 500);

        // Section checkpoints localize any regression to one corridor
        // segment; every threshold is an absolute cell captured from
        // the template layout so the assertions survive structure
        // placement offsets.
        int floorAbsY = localToCell(helper, new BlockPos(0, GametestRig.FLOOR_Y, LANE_Z))
                .y();
        int slabExitAbsX =
                localToCell(helper, new BlockPos(7, GametestRig.WALK_Y, LANE_Z)).x();
        int plateauWestAbsX = localToCell(helper, new BlockPos(8, GametestRig.FLOOR_Y + 2, LANE_Z))
                .x();
        int plateauEastAbsX = localToCell(helper, new BlockPos(10, GametestRig.FLOOR_Y + 2, LANE_Z))
                .x();
        int plateauAbsY = localToCell(helper, new BlockPos(8, GametestRig.FLOOR_Y + 2, LANE_Z))
                .y();
        int dropDoneAbsX = localToCell(helper, new BlockPos(11, GametestRig.WALK_Y, LANE_Z))
                .x();

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    checkBodyOnPlane(rig, floorAbsY);
                    check(crossed(rig, slabExitAbsX) || !mission.isActive(), "waiting: crossing the slab carpet");
                }))
                .thenExecuteFor(2, driveOnly(rig))
                .thenWaitUntil(driveUntil(rig, () -> {
                    checkBodyOnPlane(rig, floorAbsY);
                    check(
                            onPlateau(rig, plateauAbsY, plateauWestAbsX, plateauEastAbsX) || !mission.isActive(),
                            "waiting: mounting the plateau");
                }))
                .thenExecuteFor(2, driveOnly(rig))
                .thenWaitUntil(driveUntil(rig, () -> {
                    checkBodyOnPlane(rig, floorAbsY);
                    check(crossed(rig, dropDoneAbsX) || !mission.isActive(), "waiting: dropping off the plateau");
                }))
                .thenExecuteFor(2, driveOnly(rig))
                .thenWaitUntil(driveUntil(rig, () -> {
                    checkBodyOnPlane(rig, floorAbsY);
                    check(reached(rig.body(), goalCell) || !mission.isActive(), "waiting: threading the fence gap");
                }))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "corridor is v1-traversable; failure means a "
                                    + "section regressed. reason="
                                    + mission.failureReasonOrNull()
                                    + " bodyAt=" + positionOf(rig.body()));
                    assertCompleted(rig);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * A fence line spanning the whole lane, one missing post: the plan
     * must thread the gap cell, not stall at the wall. Pins the
     * issue-0002 fence semantics in-engine - the fence reads as a wall
     * everywhere EXCEPT the EMPTY gap cell.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void routesThroughFenceGap(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, LANE_Z));
        for (int z = 0; z < 16; z++) {
            if (z != LANE_Z) {
                helper.setBlock(new BlockPos(8, GametestRig.FLOOR_Y + 1, z), Blocks.OAK_FENCE);
            }
        }
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, LANE_Z));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reached(rig.body(), goalCell) || !mission.isActive(), "waiting for the gap threading")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "the gap is the only passage; routing must find it."
                                    + " reason=" + mission.failureReasonOrNull()
                                    + " bodyAt=" + positionOf(rig.body()));
                    assertCompleted(rig);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /** Falling below the walk plane means the body left the paved
     * platform - fail here rather than as a confusing void STUCK. */
    private static void checkBodyOnPlane(GametestRig.Rig rig, int floorAbsY) {
        check(rig.body().getBlockY() >= floorAbsY, "body left the walk plane downward at " + positionOf(rig.body()));
    }

    private static boolean crossed(GametestRig.Rig rig, int absX) {
        return rig.body().getBlockX() >= absX;
    }

    /** Body stands anywhere on the plateau span (its top is the only
     * walk surface between the two columns - the lane walls seal z).
     * Y must arrive absolute via localToCell - the arena seats
     * structures well below Y=0, so a raw FLOOR_Y+n compare never
     * fires (same defect class the surfaces scenario fixed). */
    private static boolean onPlateau(GametestRig.Rig rig, int plateauAbsY, int westAbsX, int eastAbsX) {
        return rig.body().getBlockY() == plateauAbsY
                && rig.body().getBlockX() >= westAbsX
                && rig.body().getBlockX() <= eastAbsX;
    }

    private static void buildCorridor(GameTestHelper helper) {
        int fy = GametestRig.FLOOR_Y;
        for (int x = 1; x <= 14; x++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(x, fy + y, 6), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, fy + y, 10), Blocks.SMOOTH_STONE);
            }
        }
        for (int x = 4; x <= 6; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, fy, z), Blocks.SMOOTH_STONE_SLAB);
            }
        }
        for (int x = 8; x <= 10; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, fy + 1, z), Blocks.SMOOTH_STONE);
            }
        }
        helper.setBlock(new BlockPos(13, fy + 1, 7), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(13, fy + 1, 9), Blocks.OAK_FENCE);
    }

    private static void assertCompleted(GametestRig.Rig rig) {
        List<String> kinds = rig.events().statusSnapshot(0).events().stream()
                .map(e -> e.kind())
                .toList();
        check(kinds.contains(EventKind.TASK_COMPLETED), "TASK_COMPLETED must reach the stream, got " + kinds);
    }
}
