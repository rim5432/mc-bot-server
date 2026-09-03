package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.types.CellPos;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Production-path integration, in-engine: the body is created by the
 * real {@code /botspawn} command, driven by a real {@code /bot goto}
 * submission through the command bus, and ticked by the actual Forge
 * ServerTickEvent subscription - the scenario itself never touches
 * the pipeline. Every slice scenario builds its own rig; this one
 * proves the wiring the rig mirrors (command registration, tick
 * phase, harness seams) actually moves a body in a live server.
 *
 * <p>Contract: see boundaries.md section D (submit/event/state
 * channels) and ADR-0004 D1 (single server-tick entry, END phase).
 *
 * <p>The mod instance keeps one active session, so exactly one test
 * may drive the command surface; it replaces nothing because no other
 * scenario spawns through commands, and it despawns on the way out so
 * the singleton does not outlive the structure.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class ProductionWiringGameTests {

    private static final int TIMEOUT = 400;

    private ProductionWiringGameTests() {}

    /**
     * Scenario: /botspawn at the paved center, /bot goto to the far
     * corner, arrival with no test-side pipeline access at all.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void walksViaCommandAndServerTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + 1, z), Blocks.AIR);
            }
        }

        var server = level.getServer();
        var spawnAbs = helper.absolutePos(new BlockPos(3, GametestRig.WALK_Y, 8));
        var source = server.createCommandSourceStack().withLevel(level).withPosition(Vec3.atCenterOf(spawnAbs));
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        String gotoCommand = "bot goto " + goalCell.x() + " " + goalCell.y() + " " + goalCell.z() + " 0 300";
        BotBodyEntity[] body = {null};

        // GameTestHelper.getBounds is private in 1.20.1; the structure
        // footprint is known (16x8x16 from the template origin), and a
        // hand-built AABB also isolates this test's body from the
        // parallel slice rigs' bodies elsewhere in the arena.
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        var structureBox = new AABB(
                origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 8, origin.getZ() + 16);

        helper.startSequence()
                .thenExecute(() -> server.getCommands().performPrefixedCommand(source, "botspawn"))
                .thenExecuteAfter(2, () -> {
                    var bodies =
                            level.getEntities(McBotServer.BOT_BODY.get(), b -> structureBox.contains(b.position()));
                    check(
                            bodies.size() == 1,
                            "/botspawn must leave exactly one body inside the " + "structure, found " + bodies.size());
                    body[0] = bodies.get(0);
                })
                .thenExecute(() -> server.getCommands().performPrefixedCommand(source, gotoCommand))
                // No driveTick anywhere here: the Forge ServerTickEvent
                // subscription is the only thing ticking this pipeline.
                .thenWaitUntil(() -> check(
                        reached(body[0], goalCell),
                        "waiting for command-driven arrival, bodyAt=" + positionOf(body[0]) + " goal=" + goalCell))
                // Cleanup discards ONLY this structure's body. /botdespawn
                // sweeps every bot body in the level by design - dispatching
                // it here killed the parallel slice rigs' bodies mid-run
                // (7 collateral failures on the first engine run of this
                // scenario). The singleton keeps idly ticking the removed
                // body until the server exits; nothing else spawns through
                // commands, so it is inert.
                .thenExecute(() -> body[0].discard())
                .thenSucceed();
    }
}
