package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingMenu;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.BotCraftingMenu;
import com.mcbot.mcbotserver.adapter.BotPlayerFacade;
import com.mcbot.mcbotserver.adapter.MenuOpener;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import com.mcbot.mcbotserver.core.reflex.EscapeLavaRule;
import com.mcbot.mcbotserver.core.reflex.DigOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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
        // Isolation wall (2 high, perimeter): the empty template has
        // no walls, so with 27 concurrent tests a NEIGHBOURING
        // structure's body can have clear line of sight to this
        // zombie from inside its 40-block presence box and win the
        // acquisition race (both bots scan every 20 ticks; whoever
        // hits the target==null zombie first claims it). The wall
        // makes the foreign sight-line fail while the in-structure
        // fight (7 blocks, clear interior) is untouched.
        for (int x = 0; x < 16; x++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y,
                    0), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y,
                    15), Blocks.SMOOTH_STONE);
            }
        }
        for (int z = 1; z < 15; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(0, GametestRig.FLOOR_Y + y,
                    z), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(15, GametestRig.FLOOR_Y + y,
                    z), Blocks.SMOOTH_STONE);
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
     * Scenario 7: submerged in lava, the lava escape reflex submits a
     * rescue GotoProcess to the nearest shore cell, and the pathing
     * behavior swims the body out of the fluid to dry ground. No
     * harness mission is running — the reflex owns the rescue from
     * trigger to arrival.
     *
     * <p>Why: issue 0008 D2 (upgraded from ASCEND-only). The earlier
     * ascendsFromLavaOnReflex pinned the held-jump surface half:
     * necessary but not sufficient — a body floating on the lava
     * surface still burns and goes nowhere horizontally. The ESCAPE
     * handoff unifies ascent and shore-reach under one pathing
     * mission (ReflexAction#ESCAPE, same shape as ENGAGE). This
     * scenario pins the full in-engine chain:
     * {@code body.isInLava → sensor → ThreatBlackboard.inLethalFluid
     * → EscapeLavaRule → ReflexAction.ESCAPE → BotController
     * preemptAndHold + rescueMissionFactory.get() → GotoProcess to
     * nearest shore → PathingBehavior swims through lava → arrival}.
     *
     * <p>Pool: a 5x5 two-deep lava column centered in the 16-wide
     * box, stone floor below, stone shore on all sides (the rest of
     * the floor). The body teleports to the pool center. Fire
     * resistance (1200 ticks) prevents lava damage — the pin is the
     * escape route, not survival. The shore is within the factory's
     * 12-block scan radius and is a valid isShore cell (non-liquid,
     * passable, walkable top below).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void escapesLavaToShore(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        rig.body().addEffect(new MobEffectInstance(
            MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));

        // 5x5 two-deep lava pool centered at (7, FLOOR_Y, 7); stone
        // floor at FLOOR_Y-2. The surrounding floor stays the
        // template's default (air above stone) — that is the shore.
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.LAVA);
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 2, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        // Ensure the shore ring has a solid floor (the template may
        // leave it air-only).
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x >= 5 && x <= 9 && z >= 5 && z <= 9) {
                    continue;
                }
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.SMOOTH_STONE);
            }
        }

        // Teleport to the pool center, one block below the surface.
        var centerAbs = helper.absolutePos(
            new BlockPos(7, GametestRig.FLOOR_Y - 1, 7));
        rig.body().moveTo(centerAbs.getX() + 0.5, centerAbs.getY(),
            centerAbs.getZ() + 0.5, 0f, 0f);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(!rig.body().isInLava(),
                    "waiting for the body to escape lava (current Y="
                        + String.format("%.1f", rig.body().getY())
                        + ", inLava=" + rig.body().isInLava() + ")")))
            // Give the rescue mission time to settle on the shore
            // and the reflex hold window to elapse.
            .thenExecuteFor(
                EscapeLavaRule.LAVA_ESCAPE_HOLD_TICKS + 10,
                driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must survive the lava escape");
                checkEquals(20f, rig.body().getHealth(),
                    "fire resistance must hold for the whole escape");
                check(!rig.body().isInLava(),
                    "the body must end on dry ground, not in lava");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 7b: burning to death, the fire-extinguish reflex submits
     * a rescue GotoProcess to the nearest water-adjacent cell, and the
     * pathing behavior walks the body into water contact, which
     * extinguishes the fire.
     *
     * <p>Why: issue 0008 D5-revised. The original D5 ruling was
     * "sense-only, no rule" on the assumption that fire is self-
     * limiting and self-answering. That holds for non-lethal fire,
     * but when remaining damage exceeds current health, waiting is a
     * death sentence. This rule fires only in that lethal band:
     * fireTicks/20 >= health (integer division, matching the engine's
     * 1.0F-per-20-ticks cadence — decompiled Entity.baseTick:483).
     *
     * <p>Setup: health=15, fireTicks=300 (15 damage remaining = lethal,
     * matching the lava-origin setSecondsOnFire(15) cap).
     * A water source sits 4 blocks east, contained in a stone basin so
     * it does not flow away. The reflex (priority 105) fires
     * immediately, submits a rescue to the nearest water-adjacent
     * cell, and the body walks to water. Water contact
     * (isInWaterRainOrBubble) clears remainingFireTicks. Assert:
     * fire extinguished, body survives above the freeze threshold.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void findsWaterWhenBurning(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Stone basin at x=11 to contain the water source. Water at
        // WALK_Y (bot's feet level) so the AABB overlaps the fluid and
        // isInWaterRainOrBubble extinguishes on contact.
        helper.setBlock(new BlockPos(11, GametestRig.FLOOR_Y, 7),
            Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(11, GametestRig.WALK_Y, 7),
            Blocks.WATER);
        helper.setBlock(new BlockPos(12, GametestRig.WALK_Y, 7),
            Blocks.SMOOTH_STONE);

        // Lethal fire: 300 ticks = 15 damage remaining; health=15 means
        // the burn will kill if it runs to completion (15 >= 15).
        // setHealth must precede setRemainingFireTicks so the sensor
        // reads the reduced health on the first drive tick.
        rig.body().setHealth(15f);
        rig.body().setRemainingFireTicks(300);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getRemainingFireTicks() <= 0,
                    "waiting for fire extinguish (ticks="
                        + rig.body().getRemainingFireTicks()
                        + ", health="
                        + String.format("%.1f", rig.body().getHealth())
                        + ")")))
            .thenExecuteFor(10, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must survive the burn");
                check(rig.body().getRemainingFireTicks() <= 0,
                    "the fire must be extinguished by water contact");
                check(rig.body().getHealth()
                        > FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                    "survival means staying above the freeze threshold, "
                        + "got " + rig.body().getHealth());
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 7c: a body trapped in a powder-snow pit accumulates
     * freeze progress; at the trigger threshold the climb reflex fires
     * (ASCEND = held jump), and vanilla climbable-block physics walks
     * the body up and out of the snow.
     *
     * <p>Why: issue 0008 F3. Powder snow is the least urgent vital
     * (140-tick freeze budget, 1 HP/40t after), but a body left
     * untended in a snow drift will eventually die. The reflex fires
     * at freezeTicks=100 (2 seconds before damage starts) and holds
     * jump; powder snow is climbable (Entity.isStateClimbable,
     * decompiled 1.20.1 Entity.java:764) so the held jump applies
     * the same upward impulse as a ladder. Thaw is -2/tick once the
     * body steps clear, so the freeze budget recovers fast.
     *
     * <p>Setup: a 3x3 two-deep powder-snow pit with a stone floor;
     * the body starts at the bottom layer (inside snow). No mission
     * is submitted - this is a pure reflex scenario. Assert: the body
     * climbs out (isFreezing goes false, freezeTicks starts
     * decreasing) and survives with no freeze damage taken (the
     * trigger fires before the fully-frozen damage phase).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void climbsOutOfPowderSnow(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Stone floor under the pit.
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.SMOOTH_STONE);
            }
        }
        // Two-deep powder snow pit (3x3). The body starts at WALK_Y
        // (y=1), inside the top snow layer.
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z),
                    Blocks.POWDER_SNOW);
                helper.setBlock(new BlockPos(x, GametestRig.WALK_Y, z),
                    Blocks.POWDER_SNOW);
            }
        }
        // Surrounding floor at FLOOR_Y-1 (stone) so the body has
        // somewhere to step after climbing out.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x >= 6 && x <= 8 && z >= 6 && z <= 8) {
                    continue;
                }
                helper.setBlock(
                    new BlockPos(x, GametestRig.FLOOR_Y - 1, z),
                    Blocks.SMOOTH_STONE);
            }
        }

        // Teleport into the bottom snow layer so freeze starts
        // immediately (the rig's default spawn is on the floor above).
        var pitAbs = helper.absolutePos(
            new BlockPos(7, GametestRig.FLOOR_Y, 7));
        rig.body().moveTo(pitAbs.getX() + 0.5, pitAbs.getY(),
            pitAbs.getZ() + 0.5, 0f, 0f);

        helper.startSequence()
            // Wait for the reflex to fire and the body to climb out.
            // isFreezing() goes false once the body leaves the snow
            // (wasInPowderSnow clears after one tick).
            .thenWaitUntil(driveUntil(rig,
                () -> check(!rig.body().isFreezing()
                        || rig.body().getTicksFrozen() < 80,
                    "waiting for the body to climb out of powder snow "
                        + "(freezeTicks="
                        + rig.body().getTicksFrozen()
                        + ", isFreezing=" + rig.body().isFreezing()
                        + ", Y=" + String.format("%.1f",
                            rig.body().getY()) + ")")))
            // Let thaw run for a bit so freezeTicks drops well below
            // the trigger, proving the body is genuinely clear.
            .thenExecuteFor(30, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must survive the powder-snow escape");
                check(rig.body().getTicksFrozen() < 100,
                    "freezeTicks must be below the trigger after escape, "
                        + "got " + rig.body().getTicksFrozen());
                checkEquals(20f, rig.body().getHealth(),
                    "no freeze damage should be taken (the reflex fires "
                        + "before the fully-frozen damage phase)");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 8: a solid block placed at the body's eye triggers the
     * suffocation reflex, which now DIGS the eye block out itself
     * (issue 0009 upgrade of the 0008 D3 stopgap), then lets the hold
     * window elapse, releases, and resumes the mission to completion.
     *
     * <p>Why: suffocation is 1 HP/tick with no interval
     * (LivingEntity.baseTick decompiled 1.20.1), 20 ticks from full
     * health. The FREEZE stopgap argued the rescue direction was
     * unknown; with dig capability it is known exactly - the eye
     * block - so the deterministic self-rescue replaces the halt.
     * DIRT seals the eye because it is the softest gravity-stable
     * block (hardness 0.5, drops without a tool): DigPacing charges
     * 15 bare-hand ticks, so the body escapes on a sliver of health -
     * authentic vanilla math (a real player hand-digs dirt exactly
     * this fast), not a balance choice. Priority 115 keeps its slot
     * between SURFACE (110) and LAVA (130) per the 0008 F9 triage
     * table.
     *
     * <p>Discriminative chain: (1) the mission starts and the body
     * begins walking; (2) a dirt block at eye level causes inWall=true
     * and the reflex preempts the mission (TASK_PAUSED); (3) the body
     * breaks the block ITSELF - the scenario never removes it, so the
     * only path to step (4) is the held dig claim accumulating through
     * the executor; (4) the 10-tick hold window elapses, the reflex
     * releases, world revalidation resumes the mission
     * (TASK_RESUMED); (5) the body reaches the goal (TASK_COMPLETED)
     * still alive, with damage actually taken (health strictly below
     * full - the scenario must prove the escape raced real damage,
     * not that no damage flowed).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void digsFreeWhenSuffocating(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        // Let the mission start and the body take a step or two before
        // we bury its eye - proves the mission was genuinely running,
        // not parked from the start.
        int startX = rig.body().getBlockX();
        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getBlockX() > startX,
                    "waiting for the mission to start walking")))
            .thenExecute(() -> {
                // Bury the eye: a full solid dirt block at the eye's
                // cell makes LivingEntity.isInWall() return true. Dirt
                // has no falling-gravity conversion (sand and gravel
                // would drop away mid-scenario) and 0.5 hardness.
                // toLocal is load-bearing: the body reports WORLD
                // coordinates while setBlock takes structure-local
                // ones - feeding absolutes through places the seal
                // outside the box and the scenario tests nothing.
                helper.setBlock(GametestRig.toLocal(helper,
                    new BlockPos(rig.body().getBlockX(),
                        (int) Math.floor(rig.body().getEyeY()),
                        rig.body().getBlockZ())), Blocks.DIRT);
            })
            .thenWaitUntil(driveUntil(rig,
                () -> assertEventSeen(rig.events(), EventKind.TASK_PAUSED)))
            // The self-rescue: the dig runs to completion with no
            // scenario-side removal. 15 dig ticks + latency headroom.
            .thenWaitUntil(driveUntil(rig,
                () -> check(
                    helper.getBlockState(GametestRig.toLocal(helper,
                        new BlockPos(rig.body().getBlockX(),
                            (int) Math.floor(rig.body().getEyeY()),
                            rig.body().getBlockZ()))).isAir(),
                    "the body must dig the eye block out itself")))
            .thenExecute(() -> {
                check(rig.body().isAlive(),
                    "the body must survive the suffocation window");
                check(rig.body().getHealth() > 0f
                        && rig.body().getHealth() < 20f,
                    "escape must race real damage: health strictly "
                        + "between 0 and 20, got "
                        + rig.body().getHealth());
            })
            // Hold window (10 ticks) + a few for revalidation and
            // mission re-seating.
            .thenExecuteFor(
                DigOnSuffocationRule.SUFFOCATION_HOLD_TICKS + 15,
                driveOnly(rig))
            .thenWaitUntil(driveUntil(rig,
                () -> assertEventSeen(rig.events(), EventKind.TASK_RESUMED)))
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival after the self-rescue")))
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

    /**
     * Scenario: the body's passive regeneration (carrier deviation,
     * workplan survival gate body-recovery policy) restores health from
     * a low value back to max. A mob carrier has zero vanilla regen, so
     * without the deviation health is monotonic and the body can never
     * recover between fights. This pins the full in-engine chain:
     * {@code BotBodyEntity.customServerAiStep → tickCount % 20 == 0 →
     * heal(1.0f) → LivingEntity health rises}.
     *
     * <p>Rate: 1 HP per 20 ticks (1 second), so from 5 to 20 takes
     * ~15 seconds (300 ticks). The timeout is generous; the test
     * asserts intermediate recovery first (health > start) and then
     * full recovery (health == max), so a partial regen failure is
     * distinguishable from a total one.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 400)
    public static void regeneratesHealthWhenBelowMax(
            GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        float startHealth = 5.0f;
        float maxHealth = rig.body().getMaxHealth();
        rig.body().setHealth(startHealth);

        helper.startSequence()
            // First gate: health must rise above the start value.
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getHealth() > startHealth,
                    "waiting for regen to start (current="
                        + String.format("%.1f", rig.body().getHealth())
                        + ")")))
            // Second gate: health must reach max.
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getHealth() >= maxHealth,
                    "waiting for full regen (current="
                        + String.format("%.1f", rig.body().getHealth())
                        + ", max=" + maxHealth + ")")))
            .thenExecuteFor(5, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(rig.body().isAlive(),
                    "the body must be alive after regen");
                check(rig.body().getHealth() >= maxHealth,
                    "health must be at or above max after regen; got "
                        + rig.body().getHealth());
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario: the DropSelected intent on the INTERACT channel spawns
     * the selected hotbar item as a world item entity and clears the
     * source slot. Full-stack drop (vanilla Q).
     *
     * <p>Why a gametest and not an offline assertion: the drop mutates
     * world state (spawns an ItemEntity) and the inventory container —
     * both need a real engine. The offline gate (DropSelectedIntentTest)
     * covers intent shape and channel validation; this pins the full
     * adapter chain: claim → BindingActor INTERACT dispatch → rising-edge
     * gate → BotBodyEntity.dropSelectedItem → spawnAtLocation + slot clear.
     *
     * <p>Rising-edge: the claim is submitted once before the sequence;
     * the first driveTick flushes the actor and fires the drop. The
     * claim expires at flush, so subsequent ticks do not re-drop even
     * though driveUntil keeps driving.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dropsSelectedItem(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var level = helper.getLevel();

        // Put 16 diamonds in hotbar slot 0 (the default selected slot).
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.DIAMOND, 16));
        checkEquals(16, rig.body().getInventory().container()
            .getItem(0).getCount(), "setup: 16 diamonds in slot 0");

        // Submit the drop claim. Priority 200 beats any reflex or
        // behavior that might contest INTERACT (none should in an idle
        // body, but the high priority is defensive).
        rig.actor().submit(new Claim(Channel.INTERACT, 200, "gt-drop",
            new Intent.DropSelected(true)));

        helper.startSequence()
            // First driveTick fires the drop; subsequent ticks let the
            // ItemEntity appear in getEntitiesOfClass (spawnAtLocation
            // registers it immediately, but the entity tick may take one
            // cycle to be visible).
            .thenWaitUntil(driveUntil(rig, () -> {
                var items = level.getEntitiesOfClass(ItemEntity.class,
                    rig.body().getBoundingBox().inflate(3.0));
                check(!items.isEmpty(),
                    "an ItemEntity must spawn after DropSelected");
            }))
            .thenExecuteAfter(0, () -> {
                var items = level.getEntitiesOfClass(ItemEntity.class,
                    rig.body().getBoundingBox().inflate(3.0));
                ItemEntity dropped = items.get(0);
                check(Items.DIAMOND.equals(dropped.getItem().getItem()),
                    "the dropped item must be a diamond, got "
                        + dropped.getItem().getItem());
                checkEquals(16, dropped.getItem().getCount(),
                    "full-stack drop must spawn all 16 diamonds");
                check(rig.body().getInventory().container()
                    .getItem(0).isEmpty(),
                    "the source slot must be empty after a full-stack drop");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario: the InteractBlock intent on the INTERACT channel places
     * the selected hotbar block against the clicked face and shrinks the
     * source stack by one. Phase 1 scope: default-state block placement
     * only (no direction-aware states, no use-block / menu opening) —
     * see InteractBlockExecutor Javadoc for the deferred items.
     *
     * <p>Setup: a dirt block sits one cell south of the bot at foot
     * level; the bot holds 16 stone in hotbar slot 0. The claim targets
     * the dirt block's UP face, so the stone lands on top of the dirt
     * (target.relative(UP)). The bot is within bare-hand reach (≈1.4
     * blocks from eye to dirt center).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void placesBlockOnInteract(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        // Target: dirt one cell south at foot level. Click its UP face
        // to place on top of it.
        BlockPos target = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(target, Blocks.DIRT);
        BlockPos placePos = target.above();
        // Template-local coords must read through the helper - a raw
        // level read would interpret them as absolute world position
        // and answer with whatever sits outside the test box.
        check(helper.getBlockState(placePos).isAir(),
            "setup: placement cell must be air before the test");

        // Give the bot 16 stone in the selected hotbar slot (slot 0).
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.STONE, 16));
        checkEquals(16, rig.body().getInventory().container()
            .getItem(0).getCount(), "setup: 16 stone in slot 0");

        // Intents carry WORLD-absolute cells - the adapter reads them
        // straight out of the level, and its reach gate measures real
        // distances. The rig works in template-local coords, so convert
        // before submitting. hitPos: center of the UP face, absolute;
        // carried for Phase 2 direction-aware placement, unused by the
        // Phase 1 executor.
        BlockPos absTarget = helper.absolutePos(target);
        var hit = new com.mcbot.mcbotserver.api.types.Vec3(
            absTarget.getX() + 0.5, absTarget.getY() + 1.0,
            absTarget.getZ() + 0.5);

        // Submit the place claim and drive one tick so the actor flushes
        // and the rising-edge gate fires.
        rig.actor().submit(new Claim(Channel.INTERACT, 200, "gt-place",
            new Intent.InteractBlock(
                new com.mcbot.mcbotserver.api.types.CellPos(
                    absTarget.getX(), absTarget.getY(), absTarget.getZ()),
                com.mcbot.mcbotserver.api.types.Direction.UP,
                hit)));
        driveOnly(rig).run();

        helper.startSequence()
            // The block placement is synchronous inside the tick (setBlock
            // is immediate), but the item shrink and the level state may
            // take one cycle to settle in getBlockState.
            .thenWaitUntil(driveUntil(rig, () -> {
                check(
                    helper.getBlockState(placePos).is(Blocks.STONE),
                    "a stone block must be placed above the dirt target, "
                        + "got " + helper.getBlockState(placePos));
            }))
            .thenExecuteAfter(0, () -> {
                checkEquals(15, rig.body().getInventory().container()
                    .getItem(0).getCount(),
                    "the source stack must shrink by one after placement");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario: the bot opens a crafting table via {@link MenuOpener},
     * fills the 3x3 grid with diamonds, and takes the resulting diamond
     * block. This validates the Phase 2 crafting-table disclosure end to
     * end: {@link BotCraftingMenu} bypasses the vanilla
     * {@code slotChangedCraftingGrid} ServerPlayer cast (issue 0007
     * risk), {@link BindingMenu} drives {@code clicked()} with a no-op
     * synchronizer, and {@code ResultSlot.onTake} consumes the grid
     * materials on take.
     *
     * <p>CraftingMenu slot layout: 0=result, 1-9=crafting grid (3x3),
     * 10-36=player main, 37-45=hotbar. The bot starts with 9 diamonds in
     * hotbar slot 0 (menu slot 37); the result lands in hotbar slot 1
     * (menu slot 38).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void craftsDiamondBlockAtTable(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Place a crafting table one block south of the bot.
        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        // MenuOpener reads world-absolute positions (same as the level
        // the body lives in); convert from template-local.
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Give the bot 9 diamonds in hotbar slot 0.
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.DIAMOND, 9));
        checkEquals(9, rig.body().getInventory().container()
            .getItem(0).getCount(), "setup: 9 diamonds in hotbar 0");

        // Create facade + opener, open the crafting table.
        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(tableAbs);
        check(menuOpt.isPresent(),
            "crafting table must open a menu");
        var menu = menuOpt.get();
        checkEquals("crafting_table", menu.snapshot().type(),
            "menu type must be crafting_table");

        // Fill the 3x3 crafting grid (menu slots 1-9) with one diamond
        // each. Access the CraftingContainer through slot 1's container
        // (all grid slots share the same container).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        // Trigger result recomputation. BotCraftingMenu.slotsChanged
        // bypasses the vanilla ServerPlayer cast and resolves the recipe
        // directly against the server recipe manager.
        rawMenu.slotsChanged(craftSlots);

        // Verify the result slot holds a diamond block.
        ItemStack result = rawMenu.slots.get(0).getItem();
        check(result.is(Items.DIAMOND_BLOCK),
            "result must be diamond_block, got " + result.getItem());
        checkEquals(1, result.getCount(),
            "result must be 1 diamond block");

        // Disclosure shape: roles and sourcePos are in the snapshot.
        var snap = menu.snapshot();
        checkEquals(SlotRole.RESULT, snap.slot(0).role(),
            "slot 0 must be the RESULT role");
        checkEquals(SlotRole.GRID, snap.slot(1).role(),
            "slot 1 must be the GRID role");
        checkEquals(SlotRole.HOTBAR, snap.slot(38).role(),
            "slot 38 must be the HOTBAR role");
        checkEquals(SlotRole.MAIN, snap.slot(10).role(),
            "slot 10 must be the MAIN role");
        checkEquals(tableAbs, snap.sourcePos() == null ? null
                : new BlockPos(snap.sourcePos().x(), snap.sourcePos().y(),
                    snap.sourcePos().z()),
            "the crafting-table menu must carry its source position");

        // Take the result: left-click (button 0) the result slot (0).
        // ResultSlot.onTake consumes the 9 grid diamonds.
        menu.click(0, 0, MenuClick.PICKUP);
        checkEquals("minecraft:diamond_block",
            menu.snapshot().carried().itemId(),
            "carried must be diamond_block after take");
        checkEquals(1, menu.snapshot().carried().count(),
            "carried must be 1 diamond block");

        // Verify grid materials were consumed by ResultSlot.onTake.
        for (int i = 0; i < 9; i++) {
            check(craftSlots.getItem(i).isEmpty(),
                "crafting grid slot " + i + " must be empty after take");
        }

        // Place the diamond block into hotbar slot 1 (menu slot 38).
        menu.click(38, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(),
            "carried must be empty after placing in hotbar 1");
        ItemStack placed = rig.body().getInventory().container().getItem(1);
        check(placed.is(Items.DIAMOND_BLOCK),
            "diamond_block must be in hotbar 1, got " + placed.getItem());
        checkEquals(1, placed.getCount(),
            "hotbar 1 must hold 1 diamond block");

        // The original 9 diamonds in hotbar 0 were never touched (the
        // grid was filled directly in test setup, not via clicks).
        checkEquals(9, rig.body().getInventory().container()
            .getItem(0).getCount(),
            "hotbar 0 must still hold 9 diamonds (setup-filled grid)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: clicking a backpack slot in the inventory menu picks up
     * the item via {@code container.removeItem} — the path that was
     * broken by BridgeInventory's phantom super-constructed compartments
     * (review P1-1 on commit 3d53037). The inherited
     * {@code Inventory.removeItem} operated on the empty phantom lists
     * and returned EMPTY; every backpack click would silently pick up
     * nothing. This test forces that exact code path:
     * {@code doClick PICKUP} → {@code Slot.tryRemove} →
     * {@code Slot.remove} → {@code BridgeInventory.removeItem}.
     *
     * <p>InventoryMenu slot layout: 0=craft result, 1-4=craft grid,
     * 5-8=armor, 9-35=main backpack, 36-44=hotbar, 45=offhand.
     * BindingInventory index 9 (first backpack slot) = InventoryMenu
     * slot 9; BindingInventory index 0 (hotbar 0) = menu slot 36.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void picksUpFromBackpackSlot(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Put 8 diamonds in the first backpack slot (BindingInventory
        // index 9 = InventoryMenu slot 9). Hotbar stays empty.
        final int BACKPACK_MENU_SLOT = 9;
        final int HOTBAR0_MENU_SLOT = 36;
        rig.body().getInventory().container().setItem(9,
            new ItemStack(Items.DIAMOND, 8));
        checkEquals(8, rig.body().getInventory().container()
            .getItem(9).getCount(), "setup: 8 diamonds in backpack slot 9");
        check(rig.body().getInventory().container().getItem(0).isEmpty(),
            "setup: hotbar 0 must be empty");

        // Open the inventory menu through the facade.
        var facade = new BotPlayerFacade(rig.body());
        facade.syncPosition();
        var menu = new BindingMenu(facade.facadeInventoryMenu(),
            facade, "inventory", null);

        // Pick up: left-click the backpack slot. This goes through
        // Slot.tryRemove → Slot.remove → container.removeItem — the
        // phantom-compartment path that was broken before the override.
        menu.click(BACKPACK_MENU_SLOT, 0, MenuClick.PICKUP);
        checkEquals("minecraft:diamond", menu.snapshot().carried().itemId(),
            "after pickup: carried must be diamonds (removeItem must "
                + "read the binding container, not the phantom super list)");
        checkEquals(8, menu.snapshot().carried().count(),
            "after pickup: carried must be all 8 diamonds");
        check(rig.body().getInventory().container().getItem(9).isEmpty(),
            "after pickup: backpack slot 9 must be empty (removeItem "
                + "must have removed from the binding container)");

        // Place: left-click hotbar 0. This goes through setByPlayer →
        // setItem (the path that always worked).
        menu.click(HOTBAR0_MENU_SLOT, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(),
            "after place: carried must be empty");
        checkEquals(8, rig.body().getInventory().container()
            .getItem(0).getCount(),
            "after place: hotbar 0 must hold 8 diamonds");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu returns the grid materials to the
     * player inventory. Vanilla closes menus through the ServerPlayer
     * network path, which BotPlayerFacade does not have; furthermore both
     * {@code AbstractContainerMenu.removed} and
     * {@code CraftingMenu.removed -> clearContainer} gate item return on
     * {@code player instanceof ServerPlayer}. Without {@link BindingMenu#close()}
     * the grid items would be silently lost. This test fills the grid
     * directly (no result taken), closes, and verifies all 9 diamonds are
     * returned to the inventory.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsCraftingGridOnClose(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Give the bot 9 diamonds in hotbar 0 (separate from grid items).
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.DIAMOND, 9));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        // Fill grid with 9 diamonds directly (not via clicks from inventory).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        // Close: must return grid materials to inventory.
        menu.close();

        // Grid must be empty after close.
        for (int i = 0; i < 9; i++) {
            check(craftSlots.getItem(i).isEmpty(),
                "crafting grid slot " + i + " must be empty after close");
        }

        // The 9 grid diamonds merged with the 9 setup diamonds in hotbar 0
        // (placeItemBackInInventory prefers merging before finding a free
        // slot). 9 + 9 = 18.
        checkEquals(18, rig.body().getInventory().container()
            .getItem(0).getCount(),
            "hotbar 0 must hold 18 diamonds (9 setup + 9 returned from grid)");

        // Facade must be back on the inventory menu after close.
        check(facade.containerMenu == facade.facadeInventoryMenu(),
            "facade containerMenu must revert to inventory menu after close");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: {@link MenuOpener#open} rejects a target beyond the 4.5
     * block interaction reach. Without the reach gate the bot could open a
     * menu on any loaded chunk regardless of distance. The bot is at
     * (7, WALK_Y, 7); a chest at z=15 is 8 blocks away — well beyond
     * reach. The opener must return empty.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void rejectsMenuBeyondReach(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Place a chest 8 blocks south of the bot (z=15, bot at z=7).
        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 15);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menuOpt = opener.open(chestAbs);

        check(menuOpt.isEmpty(),
            "menu opener must return empty for a chest 8 blocks away "
                + "(beyond 4.5 block reach)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu must not corrupt an occupied
     * hotbar 0. The vanilla placeItemBackInInventory inherits its slot
     * search from Inventory; on the bridge that search used to read the
     * phantom items list, fall back to "slot 0 is free" unconditionally,
     * and merge the returned stacks into whatever slot 0 held — the
     * cobble would grow by the return count and the diamonds would
     * vanish. Regression for the phantom slot-search family (P0).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsGridWithoutCorruptingOccupiedSlot0(
            GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Slot 0 holds a different, non-full item: the corruption
        // trigger (a merge target that fails the same-item check).
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.COBBLESTONE, 32));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        // Mixed grid: 5 diamonds + 4 sticks — two kinds both returning.
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 5; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }
        for (int i = 5; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.STICK, 1));
        }

        menu.close();

        checkEquals(32, rig.body().getInventory().container()
            .getItem(0).getCount(),
            "slot 0 cobble count must be unchanged after close");
        check(Items.COBBLESTONE.equals(rig.body().getInventory()
                .container().getItem(0).getItem()),
            "slot 0 must still hold cobblestone, not a merged stack");
        checkEquals(5, countItems(rig, Items.DIAMOND),
            "all 5 grid diamonds must return to the inventory");
        checkEquals(4, countItems(rig, Items.STICK),
            "all 4 grid sticks must return to the inventory");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: closing a crafting menu with a completely full main
     * inventory must drop the grid materials at the body, not loop
     * forever. The vanilla loop's exit for a full inventory is
     * getFreeSlot() == -1; on the bridge that method used to read the
     * phantom items list (never full), so the loop spun forever on the
     * server tick thread. This test completing at all is the no-hang
     * assertion; the entity check pins where the items went.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void dropsGridMaterialsWhenInventoryFull(
            GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var level = helper.getLevel();

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // Every main slot a full cobble stack: no merge space, no free
        // slot — the only correct outcome is the drop path.
        var container = rig.body().getInventory().container();
        for (int i = 0; i < 36; i++) {
            container.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(tableAbs).orElseThrow();

        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        // Before the phantom fix this call never returned.
        menu.close();

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig, () -> {
                var items = level.getEntitiesOfClass(ItemEntity.class,
                    rig.body().getBoundingBox().inflate(3.0));
                int diamonds = items.stream()
                    .filter(e -> Items.DIAMOND.equals(
                        e.getItem().getItem()))
                    .mapToInt(e -> e.getItem().getCount())
                    .sum();
                check(diamonds == 9,
                    "the 9 grid diamonds must drop at the body when "
                        + "the inventory is full, saw " + diamonds);
            }))
            .thenExecuteAfter(0, () -> {
                checkEquals(36 * 64, countItems(rig, Items.COBBLESTONE),
                    "the full cobble inventory must be untouched");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario: closing the facade's inventory menu returns the 2x2
     * crafting-grid materials. Vanilla InventoryMenu.removed gates its
     * grid return on ServerPlayer, and until BotInventoryMenu existed
     * the facade's grid could not even hold materials (the vanilla
     * slotsChanged cast would throw). This test exercises both: setItem
     * into the 2x2 (no crash) and close (materials back).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void returnsInventoryMenuGridOnClose(
            GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.openInventory();

        // Fill the 2x2 grid (menu slots 1-4, container slots 0-3).
        var rawMenu = menu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 4; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        menu.close();

        for (int i = 0; i < 4; i++) {
            check(craftSlots.getItem(i).isEmpty(),
                "inventory-menu grid slot " + i
                    + " must be empty after close");
        }
        checkEquals(4, countItems(rig, Items.DIAMOND),
            "the 4 grid diamonds must return to the inventory");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: opening a second menu closes the first — vanilla
     * parity (ServerPlayer.openMenu closes the previous container). The
     * old binding must return its crafting-grid materials, become
     * terminal (clicks throw), and the new menu must take over.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void openingNewMenuClosesPrevious(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos chestLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos tableAbs = helper.absolutePos(tableLocal);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var tableMenu = opener.open(tableAbs).orElseThrow();

        // 9 diamonds into the table grid.
        var rawMenu = tableMenu.rawMenu();
        CraftingContainer craftSlots =
            (CraftingContainer) rawMenu.slots.get(1).container;
        for (int i = 0; i < 9; i++) {
            craftSlots.setItem(i, new ItemStack(Items.DIAMOND, 1));
        }

        var chestMenu = opener.open(chestAbs).orElseThrow();
        checkEquals("chest", chestMenu.snapshot().type(),
            "the second open must yield the chest menu");

        checkEquals(9, countItems(rig, Items.DIAMOND),
            "opening the chest must return the table-grid diamonds");
        for (int i = 0; i < 9; i++) {
            check(craftSlots.getItem(i).isEmpty(),
                "table grid slot " + i + " must be empty after the "
                    + "menu was closed by the next open");
        }

        boolean threw = false;
        try {
            tableMenu.click(1, 0, MenuClick.PICKUP);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(threw, "clicking a binding displaced by a newer menu "
            + "must throw (terminal state)");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a double chest opens as the full vanilla 54-slot
     * CompoundContainer merge (ChestBlock.getContainer, the same
     * helper the vanilla open path uses). The old behavior — one
     * half, 27 of 54 slots, no error — was the silent half-open the
     * review flagged.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void opensDoubleChestFullWidth(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // A west-east pair facing north: LEFT at x=6 connects east.
        BlockState left = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState right = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.RIGHT);
        BlockPos leftLocal = new BlockPos(6, GametestRig.WALK_Y, 8);
        BlockPos rightLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(leftLocal, left);
        helper.setBlock(rightLocal, right);

        // Setup guard: neighbor updates must not have collapsed the
        // pair back to singles — otherwise the test proves nothing.
        check(helper.getLevel().getBlockState(
                helper.absolutePos(leftLocal))
                .getValue(ChestBlock.TYPE) != ChestType.SINGLE,
            "setup: the left half must stay a double-chest half");

        var actor = rig.actor();
        BlockPos leftAbs = helper.absolutePos(leftLocal);
        var view = actor.openMenu(new CellPos(leftAbs.getX(),
            leftAbs.getY(), leftAbs.getZ()));
        check(view != null,
            "opening either half of a double chest must succeed");
        checkEquals(54, containerSlotsOf(view),
            "a double chest must expose all 54 container slots");
        checkEquals(SlotRole.CONTAINER, view.slot(53).role(),
            "slot 53 (last chest slot) must be CONTAINER");
        checkEquals(SlotRole.MAIN, view.slot(54).role(),
            "slot 54 (first player slot) must be MAIN");
        actor.closeMenu();

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: the deposit/retrieve round trip through the actor's
     * menu transactions — QUICK_MOVE a stack into the chest, close,
     * reopen (state survived the close), PICKUP it back out. Proves
     * the chest container is the real block entity, not a menu-side
     * copy, and that close/reopen is lossless.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void storesAndRetrievesFromChest(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.DIAMOND, 32));

        var actor = rig.actor();
        var view = actor.openMenu(new CellPos(chestAbs.getX(),
            chestAbs.getY(), chestAbs.getZ()));
        check(view != null, "the chest must open");
        checkEquals(27, containerSlotsOf(view),
            "a single chest exposes 27 container slots");

        // Role-based addressing: hotbar 0 is the FIRST HOTBAR-role
        // slot - its flat index differs per menu kind (54 here, 37 in
        // a crafting table). The role lookup is exactly the knowledge
        // the harness must never re-derive.
        int hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        view = actor.menuClick(hotbar0, 0, MenuClick.QUICK_MOVE);
        checkEquals(32, view.slot(0).item().count(),
            "the chest's first slot must hold the 32 diamonds");
        check(view.slot(hotbar0).isEmpty(),
            "hotbar 0 must be empty after the quick-move");
        actor.closeMenu();

        // Reopen: the deposit survived the close (real block entity).
        view = actor.openMenu(new CellPos(chestAbs.getX(),
            chestAbs.getY(), chestAbs.getZ()));
        checkEquals(32, view.slot(0).item().count(),
            "the deposit must survive close and reopen");
        hotbar0 = firstSlotWithRole(view, SlotRole.HOTBAR);
        view = actor.menuClick(0, 0, MenuClick.PICKUP);
        checkEquals(32, view.carried().count(),
            "the pickup must lift all 32 diamonds");
        view = actor.menuClick(hotbar0, 0, MenuClick.PICKUP);
        check(view.carried().isEmpty(),
            "the stack must land back in hotbar 0");
        actor.closeMenu();

        checkEquals(32, rig.body().getInventory().container()
            .getItem(0).getCount(),
            "hotbar 0 must hold the 32 diamonds again");

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: clicks on a world menu fail loudly once the bot is out
     * of the vanilla keep-open range. Vanilla enforces stillValid every
     * tick via the ServerPlayer; the facade is never ticked, so
     * BindingMenu re-runs the predicate per click against the synced
     * position. close() must keep working (returns items, lowers the
     * lid) regardless of distance.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void clickThrowsWhenBotWalksAway(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos chestLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(chestLocal, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestLocal);

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.open(chestAbs).orElseThrow();

        // ~9.2 blocks from the chest center: beyond the 8-block
        // keep-open range (BLOCK_REACH 4.5 + 3.5), well inside the
        // structure.
        rig.body().setPos(1.5, GametestRig.WALK_Y, 1.5);

        boolean threw = false;
        try {
            menu.click(0, 0, MenuClick.PICKUP);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        check(threw, "clicking a chest menu from beyond keep-open range "
            + "must throw, not silently act on the container");

        // close is the terminal cleanup path and stays usable.
        menu.close();

        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: armor placed through the inventory menu lands in the
     * correct equipment storage. Vanilla flat armor indices run
     * feet..head while the binding runs head..feet; before the bridge
     * translation, a helmet clicked into the menu's head slot
     * (containerSlot 39) silently landed in the feet storage slot.
     * Helmet goes to head storage (36), boots to feet storage (39),
     * and InventoryView reports them at armor[0]/armor[3].
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void equipsArmorThroughMenuClicks(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.DIAMOND_HELMET));
        container.setItem(1, new ItemStack(Items.DIAMOND_BOOTS));

        var facade = new BotPlayerFacade(rig.body());
        var opener = new MenuOpener(facade);
        var menu = opener.openInventory();

        // Inventory-menu layout: armor slots 5-8 (5=head, 8=feet),
        // hotbar slots 36-44 (hotbar 0 = menu 36, hotbar 1 = 37).
        menu.click(36, 0, MenuClick.PICKUP);
        checkEquals("minecraft:diamond_helmet",
            menu.snapshot().carried().itemId(),
            "pickup must lift the helmet from hotbar 0");
        menu.click(5, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(),
            "the helmet must land on the head armor slot");
        menu.click(37, 0, MenuClick.PICKUP);
        menu.click(8, 0, MenuClick.PICKUP);
        check(menu.snapshot().carried().isEmpty(),
            "the boots must land on the feet armor slot");

        check(Items.DIAMOND_HELMET.equals(container.getItem(36).getItem()),
            "head storage (binding 36) must hold the helmet");
        check(Items.DIAMOND_BOOTS.equals(container.getItem(39).getItem()),
            "feet storage (binding 39) must hold the boots");

        var view = rig.body().getInventory().snapshot();
        checkEquals("minecraft:diamond_helmet",
            view.armor().get(0).itemId(),
            "InventoryView armor[0] (head) must report the helmet");
        checkEquals("minecraft:diamond_boots",
            view.armor().get(3).itemId(),
            "InventoryView armor[3] (feet) must report the boots");

        menu.close();
        rig.body().discard();
        helper.succeed();
    }

    /**
     * Scenario: a full craft through the sanctioned boundary-A
     * surface — the actor's menu transactions (ledger 29), clicks
     * only, no raw container writes. This is the chain a core
     * click-sequence planner will ride: openMenu → place materials
     * via PICKUP right-clicks → take the result → closeMenu.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void craftsViaMenuTransactions(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        BlockPos tableLocal = new BlockPos(7, GametestRig.WALK_Y, 8);
        helper.setBlock(tableLocal, Blocks.CRAFTING_TABLE);
        BlockPos tableAbs = helper.absolutePos(tableLocal);

        // 9 diamonds in hotbar 0; they reach the grid through clicks.
        rig.body().getInventory().container().setItem(0,
            new ItemStack(Items.DIAMOND, 9));

        var actor = rig.actor();
        var view = actor.openMenu(new CellPos(tableAbs.getX(),
            tableAbs.getY(), tableAbs.getZ()));
        check(view != null, "openMenu must succeed at the table");
        checkEquals("crafting_table", view.type(),
            "the opened menu must be the crafting table");
        checkEquals(new CellPos(tableAbs.getX(), tableAbs.getY(),
                tableAbs.getZ()), view.sourcePos(),
            "the snapshot must carry the table's position");

        // Pick up the whole stack from hotbar 0 (crafting-menu slot
        // 37), then place one diamond per grid slot (right-click
        // deposits a single item).
        view = actor.menuClick(37, 0, MenuClick.PICKUP);
        checkEquals(9, view.carried().count(),
            "carried must be the 9 diamonds");
        for (int gridSlot = 1; gridSlot <= 9; gridSlot++) {
            view = actor.menuClick(gridSlot, 1, MenuClick.PICKUP);
        }
        check(view.carried().isEmpty(),
            "carried must be empty after nine single-item places");
        checkEquals("minecraft:diamond_block",
            view.slot(0).item().itemId(),
            "the result slot must recompute to diamond_block");

        // Take the result, land it in hotbar 1, close.
        view = actor.menuClick(0, 0, MenuClick.PICKUP);
        checkEquals("minecraft:diamond_block", view.carried().itemId(),
            "the take must lift the diamond_block");
        view = actor.menuClick(38, 0, MenuClick.PICKUP);
        check(view.carried().isEmpty(),
            "the result must land in hotbar 1");
        actor.closeMenu();
        check(actor.menuSnapshot() == null,
            "no menu session may survive closeMenu");

        ItemStack placed = rig.body().getInventory().container().getItem(1);
        check(placed.is(Items.DIAMOND_BLOCK) && placed.getCount() == 1,
            "hotbar 1 must hold exactly 1 diamond_block, got "
                + placed);

        rig.body().discard();
        helper.succeed();
    }

    /** Total count of one item kind across the whole binding
     * container (all 41 slots). */
    private static int countItems(GametestRig.Rig rig,
                                  net.minecraft.world.item.Item item) {
        var container = rig.body().getInventory().container();
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Count of CONTAINER-role slots in a menu snapshot (the opened
     * world container's width, independent of the player region). */
    private static int containerSlotsOf(MenuView view) {
        int total = 0;
        for (var slot : view.slots()) {
            if (slot.role() == SlotRole.CONTAINER) {
                total++;
            }
        }
        return total;
    }

    /** First flat slot index carrying the given role (e.g. the first
     * HOTBAR slot). Role-based addressing - flat layouts differ per
     * menu kind. */
    private static int firstSlotWithRole(MenuView view, SlotRole role) {
        for (var slot : view.slots()) {
            if (slot.role() == role) {
                return slot.index();
            }
        }
        throw new IllegalStateException("no slot with role " + role);
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
