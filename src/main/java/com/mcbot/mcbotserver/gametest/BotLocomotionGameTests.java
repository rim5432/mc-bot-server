package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.assertEventSeen;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Locomotion and goto-pipeline acceptance, in-engine: flat walks,
 * fluid crossings under the Swim vocabulary, shove re-routing, and
 * the clean NO_PATH failure. The trench/pool scenarios pin PLANNER
 * locomotion (a running mission crosses); the passive-body survival
 * reflexes live in {@link BotHazardReflexGameTests}.
 *
 * <p>Contract: see workplan Stage 1 gate (walks-to-block /
 * recovers-when-shoved / fails-cleanly-unwalkable) and boundaries.md
 * decisions 16-18.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt - an empty box
 * the tests pave themselves. MC 1.20.1 ships no empty template; the
 * SNBT fallback path in StructureUtils resolves this file relative
 * to the gameTestServer working directory.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotLocomotionGameTests {

    private BotLocomotionGameTests() {}

    /**
     * Scenario 1: walks to a block on flat ground and reports success.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void walksToBlock(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after arrival");
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
     *
     * <p>INVARIANT - the trench has NO floor under the water (unlike
     * the deep pool, which digs down and paves stone at FLOOR_Y-3).
     * Deliberate: any solid ground under shallow water keeps
     * {@code onGround} true and lets the grounded-hop path clear the
     * channel without ever engaging fluid locomotion - the masking
     * the deep-pool comment records from its own two-deep first
     * draft. Do not "fix" this by paving below the water; if a
     * wading-with-floor scenario is ever wanted, it is a NEW scenario
     * alongside this one, not a modification of it. The sources
     * themselves stay put - a source block above air does not drain,
     * the spill falls into the void below the arena and cannot reach
     * the parallel structures.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void crossesWaterTrench(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 6; x <= 8; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z), Blocks.WATER);
            }
        }
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the trench")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after crossing");
                    check(mission.missionSucceeded(), "crossing must be a success");
                    assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 1c: a three-deep pool spans the lane; the bot must
     * rise through the fluid column and climb out the far side.
     *
     * <p>Why: issue 0004 F2 - the planner sells SwimUp edges the
     * executor could not physically express, because driveJump fired
     * jumpFromGround only under onGround(). Depth matters here: a
     * shallower pool lets the grounded body hop off the flooded floor
     * (onGround stays true under water) and masks the gap entirely -
     * the first draft at two deep passed before the fix. At three,
     * the surface sits beyond a single jump's 1.25-block reach, so
     * only the vanilla jumping-flag buoyancy (Mob.jumpInFluid,
     * +0.3/tick while canFloat is false) crosses the column. Red
     * first (STUCK at the pool bottom), green once the binding
     * expresses jump-in-fluid as the jumping flag.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void crossesDeepPool(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.fillPool(helper, 6, 8, 3);
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell, GametestRig.MISSION_BUDGET + 150);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the deep pool")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after crossing");
                    check(mission.missionSucceeded(), "crossing must be a success");
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
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void recoversWhenShoved(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, GametestRig.WALK_Y, 8);
        var rig = rig(helper, start);
        CellPos goalCell = localToCell(helper, new BlockPos(13, GametestRig.WALK_Y, 8));
        submitGoto(rig, goalCell);
        int startAbsX = helper.absolutePos(start).getX();

        // Pre-shove body Z. Used by the post-shove displacement
        // assertion to confirm the test landed in branch 1 of
        // issue 0001 §3 (offPath fires), not branch 2 (silent
        // limbo). The 3.5 margin is REPLAN_DISTANCE 3.0 plus 0.5
        // to absorb friction and tick-order variance.
        double[] zAtShove = {0.0};

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(rig.body().getBlockX() - startAbsX >= 4, "waiting to pass mid-point")))
                .thenExecute(() -> {
                    // Knock the body LATERALLY off the plan line. Since
                    // the ledger-20 follow-through (issue 0005 P1.2),
                    // drift is measured against the WALKED SEGMENT, not
                    // the current waypoint: an axial shove stays on the
                    // segment forever, so branch 1 now requires lateral
                    // departure. The stale drive from the last pipeline
                    // tick is neutralized first - the fields persist
                    // through the no-op window below, and a stale +X
                    // drive (sprint-amplified since P1.1) would fight
                    // the impulse and muddy the displacement reading.
                    zAtShove[0] = rig.body().getZ();
                    rig.body().setDrive(0f, 0f, false);
                    rig.body().setSprinting(false);
                    rig.body().setDeltaMovement(new Vec3(0.0, 0.0, -1.8));
                })
                .thenExecuteFor(5, () -> {
                    // 5 vanilla physics ticks without pipeline
                    // intervention - MC integrates the impulse.
                })
                .thenExecute(() -> {
                    // Sanity: lateral displacement must exceed the
                    // 3.5-cell floor (REPLAN_DISTANCE + friction margin)
                    // so the test stays in branch 1 (offPath fires on
                    // departure from the walked segment), not branch 2
                    // where offPath would NOT fire and the test silently
                    // regresses to limbo.
                    double dz = zAtShove[0] - rig.body().getZ();
                    check(
                            dz > 3.5,
                            "shove must displace the body > 3.5 cells in -Z "
                                    + "so the test stays in branch 1 (offPath fires), "
                                    + "not branch 2 (silent limbo). Got dz=" + dz
                                    + " - if friction or impulse changed, retune "
                                    + "the impulse or extend the vanilla-tick window; "
                                    + "do not relax the 3.5 margin silently.");
                })
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "still re-walking after the shove")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    checkEquals(goalCell, positionOf(rig.body()), "must still arrive after the shove");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 3: unreachable goal fails cleanly with a reason - no
     * hang, no silent stall.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void failsCleanlyWhenUnwalkable(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, GametestRig.WALK_Y, 8);
        var rig = rig(helper, start);
        // Floating goal five above the walk plane: no move in the
        // stage-2 vocabulary lands there (drops need support below,
        // climbs need a step chain), so the island drains to NO_PATH.
        var mission = submitGoto(rig, localToCell(helper, new BlockPos(13, GametestRig.WALK_Y + 5, 8)));

        for (int x = 3; x <= 14; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(
                        new BlockPos(x, GametestRig.FLOOR_Y + y, 7),
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE);
                helper.setBlock(
                        new BlockPos(x, GametestRig.FLOOR_Y + y, 9),
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE);
            }
        }

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the failure")))
                .thenExecuteFor(6, driveOnly(rig))
                .thenExecute(() -> {
                    check(!mission.missionSucceeded(), "cannot succeed");
                    checkEquals(
                            "NO_PATH",
                            mission.failureReasonOrNull(),
                            "a walled-in goal must fail as NO_PATH at planning");
                    assertEventSeen(rig.events(), EventKind.TASK_FAILED);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 1d: a lava trench spans the lane; the bot swims across
     * under fire resistance and climbs out the far side.
     *
     * <p>Why: lava shares the Swim vocabulary (the
     * {@code dangerousLiquid} trait keeps the liquid bit set) but has
     * its own execution branch - {@code BotBodyEntity} ascends via
     * {@code jumpInFluid(LAVA_TYPE)}, the more viscous cousin of the
     * water path (issue 0004 F2 + lava branch). None of that had an
     * in-engine scenario (GauntletGameTests javadoc tracked it).
     *
     * <p>Fire resistance is a HARNESS choice, not bot capability: the
     * device has no lava-survival story yet, and the scenario pins
     * LOCOMOTION through lava - the ascent branch, the crossing, the
     * exit climb. The unscathed-health assertion doubles as proof the
     * effect held for the whole crossing; without it the body would
     * burn down mid-trench.
     *
     * <p>Same no-floor construction as the water trench and for the
     * same reason: any floor under shallow lava keeps {@code onGround}
     * true and lets ground-hop mask the fluid path entirely.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 600)
    public static void crossesLavaTrench(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));
        for (int x = 6; x <= 8; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z), Blocks.LAVA);
            }
        }
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell, 500);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the lava trench")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after the lava crossing");
                    check(mission.missionSucceeded(), "crossing lava must be a success");
                    assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                    checkEquals(20f, rig.body().getHealth(), "fire resistance must hold for the whole crossing");
                    rig.body().discard();
                })
                .thenSucceed();
    }
}
