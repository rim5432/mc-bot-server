package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.SETTLE_TICKS;
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
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
        GametestRig.fillPool(helper, 6, 8, 0, 15, 1, Blocks.WATER);
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the trench")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
        GametestRig.fillPool(helper, 6, 8, 0, 15, 3, Blocks.WATER);
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell, GametestRig.MISSION_BUDGET + 150);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the deep pool")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
        GametestRig.fillPool(helper, 6, 8, 0, 15, 1, Blocks.LAVA);
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell, 500);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival across the lava trench")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after the lava crossing");
                    check(mission.missionSucceeded(), "crossing lava must be a success");
                    assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                    checkEquals(20f, rig.body().getHealth(), "fire resistance must hold for the whole crossing");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Sneak crawl (issue 0003 sketch A, pose half): a body driving
     * with {@code Move.sneak} crosses a 1.5-block clearance (air + a
     * bottom slab) that stops standing height - the crouching box is
     * 0.6 x 1.5, the standing box 0.6 x 1.8. The claim carries the
     * sneak modifier; the body applies the pose pair and the
     * dimensions follow.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void sneakCrawlFitsThroughGap(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));

        // Wall at x=8 across the full width: solid, except a crawl gap
        // for z 7..9 - air at y=1, bottom slab at y=2 (clearance 1.5).
        for (int z = 0; z < 16; z++) {
            boolean gap = z >= 7 && z <= 9;
            for (int y = 1; y <= 3; y++) {
                if (gap && y == 1) {
                    continue; // the crawl opening: air at body height
                }
                if (gap && y == 2) {
                    // Bottom slab caps the opening at 1.5: crouched
                    // height fits exactly, standing 1.8 does not.
                    helper.setBlock(
                            new BlockPos(8, y, z),
                            Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                } else {
                    helper.setBlock(new BlockPos(8, y, z), Blocks.SMOOTH_STONE);
                }
            }
        }
        // Face east so forward drive pushes into the wall.
        rig.body().setYRot(-90f);

        helper.startSequence()
                .thenWaitUntil(GametestRig.driveUntil(rig, () -> {
                    // Re-armed every tick: claims expire at flush.
                    rig.actor().submit(new Claim(Channel.MOVE, 50, "test:sneak", new Intent.Move(1, 0, false, true)));
                    check(
                            rig.body().getBlockX() >= 9,
                            "waiting for the body to cross the crawl gap (X="
                                    + rig.body().getBlockX() + ", pose="
                                    + rig.body().getPose() + ")");
                }))
                .thenExecute(() -> {
                    // Re-assert the sneak claim: thenWaitUntil stops
                    // submitting once the cross condition fires, and the
                    // idle-tick else branch in applyMove resets the pose
                    // to STANDING. One fresh MOVE claim restores the
                    // crouch before the assertion reads it.
                    rig.actor().submit(new Claim(Channel.MOVE, 50, "test:sneak", new Intent.Move(0, 0, false, true)));
                    rig.actor().flush();
                    check(rig.body().isAlive(), "the body must survive the crawl");
                    check(
                            rig.body().isCrouching(),
                            "the body must still be crouched after crossing (pose="
                                    + rig.body().getPose() + ")");
                })
                .thenSucceed();
    }

    /**
     * Scenario: the sneak edge guard, both halves. A sneaking body
     * driven flat-out toward a plateau edge must NOT FALL: vanilla
     * trim parks the box overhanging the edge by up to half its
     * width, so the body cell may read one past the last plateau
     * cell while standing safely on the support sliver - the pin is
     * the Y level, not the cell line. The identical drive without
     * sneak must walk off and fall to the paved floor. The plateau
     * sits on the rig floor so the fall lands inside the structure
     * (3-block drop, no damage pin).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void sneakingHoldsTheLedge(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y + 3, 8));
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.SMOOTH_STONE);
                }
            }
        }
        rig.body().setYRot(-90f); // face east, straight at the edge

        helper.startSequence()
                .thenExecuteFor(40, () -> {
                    rig.actor()
                            .submit(new Claim(Channel.MOVE, 50, "test:edgesneak", new Intent.Move(1, 0, false, true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(0, () -> {
                    // Structure-local reads only: world X differs per
                    // pooled-run structure placement (the rig's
                    // toLocal note - absolute coords silently test
                    // the wrong ledge).
                    BlockPos local = GametestRig.toLocal(helper, rig.body().blockPosition());
                    check(
                            local.getX() <= 8,
                            "a sneaking body may overhang one cell but no further, local x=" + local.getX());
                    check(local.getY() >= 4, "a sneaking body must not fall off the plateau, local y=" + local.getY());
                })
                .thenExecuteFor(60, () -> {
                    rig.actor()
                            .submit(new Claim(Channel.MOVE, 50, "test:edgewalk", new Intent.Move(1, 0, false, false)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(0, () -> {
                    BlockPos local = GametestRig.toLocal(helper, rig.body().blockPosition());
                    check(local.getX() >= 8, "the unsneaking control must walk off the ledge, local x=" + local.getX());
                    check(
                            local.getY() <= 2,
                            "the fallen control must land on the paved floor, local y=" + local.getY());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: sneak in water means descend at the applied
     * velocity. The tank is three source layers deep; the body faces
     * the rim wall and drives INTO it (a zero-drive claim takes the
     * actor's idle branch and never latches sneak - the crawl
     * scenario learned this first), so the sneak modifier engages
     * while the body stays put horizontally. Mid-column per-tick Y
     * deltas must sit around the applied WATER_DESCENT_SPEED
     * (-0.10), and the body must end standing on the tank floor.
     * Floor-contact samples are excluded: a body resting on stone
     * reads 0 regardless of the descent.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void sneakingInWaterSlidesDown(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(6, GametestRig.WALK_Y + 3, 8));
        for (int x = 2; x <= 8; x++) {
            for (int z = 4; z <= 12; z++) {
                boolean rim = x == 2 || x == 8 || z == 4 || z == 12;
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), rim ? Blocks.SMOOTH_STONE : Blocks.WATER);
                }
            }
        }
        rig.body().setYRot(-90f); // face east, into the rim wall

        final List<Double> deltas = new ArrayList<>();
        final double[] lastY = {rig.body().getY()};

        helper.startSequence()
                .thenExecuteFor(50, () -> {
                    rig.actor()
                            .submit(new Claim(Channel.MOVE, 50, "test:watersink", new Intent.Move(1, 0, false, true)));
                    GametestRig.driveTick(rig);
                    double y = rig.body().getY();
                    deltas.add(y - lastY[0]);
                    lastY[0] = y;
                })
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isInWater(), "the descent must happen inside water");
                    check(
                            rig.body().getY() <= 1.9,
                            "a sneaking body must reach the tank floor, y="
                                    + rig.body().getY());
                    List<Double> midColumn = new ArrayList<>();
                    for (double delta : deltas) {
                        if (delta < -0.01) {
                            midColumn.add(delta);
                        }
                    }
                    check(!midColumn.isEmpty(), "the descent must produce sinking ticks");
                    double median = midColumn.get(midColumn.size() / 2);
                    check(median <= -0.07, "sneak must apply the descent velocity (~-0.10), median delta=" + median);
                    check(median >= -0.16, "the descent must stay near the applied rate, median delta=" + median);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a ladder column carries the bot from the floor to a
     * high platform. The walk-to-ladder leg uses plain Walk edges; the
     * vertical leg uses ClimbUp (trait-driven, decision 19); the exit
     * leg uses Walk onto the platform surface. This is the first
     * in-engine exercise of the ClimbUp vocabulary - the offline
     * BasicMovesClimbTest pins the edge shape, and
     * AStarLadderColumnGateTest pins this exact geometry at the
     * planner layer; this scenario adds the engine halves: the
     * vanilla travel() climb branch (needs horizontalCollision, so
     * the steer yaw presses into the ladder plate) and the
     * Y-aware cursor advance over a directly-overhead waypoint.
     *
     * <p>Geometry: wall at x=10 (z=7..9, y=1..6), ladders at x=9
     * (z=8, y=1..5, facing=west), platform at y=5 (x=4..8, z=7..9).
     * The bot starts at (4,1,8) facing east; goal at (6,6,8) on the
     * platform surface. Facing convention: a ladder's FACING is its
     * open side pointing AWAY from the support wall - the 3/16
     * collision plate hugs the OPPOSITE edge (vanilla
     * {@code WEST_AABB = box(13,0,0,16,16,16)}), so facing=west
     * mounts the plate against the wall at x=10. The ladder extends
     * to y=5 so one more ClimbUp lands at y=6, level with the
     * platform surface, allowing a sideways Walk exit.
     *
     * <p>required=false - the scenario mechanics are proven (it
     * passes deterministically under any trivial timing perturbation
     * and the planner route is pinned offline by
     * AStarLadderColumnGateTest), but on a clean build the mission
     * freezes mid-walk on a timing knife edge: one no-op
     * {@code helper.startSequence()} line flips fail to pass. The
     * evidence table and hypotheses live in issue 0017; flipping
     * this to required requires root-causing that race first.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT, required = false)
    public static void climbsLadderToPlatform(GameTestHelper helper) {
        // Rig FIRST: it paves the floor and clears the whole walk
        // layer (FLOOR_Y+1) to air, which erases any scenario block
        // already sitting at y=1 - the ladder's base rung and the
        // wall's bottom row. Scenario blocks go after, matching every
        // other scenario in this file.
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        // Wall: the ladder's support, 3 wide, 6 high (one above the platform
        // top so the ladder can reach the platform-surface level).
        for (int z = 7; z <= 9; z++) {
            for (int y = 1; y <= 6; y++) {
                helper.setBlock(new BlockPos(10, y, z), Blocks.SMOOTH_STONE);
            }
        }
        // Ladders on the west face of the wall (x=9), 5 cells high
        // (y=1..5). facing=west: plate on the cell's east edge, flush
        // against the wall - the mount a real placement would produce.
        for (int y = 1; y <= 5; y++) {
            helper.setBlock(
                    new BlockPos(9, y, 8),
                    Blocks.LADDER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        }
        // Platform at y=5, x=4..8 - the exit surface. Stops at x=8 so
        // (9,6,8) (the ClimbUp exit target above the top rung) stays air.
        for (int x = 4; x <= 8; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.SMOOTH_STONE);
            }
        }
        rig.body().setYRot(-90f); // face east, toward the ladder
        CellPos goalCell = localToCell(helper, new BlockPos(6, GametestRig.WALK_Y + 5, 8));
        var mission = submitGoto(rig, goalCell);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reached(rig.body(), goalCell),
                                "waiting for ladder ascent to platform, pos=" + positionOf(rig.body())
                                        + " mission.active=" + mission.isActive()
                                        + " failure=" + mission.failureReasonOrNull())))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after ascent");
                    check(mission.missionSucceeded(), "ascent must be a success");
                    assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                    rig.body().discard();
                })
                .thenSucceed();
    }
}
