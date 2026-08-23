package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DefendProcess;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.submitGoto;

/**
 * Stage-1 acceptance, in-engine: the three slice scenarios from the
 * workplan gate plus the two combat scenarios from the Stage 2
 * skeleton. Harness wiring lives in {@link GametestRig}; this class
 * owns only the scenarios and their assertions.
 *
 * <p>Contract: see workplan Stage 1 gate (walks-to-block /
 * recovers-when-shoved / fails-cleanly-unwalkable) and boundaries.md
 * decisions 16-18.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt - an empty box the
 * tests pave themselves. MC 1.20.1 ships no empty template; the SNBT
 * fallback path in StructureUtils resolves this file relative to the
 * gameTestServer working directory.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotSliceGameTests {

    private static final int TIMEOUT = 400;
    private static final int MISSION_BUDGET = 350;

    private BotSliceGameTests() {
    }

    /**
     * Scenario 1: walks to a block on flat ground and reports success.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void walksToBlock(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.isActive(),
                    "mission must retire after arrival");
                check(mission.missionSucceeded(), "must be a success");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 1b: a water trench spans the lane; the bot swims
     * across and climbs out the far side.
     *
     * <p>Why: the acceptance run found the v1 planner blind to
     * water - every swim cell reads unstunnable without the liquid
     * trait, so a lake meant NO_PATH everywhere. This pins the Swim
     * vocabulary end to end: Walk to the edge, Drop/Swim through the
     * channel, JumpUp out. The trench spans all z so walking around
     * is impossible.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void crossesWaterTrench(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 6; x <= 8; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.WATER);
            }
        }
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival across the trench")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.isActive(),
                    "mission must retire after crossing");
                check(mission.missionSucceeded(),
                    "crossing must be a success");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 2: shoved mid-path, the bot re-walks to the same goal.
     *
     * <p>Issue 0001 fix 3: the shove is a real vanilla-physics
     * knockback (setDeltaMovement + 5 physics ticks of integration)
     * rather than an admin teleport. The body must actually displace
     * horizontally and the planner must re-route to the original goal.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void recoversWhenShoved(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, GametestRig.WALK_Y, 8);
        var rig = rig(helper, start);
        CellPos goalCell = localToCell(helper,
            new BlockPos(13, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);
        int startAbsX = helper.absolutePos(start).getX();

        // Pre-shove body X. Used by the post-shove displacement
        // assertion to confirm the test landed in branch 1 of
        // issue 0001 §3 (offPath fires because XZ drift > 3.0),
        // not branch 2 (XZ drift 0.8~3.0, offPath would NOT fire
        // and the test would silently regress to limbo). The 3.5
        // margin is REPLAN_DISTANCE 3.0 plus 0.5 to absorb friction
        // and tick-order variance.
        double[] xAtShove = {0.0};

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getBlockX()
                    - startAbsX >= 4,
                    "waiting to pass mid-point")))
            .thenExecute(() -> {
                // Strong horizontal knockback in -X. The body is
                // mid-path, so the planner must re-route after
                // the shove lands.
                xAtShove[0] = rig.body().getX();
                rig.body().setDeltaMovement(new Vec3(
                    -1.8, 0.0, 0.0));
            })
            .thenExecuteFor(5, () -> {
                // 5 vanilla physics ticks without pipeline
                // intervention - MC integrates the impulse.
            })
            .thenExecute(() -> {
                // Sanity: displacement must exceed the 3.5-cell
                // floor (REPLAN_DISTANCE + friction margin) so the
                // test stays in branch 1 of issue 0001 §3 (offPath
                // fires on XZ drift > 3.0 from the remaining
                // waypoints), not branch 2 where offPath would NOT
                // fire and the test silently regresses to limbo.
                // Direction past the SPAWN point is deliberately not
                // asserted: a knockback integrated near its full
                // ~4-cell series can legally land the body back at or
                // before spawn when the mid-point gate fired early,
                // and branch 1 holds either way - drift is measured
                // from the body's position at shove time, not from
                // spawn.
                double dx = xAtShove[0] - rig.body().getX();
                check(dx > 3.5,
                    "shove must displace the body > 3.5 cells in -X "
                    + "so the test stays in branch 1 (offPath fires), "
                    + "not branch 2 (silent limbo). Got dx=" + dx
                    + " - if friction or impulse changed, retune "
                    + "the impulse or extend the vanilla-tick window; "
                    + "do not relax the 3.5 margin silently.");
            })
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "still re-walking after the shove")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                checkEquals(goalCell, positionOf(rig.body()),
                    "must still arrive after the shove");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 3: unreachable goal fails cleanly with a reason - no
     * hang, no silent stall.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void failsCleanlyWhenUnwalkable(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, GametestRig.WALK_Y, 8);
        var rig = rig(helper, start);
        // Floating goal five above the walk plane: no move in the
        // stage-2 vocabulary lands there (drops need support below,
        // climbs need a step chain), so the island drains to NO_PATH.
        var mission = submitGoto(rig,
            localToCell(helper, new BlockPos(13, GametestRig.WALK_Y + 5, 8)));

        for (int x = 3; x <= 14; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y, 7),
                    net.minecraft.world.level.block.Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y, 9),
                    net.minecraft.world.level.block.Blocks.SMOOTH_STONE);
            }
        }

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig, () ->
                check(!mission.isActive(), "waiting for the failure")))
            .thenExecuteFor(6, driveOnly(rig))
            .thenExecute(() -> {
                check(!mission.missionSucceeded(), "cannot succeed");
                checkEquals("NO_PATH", mission.failureReasonOrNull(),
                    "a walled-in goal must fail as NO_PATH at planning");
                assertEventSeen(rig.events(), EventKind.TASK_FAILED);
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 5: a ranged hostile is REFUSED, not chased. The planner
     * sees the structural mismatch (melee-only vs kite-and-shoot) and
     * fails the mission on the engage tick with a structured reason -
     * before the body spends a single tick under fire.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void refusesRangedItCannotAnswer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        Skeleton skeleton = EntityType.SKELETON.create(level);
        check(skeleton != null, "skeleton creation failed");
        var sAbs = helper.absolutePos(new BlockPos(7, GametestRig.WALK_Y, 8));
        skeleton.moveTo(sAbs.getX() + 0.5, sAbs.getY(),
            sAbs.getZ() + 0.5, 0f, 0f);
        skeleton.setNoAi(true);
        level.addFreshEntity(skeleton);

        DefendProcess mission = submitDefend(rig);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(!mission.isActive(),
                    "waiting for the refusal")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.missionSucceeded(),
                    "a refusal is a failure, not a success");
                checkEquals(DefendProcess.REASON_REFUSED,
                    mission.failureReasonOrNull(),
                    "the refusal reason must be structured");
                BotEvent failed = rig.events().statusSnapshot(0)
                    .events().stream()
                    .filter(e -> EventKind.TASK_FAILED.equals(e.kind()))
                    .findFirst().orElse(null);
                check(failed != null, "TASK_FAILED must be in stream");
                checkEquals("ENGAGEMENT_REFUSED",
                    failed.attrs().get("reason"),
                    "refusal reason must be structured");
                checkEquals("minecraft:skeleton",
                    failed.attrs().get("threatType"),
                    "the refused type must reach the harness");
                checkEquals(20f, rig.body().getHealth(),
                    "the body must refuse BEFORE taking arrows");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 4: an intruder inside the engage radius gets engaged,
     * chased the last step, and beaten down - the defend mission
     * completes only once the target stops existing.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void defendsByKillingZombie(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        Zombie zombie = EntityType.ZOMBIE.create(level);
        check(zombie != null, "zombie creation failed");
        var zAbs = helper.absolutePos(new BlockPos(7, GametestRig.WALK_Y, 8));
        zombie.moveTo(zAbs.getX() + 0.5, zAbs.getY(),
            zAbs.getZ() + 0.5, 0f, 0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);

        DefendProcess mission = submitDefend(rig);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(!mission.isActive(),
                    "waiting for the fight to end")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(mission.missionSucceeded(),
                    "a killed target must complete the defend task");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "the zombie must not survive the engagement");
                check(rig.body().isAlive(),
                    "the body must walk away from this fight");
                rig.body().discard();
            })
            .thenSucceed();
    }

    private static DefendProcess submitDefend(GametestRig.Rig rig) {
        String taskId = "gt-df-" + Integer.toHexString(
            System.identityHashCode(rig.body()));
        DefendProcess mission = new DefendProcess(taskId, 60,
            MISSION_BUDGET, () -> GametestRig.positionOf(rig.body()),
            LevelThreatSensor.hostileTypes(),
            LevelThreatSensor.rangedTypes());
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    private static void assertEventSeen(
            com.mcbot.mcbotserver.core.event.InMemoryEventQueue events,
            String kind) {
        List<String> kinds = events.statusSnapshot(0).events().stream()
            .map(BotEvent::kind).toList();
        check(kinds.contains(kind),
            "expected " + kind + " in stream, got " + kinds);
    }
}
