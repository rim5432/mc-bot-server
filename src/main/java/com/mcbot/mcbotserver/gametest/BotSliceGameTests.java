package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import com.mcbot.mcbotserver.core.reflex.AscendInLethalFluidRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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

    /**
     * Upper bound on ticks from mission start to a structural
     * refusal: the refusal decision is made on the mission's first
     * directive pass, so a healthy run lands in single digits - the
     * window only exists to absorb sequence-overhead ticks.
     */
    private static final int REFUSAL_WINDOW_TICKS = 15;

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
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void crossesDeepPool(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 6; x <= 8; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 2, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 3, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell,
            MISSION_BUDGET + 150);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival across the deep pool")))
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

        // Pre-shove body Z. Used by the post-shove displacement
        // assertion to confirm the test landed in branch 1 of
        // issue 0001 §3 (offPath fires), not branch 2 (silent
        // limbo). The 3.5 margin is REPLAN_DISTANCE 3.0 plus 0.5
        // to absorb friction and tick-order variance.
        double[] zAtShove = {0.0};

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getBlockX()
                    - startAbsX >= 4,
                    "waiting to pass mid-point")))
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
                rig.body().setDeltaMovement(new Vec3(
                    0.0, 0.0, -1.8));
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
                check(dz > 3.5,
                    "shove must displace the body > 3.5 cells in -Z "
                    + "so the test stays in branch 1 (offPath fires), "
                    + "not branch 2 (silent limbo). Got dz=" + dz
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
     *
     * <p>The skeleton sits at its natural ranged standoff (8 cells,
     * outside {@code EngageOnHostileProximityRule}'s 6-cell trigger)
     * - under the production rule set a skeleton that closed to
     * melee range would trip the idle-combat reflex instead, which is
     * the scenario 4 shape, not this one. The discriminative
     * assertions are the structured refusal reason plus the tick
     * window: a regression to chasing keeps the mission alive past
     * the window and fails here, not at timeout.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void refusesRangedItCannotAnswer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        Skeleton skeleton = EntityType.SKELETON.create(level);
        check(skeleton != null, "skeleton creation failed");
        var sAbs = helper.absolutePos(new BlockPos(11, GametestRig.WALK_Y, 8));
        skeleton.moveTo(sAbs.getX() + 0.5, sAbs.getY(),
            sAbs.getZ() + 0.5, 0f, 0f);
        skeleton.setNoAi(true);
        level.addFreshEntity(skeleton);

        DefendProcess mission = submitDefend(rig);
        int[] waited = {0};

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig, () -> {
                waited[0]++;
                check(!mission.isActive(),
                    "waiting for the refusal");
            }))
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
                check(waited[0] <= REFUSAL_WINDOW_TICKS,
                    "a structural refusal lands on the engage tick, not "
                        + "after a chase; waited " + waited[0] + " ticks");
                List<String> kinds = rig.events().statusSnapshot(0)
                    .events().stream()
                    .map(BotEvent::kind).toList();
                check(!kinds.contains(EventKind.TASK_PAUSED),
                    "a standoff-range skeleton must not trip the engage "
                        + "reflex (no TASK_PAUSED expected)");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 4: an intruder inside the engage radius gets engaged,
     * chased the last step, and beaten down - through the PRODUCTION
     * path: no mission is submitted by the test; the idle-combat
     * reflex ({@code EngageOnHostileProximityRule}, 6-cell trigger)
     * notices the zombie and the controller's ENGAGE handling mints a
     * reflex-owned defend mission from the wiring's factory. This is
     * the exact chain whose absence caused the 2026-08-24 night-cave
     * death, so it stays pinned in-engine end to end.
     *
     * <p>The reflex mission ends with TARGET_ESCAPED once the target
     * stops existing — the bot cannot distinguish a killed target from
     * an escaped one (no death flag on EntitySnapshot; health=0 is not
     * filtered), so it reports the conservative failure verdict. The
     * behavioral assertions below (zombie dead, body alive) prove the
     * bot actually won the fight; the mission verdict is the honest
     * uncertainty signal, not a behavioral failure. See issue 0006.
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

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "waiting for the fight to end")))
            .thenWaitUntil(driveUntil(rig,
                () -> check(reflexEngageVerdict(rig.events()) != null,
                    "waiting for the reflex mission verdict")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                BotEvent verdict = reflexEngageVerdict(rig.events());
                check(verdict != null,
                    "the fight must be reflex-owned (a reflex-engage "
                        + "mission verdict in the stream)");
                checkEquals(DefendProcess.REASON_ESCAPED,
                    verdict.attrs().get("reason"),
                    "the absence-after-grace verdict must be TARGET_ESCAPED");
                check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "the zombie must not survive the engagement");
                check(rig.body().isAlive(),
                    "the body must walk away from this fight");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 6: submerged with low air, the drowning reflex holds
     * jump and the body surfaces. No mission is running — the only
     * upward force is the reflex's ASCEND action, so an idle body
     * without the reflex would sink to the pool floor and drown.
     *
     * <p>Why: issue 0004 F6(2) — the air-supply reflex is the
     * survival gate's drowning defense. The offline test
     * ({@code SurfaceOnLowAirRuleTest}) covers rule logic and
     * hysteresis; this pins the full in-engine chain:
     * {@code body.getAirSupply → sensor → ThreatBlackboard →
     * SurfaceOnLowAirRule → ReflexAction.ASCEND → BotController
     * jump=true → BotBodyEntity.setDrive → jumpInFluid(WATER_TYPE)
     * → buoyancy → surface → air regen +4/tick}.
     *
     * <p>Pool: the entire 16x16 floor becomes a four-deep water
     * column with a stone floor at FLOOR_Y-4. The body teleports
     * to the bottom center and air is forced to 60 (below the
     * trigger of 80) — natural drain from 300 takes 220 ticks,
     * too slow for a gametest. {@code setAirSupply} is public on
     * LivingEntity.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void surfacesWhenAirRunsLow(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Four-deep pool over the whole floor; stone at the bottom.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 2, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 3, z),
                    Blocks.WATER);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 4, z),
                    Blocks.SMOOTH_STONE);
            }
        }

        // Teleport to the pool bottom and force low air. The body
        // stands on the stone floor (onGround=true), so the first
        // tick fires jumpFromGround; once airborne the jumpInFluid
        // branch takes over for continuous buoyancy (issue 0004 F2).
        var bottomAbs = helper.absolutePos(
            new BlockPos(7, GametestRig.FLOOR_Y - 3, 7));
        rig.body().moveTo(bottomAbs.getX() + 0.5, bottomAbs.getY(),
            bottomAbs.getZ() + 0.5, 0f, 0f);
        rig.body().setAirSupply(60);
        // Body Y is ABSOLUTE; FLOOR_Y is structure-relative. The
        // gametest arena seats structures well below Y=0, so a raw
        // FLOOR_Y comparison can never pass - compare against the
        // absolute surface level instead (same convention as every
        // other position check in this suite: absolutePos first).
        int surfaceAbsY = helper.absolutePos(
            new BlockPos(0, GametestRig.FLOOR_Y, 0)).getY();

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getY() >= surfaceAbsY,
                    "waiting for the body to surface (current Y="
                        + String.format("%.1f", rig.body().getY())
                        + ")")))
            // Let air regen (+4/tick) carry past the trigger and
            // give the hysteresis hold window time to elapse.
            .thenExecuteFor(40, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must survive the submersion");
                check(rig.body().getAirSupply()
                        > SurfaceOnLowAirRule.TRIGGER_AIR,
                    "air must recover above the trigger after "
                        + "surfacing; got " + rig.body().getAirSupply());
                check(rig.body().getY() >= surfaceAbsY,
                    "the body must stay at or above the surface");
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

    /**
     * First terminal event of a reflex-owned engage mission, or null
     * while the fight is still running. The wiring's factory mints
     * task ids prefixed {@code reflex-engage-}, which is what makes
     * the production engage chain observable from outside.
     *
     * @param events the rig's event stream; never null
     * @return the verdict event, or null when no reflex mission has
     *         retired yet
     */
    private static BotEvent reflexEngageVerdict(
            com.mcbot.mcbotserver.core.event.InMemoryEventQueue events) {
        return events.statusSnapshot(0).events().stream()
            .filter(e -> EventKind.TASK_FAILED.equals(e.kind())
                || EventKind.TASK_COMPLETED.equals(e.kind()))
            .filter(e -> e.attrs().getOrDefault("task", "")
                .startsWith("reflex-engage"))
            .findFirst().orElse(null);
    }

    /**
     * Scenario 4b: the intruder fights BACK. The zombie keeps its AI;
     * with the carrier-side presence pass it acquires the body on
     * sight (world-treatment ruling 2026-08-25) and attacks on its
     * own, and any hit the body lands adds HurtByTargetGoal heat on
     * top - the fight is two-sided from the start. Weakened to 8
     * health on purpose: the pin is "engages, survives being fought,
     * wins", not a fair duel, and a short fight keeps the body
     * comfortably above the freeze threshold.
     *
     * <p>Roof: the whole floor gets a stone ceiling one layer below
     * the template top. The arena runs at daytime and an AI zombie is
     * sun-sensitive - without the roof the target burns instead of
     * fighting, which would test daylight, not retaliation.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void survivesRetaliatingZombie(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y + 7, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = EntityType.ZOMBIE.create(level);
        check(zombie != null, "zombie creation failed");
        var zAbs = helper.absolutePos(new BlockPos(7, GametestRig.WALK_Y, 8));
        zombie.moveTo(zAbs.getX() + 0.5, zAbs.getY(),
            zAbs.getZ() + 0.5, 0f, 0f);
        zombie.setHealth(8f);
        level.addFreshEntity(zombie);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "waiting for the retaliation fight to end")))
            .thenWaitUntil(driveUntil(rig,
                () -> check(reflexEngageVerdict(rig.events()) != null,
                    "waiting for the reflex mission verdict")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                BotEvent verdict = reflexEngageVerdict(rig.events());
                check(verdict != null,
                    "the retaliation fight must be reflex-owned");
                checkEquals(DefendProcess.REASON_ESCAPED,
                    verdict.attrs().get("reason"),
                    "a killed target ends as TARGET_ESCAPED");
                check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "the retaliating zombie must not survive");
                check(rig.body().isAlive(),
                    "the body must survive being fought back");
                check(rig.body().getHealth()
                        > FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                    "survival means staying above the freeze threshold, "
                        + "got " + rig.body().getHealth());
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 4c: a target behind a full wall is engaged (the threat
     * scan is distance-based and wall-blind - by design, see
     * LevelThreatSensor) but cannot be damaged: the bot closes to its
     * standoff, swings on cooldown, and every swing is eaten by
     * {@code MeleeResolver.sightBlocked}. The mission then fails
     * honestly by its own tick budget instead of pretending to win.
     *
     * <p>The zombie stays at exactly 20 health for the whole scenario
     * - that zero-damage fact is the pin. If the LOS clip regresses,
     * swings land and this fails with a damaged zombie.
     */
    @GameTest(template = "empty16x8x16",
        timeoutTicks = (int) BotAssembly.ENGAGE_MISSION_TIMEOUT_TICKS
            + 100)
    public static void holdsFireWhenSightBlocked(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        for (int z = 0; z < 16; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(8, GametestRig.FLOOR_Y + y, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = EntityType.ZOMBIE.create(level);
        check(zombie != null, "zombie creation failed");
        var zAbs = helper.absolutePos(new BlockPos(9, GametestRig.WALK_Y, 8));
        zombie.moveTo(zAbs.getX() + 0.5, zAbs.getY(),
            zAbs.getZ() + 0.5, 0f, 0f);
        zombie.setNoAi(true);
        level.addFreshEntity(zombie);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reflexEngageVerdict(rig.events()) != null,
                    "waiting for the walled-off engagement to time out")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                BotEvent verdict = reflexEngageVerdict(rig.events());
                check(verdict != null, "a verdict must be present");
                checkEquals(DefendProcess.REASON_TIMEOUT,
                    verdict.attrs().get("reason"),
                    "an undamageable target must fail by budget, "
                        + "not by success or escape");
                checkEquals(20f, zombie.getHealth(),
                    "no swing may land through a full wall");
                check(rig.body().isAlive(),
                    "the standoff must be harmless to the body too");
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
        rig.body().addEffect(new MobEffectInstance(
            MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));
        for (int x = 6; x <= 8; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.LAVA);
            }
        }
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell, 500);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival across the lava trench")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.isActive(),
                    "mission must retire after the lava crossing");
                check(mission.missionSucceeded(),
                    "crossing lava must be a success");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                checkEquals(20f, rig.body().getHealth(),
                    "fire resistance must hold for the whole crossing");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 4d: the WORLD starts the fight. The zombie spawns 7
     * cells out - outside the engage reflex's 6-cell trigger - with
     * clear line of sight and its AI on. Nothing the bot does can
     * start this engagement; the only path into the fight is the
     * zombie acquiring the body through the carrier-side presence
     * pass (world-treatment ruling 2026-08-25: monsters see the body
     * the way they would see a player, at their own follow range,
     * through their own line-of-sight ray).
     *
     * <p>Discriminative chain: target acquisition (z.getTarget is the
     * body) proves the presence pass; the kill then proves the
     * reflex-engage chain still fires when the threat closes in.
     * Weakened to 8 health like the retaliation scenario - the pin is
     * the acquisition and the fight, not a duel.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void hostilesAggroOnSight(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y + 7, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = EntityType.ZOMBIE.create(level);
        check(zombie != null, "zombie creation failed");
        var zAbs = helper.absolutePos(
            new BlockPos(10, GametestRig.WALK_Y, 8));
        zombie.moveTo(zAbs.getX() + 0.5, zAbs.getY(),
            zAbs.getZ() + 0.5, 0f, 0f);
        zombie.setHealth(8f);
        level.addFreshEntity(zombie);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(zombie.getTarget() == rig.body(),
                    "waiting for the world to acquire the body")))
            .thenWaitUntil(driveUntil(rig,
                () -> check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "waiting for the sight-started fight to end")))
            .thenWaitUntil(driveUntil(rig,
                () -> check(reflexEngageVerdict(rig.events()) != null,
                    "waiting for the reflex mission verdict")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                BotEvent verdict = reflexEngageVerdict(rig.events());
                check(verdict != null,
                    "the sight-started fight must be reflex-owned");
                checkEquals(DefendProcess.REASON_ESCAPED,
                    verdict.attrs().get("reason"),
                    "a killed target ends as TARGET_ESCAPED");
                check(zombie.isDeadOrDying() || zombie.isRemoved(),
                    "the aggressor must not survive");
                check(rig.body().isAlive(),
                    "the body must survive being hunted");
                check(rig.body().getHealth()
                        > FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                    "survival means staying above the freeze threshold, "
                        + "got " + rig.body().getHealth());
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 7: submerged in lava, the lava reflex holds jump and the
     * body surfaces. No mission is running - the only upward force is
     * the reflex's ASCEND action, so an idle body without the reflex
     * would sink to the pool floor (lava has no natural buoyancy) and
     * burn; fire resistance prevents damage but not sinking.
     *
     * <p>Why: issue 0008 F2/D2 - the lava reflex is the survival
     * gate's fastest-killer defense (4 HP/tick, 5 ticks from full
     * health per decompiled Entity.lavaHurt). The offline test
     * ({@code AscendInLethalFluidRuleTest}) covers rule logic and
     * hysteresis; this pins the full in-engine chain:
     * {@code body.isInLava → sensor → ThreatBlackboard.inLethalFluid
     * → AscendInLethalFluidRule → ReflexAction.ASCEND → BotController
     * jump=true → BotBodyEntity.setDrive → jumpInFluid(LAVA_TYPE)
     * → +0.3/tick upward impulse → surface}.
     *
     * <p>Pool: the entire 16x16 floor becomes a four-deep lava column
     * with a stone floor at FLOOR_Y-4. The body teleports to the
     * bottom center. Fire resistance (1200 ticks) prevents lava damage
     * for the whole scenario - the pin is the ascend, not survival.
     * Same no-floor-above-stone construction as the deep pool: the
     * stone at FLOOR_Y-4 is the only ground, so the body cannot
     * ground-hop and must use fluid buoyancy.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void ascendsFromLavaOnReflex(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        rig.body().addEffect(new MobEffectInstance(
            MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));

        // Four-deep lava pool over the whole floor; stone at the bottom.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 2, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 3, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 4, z),
                    Blocks.SMOOTH_STONE);
            }
        }

        // Teleport to the pool bottom. Lava has no natural buoyancy -
        // the body sinks without the reflex's held-jump ascend.
        var bottomAbs = helper.absolutePos(
            new BlockPos(7, GametestRig.FLOOR_Y - 3, 7));
        rig.body().moveTo(bottomAbs.getX() + 0.5, bottomAbs.getY(),
            bottomAbs.getZ() + 0.5, 0f, 0f);
        int surfaceAbsY = helper.absolutePos(
            new BlockPos(0, GametestRig.FLOOR_Y, 0)).getY();

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getY() >= surfaceAbsY,
                    "waiting for the body to surface from lava (current Y="
                        + String.format("%.1f", rig.body().getY())
                        + ")")))
            // Give the hysteresis hold window time to elapse after
            // clearing the lava surface, then a few ticks to confirm
            // the body stays up (no re-submersion).
            .thenExecuteFor(
                AscendInLethalFluidRule.LAVA_HOLD_TICKS + 5,
                driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must survive the lava submersion");
                checkEquals(20f, rig.body().getHealth(),
                    "fire resistance must hold for the whole ascend");
                check(rig.body().getY() >= surfaceAbsY,
                    "the body must stay at or above the lava surface");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 8: a solid block placed at the body's eye triggers the
     * suffocation reflex, which FREEZEs the body and pauses the active
     * mission. Removing the block lets the hold window elapse, the
     * reflex releases, and the mission resumes to completion.
     *
     * <p>Why: issue 0008 F4/D3 - suffocation is the one vital where
     * the rescue direction is UNKNOWN (a reflex guessing movement can
     * push the body deeper into glitch geometry), so FREEZE is
     * strictly correct. 1 HP/tick with no interval (LivingEntity.baseTick
     * decompiled 1.20.1), 20 ticks from full health. Priority 115 sits
     * between SURFACE (110) and LAVA (130) per the F9 triage table.
     *
     * <p>Discriminative chain: (1) the mission starts and the body
     * begins walking; (2) a block at eye level causes inWall=true and
     * the reflex fires FREEZE, preempting the mission (TASK_PAUSED);
     * (3) the block is removed, the 10-tick hold window elapses, the
     * reflex releases, and world revalidation resumes the mission
     * (TASK_RESUMED); (4) the body reaches the goal (TASK_COMPLETED).
     * Without the reflex, a body walking into a wall would keep
     * pathing and potentially glitch deeper - the FREEZE is the siren.
     *
     * <p>Suffocation damage is 1/tick; the wall is placed for well
     * under 20 ticks, so the body survives with health to spare. The
     * pin is the event sequence (PAUSED → RESUMED → COMPLETED), not a
     * damage number.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void freezesWhenSuffocating(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        // Let the mission start and the body take a step or two before
        // we seal its eye - proves the mission was genuinely running,
        // not parked from the start.
        int startX = rig.body().getBlockX();
        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getBlockX() > startX,
                    "waiting for the mission to start walking")))
            .thenExecute(() -> {
                // Seal the eye: a full solid block at the eye's block
                // position makes LivingEntity.isInWall() return true.
                // The body cannot walk through it, so it stays pinned
                // with its eye inside the block - exactly the glitch
                // geometry this reflex exists to catch.
                int eyeBlockY = (int) Math.floor(rig.body().getEyeY());
                helper.setBlock(new BlockPos(rig.body().getBlockX(),
                    eyeBlockY, rig.body().getBlockZ()), Blocks.SMOOTH_STONE);
            })
            .thenWaitUntil(driveUntil(rig,
                () -> assertEventSeen(rig.events(), EventKind.TASK_PAUSED)))
            .thenExecute(() -> {
                // The body must still be alive - suffocation is 1/tick
                // and the wall has been up for well under 20 ticks.
                check(rig.body().isAlive(),
                    "the body must survive the suffocation window");
                check(rig.body().getHealth() > 0,
                    "health must be positive after suffocation; got "
                        + rig.body().getHealth());
                // Remove the block so the eye clears and the reflex can
                // release after its hold window.
                int eyeBlockY = (int) Math.floor(rig.body().getEyeY());
                helper.setBlock(new BlockPos(rig.body().getBlockX(),
                    eyeBlockY, rig.body().getBlockZ()), Blocks.AIR);
            })
            // Hold window (10 ticks) + a few for revalidation and
            // mission re-seating.
            .thenExecuteFor(
                FreezeOnSuffocationRule.SUFFOCATION_HOLD_TICKS + 15,
                driveOnly(rig))
            .thenWaitUntil(driveUntil(rig,
                () -> assertEventSeen(rig.events(), EventKind.TASK_RESUMED)))
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival after suffocation clears")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.isActive(),
                    "mission must retire after the resumed walk");
                check(mission.missionSucceeded(),
                    "the resumed walk must be a success");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                check(rig.body().isAlive(),
                    "the body must survive the whole scenario");
                rig.body().discard();
            })
            .thenSucceed();
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
