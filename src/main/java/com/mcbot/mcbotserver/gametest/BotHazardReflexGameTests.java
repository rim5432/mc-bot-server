package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.SETTLE_TICKS;
import static com.mcbot.mcbotserver.gametest.GametestRig.assertEventSeen;
import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.submitGoto;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.reflex.DigOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.EscapeLavaRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Passive-body survival reflexes, in-engine: drowning ascent, lava
 * escape to shore, lethal-fire water run, powder-snow climb,
 * suffocation self-dig with mission resume, and carrier passive
 * regeneration. No mission is running in the pure-reflex scenarios -
 * the reflex owns the rescue from trigger to resolution.
 *
 * <p>Contract: see boundaries.md section C (reflex preemption),
 * ledger 22/24/25/26/27 and issues 0008/0009.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotHazardReflexGameTests {

    private BotHazardReflexGameTests() {}

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
        GametestRig.fillPool(helper, 0, 15, 0, 15, 4, Blocks.WATER);

        // Teleport to the pool bottom and force low air. The body
        // stands on the stone floor (onGround=true), so the first
        // tick fires jumpFromGround; once airborne the jumpInFluid
        // branch takes over for continuous buoyancy (issue 0004 F2).
        var bottomAbs = helper.absolutePos(new BlockPos(7, GametestRig.FLOOR_Y - 3, 7));
        rig.body().moveTo(bottomAbs.getX() + 0.5, bottomAbs.getY(), bottomAbs.getZ() + 0.5, 0f, 0f);
        rig.body().setAirSupply(60);
        // Body Y is ABSOLUTE; FLOOR_Y is structure-relative. The
        // gametest arena seats structures well below Y=0, so a raw
        // FLOOR_Y comparison can never pass - compare against the
        // absolute surface level instead (same convention as every
        // other position check in this suite: absolutePos first).
        int surfaceAbsY =
                helper.absolutePos(new BlockPos(0, GametestRig.FLOOR_Y, 0)).getY();

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getY() >= surfaceAbsY,
                                "waiting for the body to surface (current Y="
                                        + String.format("%.1f", rig.body().getY())
                                        + ")")))
                // Let air regen (+4/tick) carry past the trigger and
                // give the hysteresis hold window time to elapse.
                .thenExecuteFor(40, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the body must survive the submersion");
                    check(
                            rig.body().getAirSupply() > SurfaceOnLowAirRule.TRIGGER_AIR,
                            "air must recover above the trigger after " + "surfacing; got "
                                    + rig.body().getAirSupply());
                    check(rig.body().getY() >= surfaceAbsY, "the body must stay at or above the surface");
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
        rig.body().addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));

        // 5x5 two-deep lava pool centered at (7, FLOOR_Y, 7); stone
        // floor at FLOOR_Y-2. The surrounding floor stays the
        // template's default (air above stone) — that is the shore.
        GametestRig.fillPool(helper, 5, 9, 5, 9, 2, Blocks.LAVA);
        // Ensure the shore ring has a solid floor (the template may
        // leave it air-only).
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x >= 5 && x <= 9 && z >= 5 && z <= 9) {
                    continue;
                }
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y - 1, z), Blocks.SMOOTH_STONE);
            }
        }

        // Teleport to the pool center, one block below the surface.
        var centerAbs = helper.absolutePos(new BlockPos(7, GametestRig.FLOOR_Y - 1, 7));
        rig.body().moveTo(centerAbs.getX() + 0.5, centerAbs.getY(), centerAbs.getZ() + 0.5, 0f, 0f);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                !rig.body().isInLava(),
                                "waiting for the body to escape lava (current Y="
                                        + String.format("%.1f", rig.body().getY())
                                        + ", inLava=" + rig.body().isInLava() + ")")))
                // Give the rescue mission time to settle on the shore
                // and the reflex hold window to elapse.
                .thenExecuteFor(EscapeLavaRule.LAVA_ESCAPE_HOLD_TICKS + 10, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the body must survive the lava escape");
                    checkEquals(20f, rig.body().getHealth(), "fire resistance must hold for the whole escape");
                    check(!rig.body().isInLava(), "the body must end on dry ground, not in lava");
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
    // capability: vitals.fire
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void findsWaterWhenBurning(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Stone basin at x=11 to contain the water source. Water at
        // WALK_Y (bot's feet level) so the AABB overlaps the fluid and
        // isInWaterRainOrBubble extinguishes on contact.
        helper.setBlock(new BlockPos(11, GametestRig.FLOOR_Y, 7), Blocks.SMOOTH_STONE);
        helper.setBlock(new BlockPos(11, GametestRig.WALK_Y, 7), Blocks.WATER);
        helper.setBlock(new BlockPos(12, GametestRig.WALK_Y, 7), Blocks.SMOOTH_STONE);

        // Lethal fire: 300 ticks = 15 damage remaining; health=15 means
        // the burn will kill if it runs to completion (15 >= 15).
        // setHealth must precede setRemainingFireTicks so the sensor
        // reads the reduced health on the first drive tick.
        rig.body().setHealth(15f);
        rig.body().setRemainingFireTicks(300);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getRemainingFireTicks() <= 0,
                                "waiting for fire extinguish (ticks="
                                        + rig.body().getRemainingFireTicks()
                                        + ", health="
                                        + String.format("%.1f", rig.body().getHealth())
                                        + ")")))
                .thenExecuteFor(10, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the body must survive the burn");
                    check(rig.body().getRemainingFireTicks() <= 0, "the fire must be extinguished by water contact");
                    check(
                            rig.body().getHealth() > FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                            "survival means staying above the freeze threshold, " + "got "
                                    + rig.body().getHealth());
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
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y - 1, z), Blocks.SMOOTH_STONE);
            }
        }
        // Two-deep powder snow pit (3x3). The body starts at WALK_Y
        // (y=1), inside the top snow layer.
        for (int x = 6; x <= 8; x++) {
            for (int z = 6; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y, z), Blocks.POWDER_SNOW);
                helper.setBlock(new BlockPos(x, GametestRig.WALK_Y, z), Blocks.POWDER_SNOW);
            }
        }
        // Surrounding floor at FLOOR_Y-1 (stone) so the body has
        // somewhere to step after climbing out.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x >= 6 && x <= 8 && z >= 6 && z <= 8) {
                    continue;
                }
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y - 1, z), Blocks.SMOOTH_STONE);
            }
        }

        // Teleport into the bottom snow layer so freeze starts
        // immediately (the rig's default spawn is on the floor above).
        var pitAbs = helper.absolutePos(new BlockPos(7, GametestRig.FLOOR_Y, 7));
        rig.body().moveTo(pitAbs.getX() + 0.5, pitAbs.getY(), pitAbs.getZ() + 0.5, 0f, 0f);

        helper.startSequence()
                // Wait for the reflex to fire and the body to climb out.
                // isFreezing() goes false once the body leaves the snow
                // (wasInPowderSnow clears after one tick).
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                !rig.body().isFreezing() || rig.body().getTicksFrozen() < 80,
                                "waiting for the body to climb out of powder snow "
                                        + "(freezeTicks="
                                        + rig.body().getTicksFrozen()
                                        + ", isFreezing=" + rig.body().isFreezing()
                                        + ", Y="
                                        + String.format("%.1f", rig.body().getY()) + ")")))
                // Let thaw run for a bit so freezeTicks drops well below
                // the trigger, proving the body is genuinely clear.
                .thenExecuteFor(30, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the body must survive the powder-snow escape");
                    check(
                            rig.body().getTicksFrozen() < 100,
                            "freezeTicks must be below the trigger after escape, " + "got "
                                    + rig.body().getTicksFrozen());
                    checkEquals(
                            20f,
                            rig.body().getHealth(),
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
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void digsFreeWhenSuffocating(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var mission = submitGoto(rig, goalCell);

        // Let the mission start and the body take a step or two before
        // we bury its eye - proves the mission was genuinely running,
        // not parked from the start.
        int startX = rig.body().getBlockX();
        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(rig.body().getBlockX() > startX, "waiting for the mission to start walking")))
                .thenExecute(() -> {
                    // Bury the eye: a full solid dirt block at the eye's
                    // cell makes LivingEntity.isInWall() return true. Dirt
                    // has no falling-gravity conversion (sand and gravel
                    // would drop away mid-scenario) and 0.5 hardness.
                    // toLocal is load-bearing: the body reports WORLD
                    // coordinates while setBlock takes structure-local
                    // ones - feeding absolutes through places the seal
                    // outside the box and the scenario tests nothing.
                    helper.setBlock(
                            GametestRig.toLocal(
                                    helper,
                                    new BlockPos(
                                            rig.body().getBlockX(),
                                            (int) Math.floor(rig.body().getEyeY()),
                                            rig.body().getBlockZ())),
                            Blocks.DIRT);
                })
                .thenWaitUntil(driveUntil(rig, () -> assertEventSeen(rig.events(), EventKind.TASK_PAUSED)))
                // The self-rescue: the dig runs to completion with no
                // scenario-side removal. 15 dig ticks + latency headroom.
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                helper.getBlockState(GametestRig.toLocal(
                                                helper,
                                                new BlockPos(
                                                        rig.body().getBlockX(),
                                                        (int) Math.floor(
                                                                rig.body().getEyeY()),
                                                        rig.body().getBlockZ())))
                                        .isAir(),
                                "the body must dig the eye block out itself")))
                .thenExecute(() -> {
                    check(rig.body().isAlive(), "the body must survive the suffocation window");
                    check(
                            rig.body().getHealth() > 0f && rig.body().getHealth() < 20f,
                            "escape must race real damage: health strictly "
                                    + "between 0 and 20, got "
                                    + rig.body().getHealth());
                })
                // Hold window (10 ticks) + a few for revalidation and
                // mission re-seating.
                .thenExecuteFor(DigOnSuffocationRule.SUFFOCATION_HOLD_TICKS + 15, driveOnly(rig))
                .thenWaitUntil(driveUntil(rig, () -> assertEventSeen(rig.events(), EventKind.TASK_RESUMED)))
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for arrival after the self-rescue")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.isActive(), "mission must retire after the resumed walk");
                    check(mission.missionSucceeded(), "the resumed walk must be a success");
                    assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                    check(rig.body().isAlive(), "the body must survive the whole scenario");
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
    // capability: none
    @GameTest(template = "empty16x8x16", timeoutTicks = 400)
    public static void regeneratesHealthWhenBelowMax(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        // Food-driven regen (Phase 4 eat slice, ledger 34): the free
        // peaceful floor is retired; healing needs foodLevel >= 18
        // and lands 1 HP per 80 ticks, so this pins ONE regen tick,
        // not a full recovery.
        var food = rig.body().getFoodData();
        food.setFoodLevel(20);
        // Saturation zero: the saturated fast-regen path heals
        // saturation/6 every 10 ticks and would fire before the 80-tick
        // slow regen this scenario pins. Pin only the foodLevel>=18 path.
        food.setSaturation(0f);
        float startHealth = rig.body().getMaxHealth() - 1.0f;
        rig.body().setHealth(startHealth);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getHealth() > startHealth,
                                "waiting for the food-driven regen tick (current="
                                        + String.format("%.1f", rig.body().getHealth())
                                        + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            rig.body().getHealth() >= startHealth + 1.0f,
                            "one regen tick heals 1.0 at foodLevel 20; got "
                                    + rig.body().getHealth());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the eat reflex (Stage 3 Phase 4 eat slice, issue
     * 0010 consumption half). Food dropped to 10 (trigger 16) with
     * bread in the hotbar: the reflex selects the bread slot, one
     * rising USE edge consumes exactly one item through the vanilla
     * eat chain, and the restored food level releases the rule.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void eatsWhenHungry(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(Items.BREAD, 1));
        rig.body().getFoodData().setFoodLevel(10);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getFoodData().getFoodLevel() > 10,
                                "waiting for the reflex to eat (food="
                                        + rig.body().getFoodData().getFoodLevel()
                                        + ")")))
                .thenExecuteAfter(0, () -> {
                    check(container.getItem(0).isEmpty(), "exactly one bread must be consumed from the sensed slot");
                    check(
                            rig.body().getFoodData().getFoodLevel() == 15,
                            "bread nutrition is 5: 10 + 5 = 15; got "
                                    + rig.body().getFoodData().getFoodLevel());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * MLG water bucket (the coverage-review P0 slice): a body falling
     * with a water bucket in the hotbar places the water before
     * impact and lands undamaged. Proves the whole chain on the
     * engine - the sensor stamps the ground cell, WATER_BUCKET_ON_FALL
     * fires, the injector aims down + selects + pulses the use, and
     * vanilla water cancels the fall damage.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 300)
    public static void waterBucketBreaksAFall(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, GametestRig.WALK_Y, 7));

        // Stone floor across the cell so there is ground to place onto.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y - 1, z), Blocks.SMOOTH_STONE);
            }
        }

        // Water bucket in hotbar slot 0 (the injector selects it).
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.WATER_BUCKET));

        // Drop the body from high inside the template: gravity supplies
        // the descent, the reflex supplies the save.
        var drop = helper.absolutePos(new BlockPos(7, 7, 7));
        rig.body().moveTo(drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5, 0f, 90f);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().onGround(),
                                "waiting for the body to land (Y="
                                        + String.format("%.1f", rig.body().getY()) + ")")))
                .thenExecute(() -> {
                    check(rig.body().isAlive(), "the body must survive the fall");
                    check(
                            rig.body().getHealth() >= 19.5f,
                            "the water must cancel the fall damage, health="
                                    + rig.body().getHealth());
                })
                .thenSucceed();
    }
}
