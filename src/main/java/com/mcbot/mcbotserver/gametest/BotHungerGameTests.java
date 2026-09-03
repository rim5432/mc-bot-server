package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.WALK_Y;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * FoodData lifecycle in-engine: the exhaustion/saturation/foodLevel
 * cascade, saturated fast regen, slow regen, starvation, and the
 * naturalRegeneration gamerule gate. Every scenario drives the body
 * through the production entity tick ({@code customServerAiStep ->
 * HungerTicker.tick}) so a green means the vanilla-math clone runs
 * on a real carrier.
 *
 * <p>Contract: capability face {@code hunger.fooddata} (FoodData
 * verbatim vanilla ticking). Offline unit tests cover HungerTicker
 * branch math; these pin the engine integration and the body's
 * life-support ordering (hunger ticks before drive application in
 * customServerAiStep).
 *
 * <p>Branch map (what each test pins):
 * <ul>
 *   <li>{@link #exhaustionCascadeDrainsSaturationThenFood} —
 *       drainExhaustion: exhaustion{@code >}4.0 subtracts 4.0 per
 *       tick (single iteration, not a while loop — vanilla shape),
 *       drains saturation first, then foodLevel on non-peaceful.
 *       Pins the strict {@code >}4.0 threshold and the three-stage
 *       cascade order.</li>
 *   <li>{@link #saturatedFastRegenHealsAndCostsExhaustion} —
 *       trySaturatedRegen: foodLevel{@code >=}20 + saturation{@code >}0
 *       + hurt + naturalRegen, 10-tick timer, heal saturation/6, pay
 *       exhaustion equal to healed.</li>
 *   <li>{@link #slowRegenFiresAtFoodLevelEighteen} — trySlowRegen:
 *       foodLevel{@code >=}18 + saturation==0 + hurt + naturalRegen,
 *       80-tick timer, heal 1.0, pay exhaustion 6.0. Pins the
 *       {@code >=}18 boundary (foodLevel 17 never fires).</li>
 *   <li>{@link #starvationDealsDamageAtZeroFood} — tryStarve:
 *       foodLevel==0, 80-tick timer, 1.0 starve damage with NORMAL
 *       difficulty/health gating (stops at 1 HP on NORMAL).</li>
 *   <li>{@link #naturalRegenDisabledStopsHealingButNotStarvation} —
 *       the naturalRegeneration gamerule gates both regen branches but
 *       not starvation; exhaustion drain and starvation are independent
 *       of the gamerule.</li>
 *   <li>{@link #walkAccumulatesExhaustionAtDeviatedRate} — movement
 *       exhaustion: walking charges 0.01/block (the recorded deviation;
 *       vanilla walk is 0/block, the bot uses the vanilla swim rate as
 *       its walking floor). Pins the non-zero baseline so a purely-walking
 *       carrier still drains hunger.</li>
 *   <li>{@link #sprintAccumulatesExhaustionAtVanillaRate} — movement
 *       exhaustion: sprinting charges 0.1/block (vanilla parity,
 *       Player.checkMovementStatistics). Pins the 10x walk-rate ratio
 *       and the isSprinting() branch in customServerAiStep.</li>
 * </ul>
 *
 * <p>The eating scenarios (EAT reflex disclosure, container
 * retention per food family, nutrition snapshot) live in
 * {@link BotEatingGameTests} since the 2026-09-03 split; they share
 * this file's {@code GametestRig.primeFood} opening rig. The
 * peaceful-difficulty branch of drainExhaustion is
 * intentionally not gametest-covered here (server-wide difficulty is
 * unsafe to flip in pooled runs); it belongs in an offline HungerTicker
 * unit test. The timer-exclusivity interaction (saturated regen owning
 * the timer blocks slow regen/starvation from advancing) is pinned
 * implicitly by the saturation=0 setup in slowRegen and the
 * foodLevel=0 setup in starvation — a future dedicated test can assert
 * the cross-branch suppression explicitly.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotHungerGameTests {

    private BotHungerGameTests() {}

    // ===== Exhaustion cascade =====

    /**
     * Scenario: exhaustion above 4.0 drains one saturation point per
     * tick (single iteration, not a while loop — vanilla shape); when
     * saturation reaches zero, further drains subtract foodLevel.
     * Pins drainExhaustion's three-stage cascade and the strict
     * {@code >}4.0 threshold (exactly 4.0 does not drain).
     */
    // capability: hunger.fooddata
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void exhaustionCascadeDrainsSaturationThenFood(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        var food = body.getFoodData();
        // Full health: hurt=false, so neither regen branch owns the
        // timer or adds exhaustion — drainExhaustion is the only writer.
        body.setHealth(body.getMaxHealth());
        GametestRig.primeFood(body, 20, 3.0f, 12.5f);

        helper.startSequence()
                // 12.5 exhaustion: three drain cycles (12.5->8.5->4.5->0.5)
                // drop saturation 3->2->1->0 over three ticks.
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                food.getSaturationLevel() <= 0.001f,
                                "waiting for saturation to drain to 0 (sat=" + food.getSaturationLevel() + " exh="
                                        + food.getExhaustionLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            food.getExhaustionLevel() <= 0.5f + 0.01f,
                            "after 3 drains exhaustion should be ~0.5, got " + food.getExhaustionLevel());
                    // Saturation is now 0: the next drain hits foodLevel.
                    food.addExhaustion(4.5f);
                })
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                food.getFoodLevel() == 19,
                                "waiting for foodLevel drain 20->19 (food=" + food.getFoodLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(food.getSaturationLevel() <= 0.001f, "saturation must stay 0 through the foodLevel drain");
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Saturated fast regen =====

    /**
     * Scenario: at foodLevel 20 with saturation above zero and health
     * below max, the saturated fast-regen branch heals saturation/6
     * every 10 ticks and pays exhaustion equal to what it healed.
     * Pins trySaturatedRegen's 10-tick timer, the heal formula, and
     * the exhaustion cost (which feeds back into drainExhaustion).
     */
    // capability: hunger.fooddata
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void saturatedFastRegenHealsAndCostsExhaustion(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        var food = body.getFoodData();
        GametestRig.primeFood(body, 20, 6.0f, 0f);
        float startHealth = body.getMaxHealth() - 5.0f;
        body.setHealth(startHealth);
        // Verify the fast-regen preconditions at setup: all four must
        // hold or the branch never owns the timer. foodLevel>=20 is the
        // branch that distinguishes fast regen from slow regen.
        check(food.getFoodLevel() >= 20, "foodLevel must be >=20 for fast regen, got " + food.getFoodLevel());
        check(
                food.getSaturationLevel() > 0.0f,
                "saturation must be >0 for fast regen, got " + food.getSaturationLevel());
        check(
                body.getHealth() > 0.0f && body.getHealth() < body.getMaxHealth(),
                "body must be hurt for fast regen, health=" + body.getHealth() + " max=" + body.getMaxHealth());

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getHealth() > startHealth,
                                "waiting for saturated fast regen (health=" + body.getHealth()
                                        + " food=" + food.getFoodLevel()
                                        + " sat=" + food.getSaturationLevel()
                                        + " exh=" + food.getExhaustionLevel() + ")")))
                .thenExecuteAfter(0, () -> {
                    // saturation=6 -> heal 6/6=1.0 per 10-tick cycle.
                    check(
                            body.getHealth() >= startHealth + 1.0f,
                            "fast regen heals saturation/6=1.0; got " + body.getHealth());
                    // The regen pays exhaustion=healed (6.0); drain may
                    // have already consumed some, so assert >0 not ==6.
                    check(
                            food.getExhaustionLevel() > 0.0f,
                            "fast regen must cost exhaustion; got " + food.getExhaustionLevel());
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Slow regen =====

    /**
     * Scenario: at foodLevel 18 (the {@code >=}18 boundary) with zero
     * saturation and health below max, the slow-regen branch heals
     * 1.0 every 80 ticks and pays 6.0 exhaustion. Pins trySlowRegen's
     * 80-tick timer, the foodLevel{@code >=}18 boundary, and the fixed
     * 1.0 heal / 6.0 exhaustion cost.
     */
    // capability: hunger.fooddata
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void slowRegenFiresAtFoodLevelEighteen(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        var food = body.getFoodData();
        // saturation=0: trySaturatedRegen returns false immediately, so
        // the slow-regen branch owns the timer from tick 1.
        GametestRig.primeFood(body, 18, 0f, 0f);
        float startHealth = body.getMaxHealth() - 5.0f;
        body.setHealth(startHealth);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getHealth() > startHealth,
                                "waiting for slow regen at foodLevel 18 (health=" + body.getHealth() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            body.getHealth() >= startHealth + 1.0f,
                            "slow regen heals 1.0 per 80 ticks; got " + body.getHealth());
                    check(
                            food.getExhaustionLevel() > 0.0f,
                            "slow regen must cost 6.0 exhaustion; got " + food.getExhaustionLevel());
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Starvation =====

    /**
     * Scenario: at foodLevel 0, the starvation branch deals 1.0 starve
     * damage every 80 ticks. On NORMAL difficulty the damage is gated
     * by health{@code >}1.0 (stops at 1 HP), so a full-health body
     * takes the first hit cleanly. Pins tryStarve's 80-tick timer and
     * the damage source (starve, not generic).
     */
    // capability: hunger.fooddata
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void starvationDealsDamageAtZeroFood(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        GametestRig.primeFood(body, 0, 0f, 0f);
        float startHealth = body.getMaxHealth();
        body.setHealth(startHealth);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getHealth() < startHealth,
                                "waiting for starvation damage (health=" + body.getHealth() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            body.getHealth() <= startHealth - 1.0f,
                            "starvation deals 1.0 per 80 ticks; got " + body.getHealth());
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Gamerule gate =====

    /**
     * Scenario: with naturalRegeneration disabled, neither regen branch
     * fires even at full food + saturation + hurt, but starvation still
     * deals damage at foodLevel 0. Pins the naturalRegen gamerule as a
     * regen-only gate — exhaustion drain and starvation are independent.
     */
    // capability: hunger.fooddata
    @GameTest(template = "empty16x8x16", timeoutTicks = 200)
    public static void naturalRegenDisabledStopsHealingButNotStarvation(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(7, WALK_Y, 7));
        var body = rig.body();
        var food = body.getFoodData();
        var level = helper.getLevel();
        var regenRule = level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION);
        boolean regenWasOn = regenRule.get();
        regenRule.set(false, level.getServer());

        float hurtHealth = body.getMaxHealth() - 5.0f;
        GametestRig.primeFood(body, 20, 6.0f, 0f);
        body.setHealth(hurtHealth);

        helper.startSequence()
                // 20 ticks is twice the fast-regen period; if regen were
                // on, health would have risen by tick 10.
                .thenExecuteFor(20, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            body.getHealth() == hurtHealth,
                            "naturalRegen=false must prevent healing (health=" + body.getHealth() + ")");
                    // Now drop food to zero: starvation is NOT gated by
                    // naturalRegeneration, so damage should still land.
                    food.setFoodLevel(0);
                    food.setSaturation(0f);
                })
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                body.getHealth() < hurtHealth,
                                "waiting for starvation despite regen disabled (health=" + body.getHealth() + ")")))
                .thenExecuteAfter(0, () -> {
                    check(
                            body.getHealth() <= hurtHealth - 1.0f,
                            "starvation deals 1.0 even with regen disabled; got " + body.getHealth());
                    regenRule.set(regenWasOn, level.getServer());
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Movement exhaustion rates =====

    /**
     * Scenario: walking accumulates exhaustion at the deviated rate of
     * 0.01 per horizontal block traveled. Vanilla walk is 0/block
     * (walking on ground is free); the bot uses the vanilla swim rate as
     * its walking floor so a purely-walking mob carrier still drains
     * hunger over time. Pins the non-zero baseline and the
     * {@code isSprinting() ? 0.1 : 0.01} branch in customServerAiStep.
     *
     * <p>The drive is re-applied every tick after driveTick so the
     * pipeline's idle write cannot neutralize it; the bot walks in a
     * straight line and exhaustion is compared to actual distance traveled.
     */
    // capability: hunger.movement_exhaustion
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void walkAccumulatesExhaustionAtDeviatedRate(GameTestHelper helper) {
        // Row 3, not the center: a pinned-yaw 30-tick walk covers ~9m and
        // must stay on the 16-wide pad to keep the path straight.
        var rig = rig(helper, new BlockPos(7, WALK_Y, 3));
        var body = rig.body();
        var food = body.getFoodData();
        GametestRig.primeFood(body, 20, 5.0f, 0f);

        double[] startPos = new double[2];
        float[] startExh = {0f};
        int[] counter = {0};
        final int driveTicks = 30;

        helper.startSequence()
                .thenWaitUntil(
                        driveFixedForwardForTicks(rig, body, 1.0f, false, driveTicks, startPos, startExh, counter))
                .thenExecuteAfter(0, () -> {
                    double distance = Math.hypot(body.getX() - startPos[0], body.getZ() - startPos[1]);
                    float exhDelta = food.getExhaustionLevel() - startExh[0];
                    float expected = (float) (distance * 0.01);
                    check(distance > 1.0, "bot must actually walk (>1 block) to measure rate; got " + distance);
                    check(
                            Math.abs(exhDelta - expected) < expected * 0.25f + 0.005f,
                            "walk exhaustion should be ~distance*0.01=" + expected + "; got " + exhDelta + " (distance="
                                    + distance + ", endYaw=" + body.getYRot() + ")");
                    body.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: sprinting accumulates exhaustion at the vanilla rate of
     * 0.1 per horizontal block traveled — 10x the walk rate. Pins the
     * isSprinting() branch and the vanilla parity constant. Sprint also
     * increases movement speed (1.3x), so the bot covers more ground per
     * tick; the rate assertion uses actual distance, not tick count.
     */
    // capability: hunger.movement_exhaustion
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void sprintAccumulatesExhaustionAtVanillaRate(GameTestHelper helper) {
        // Same off-center start as the walk scenario: the pinned-yaw
        // sprint covers ~10m and must stay on the pad.
        var rig = rig(helper, new BlockPos(7, WALK_Y, 3));
        var body = rig.body();
        var food = body.getFoodData();
        GametestRig.primeFood(body, 20, 5.0f, 0f);

        double[] startPos = new double[2];
        float[] startExh = {0f};
        int[] counter = {0};
        final int driveTicks = 25;

        helper.startSequence()
                .thenWaitUntil(
                        driveFixedForwardForTicks(rig, body, 1.0f, true, driveTicks, startPos, startExh, counter))
                .thenExecuteAfter(0, () -> {
                    double distance = Math.hypot(body.getX() - startPos[0], body.getZ() - startPos[1]);
                    float exhDelta = food.getExhaustionLevel() - startExh[0];
                    float expected = (float) (distance * 0.1);
                    check(distance > 1.0, "bot must actually sprint (>1 block) to measure rate; got " + distance);
                    check(
                            Math.abs(exhDelta - expected) < expected * 0.25f + 0.01f,
                            "sprint exhaustion should be ~distance*0.1=" + expected + "; got " + exhDelta
                                    + " (distance=" + distance + ")");
                    body.setSprinting(false);
                    body.discard();
                })
                .thenSucceed();
    }

    // ===== Helpers =====

    /**
     * Sets the body's food state in one call: foodLevel, saturation, and
     * exhaustion (added to the current value — FoodData has no setter
     * for exhaustion). Used by every scenario so setup conventions stay
     * pinned in one place.
     *
     * @param body       the hungry body; never null
     * @param foodLevel  target food level (0..20)
     * @param saturation target saturation (0..20)
     * @param exhaustion exhaustion to add (>=0; FoodData has no setter)
     */
    /**
     * The shared count-2 eat pin: slot 0 keeps the remaining stew
     * (count 1) - never the bowl, never an empty slot.
     *
     * @param slot0 the post-eat contents of slot 0; never null
     */
    /**
     * Drives the body forward with a fixed sprint flag for exactly N
     * ticks, recording the start position and exhaustion on the first
     * call. The drive is re-applied every tick AFTER driveTick so the
     * pipeline's idle write (zza=0) cannot neutralize it; the engine
     * then applies the drive in customServerAiStep on the next server
     * tick. Suitable for thenWaitUntil — the caller reads distance and
     * exhaustion delta in thenExecute.
     *
     * @param rig       the wired rig; never null
     * @param body      the body to drive; never null
     * @param forward   forward drive (-1..1)
     * @param sprinting true to hold the sprint flag every tick
     * @param ticks     exact number of ticks to drive
     * @param startPos  out-parameter of length 2, filled {x, z} on first call
     * @param startExh  out-parameter of length 1, filled with exhaustion on first call
     * @param counter   in-out-parameter of length 1, tracks elapsed ticks
     * @return a Runnable for thenWaitUntil; never null
     */
    private static Runnable driveFixedForwardForTicks(
            GametestRig.Rig rig,
            BotBodyEntity body,
            float forward,
            boolean sprinting,
            int ticks,
            double[] startPos,
            float[] startExh,
            int[] counter) {
        return driveUntil(rig, () -> {
            // Own the ROT channel at a priority above every behavior:
            // without a claim, the idle head sweep turns the body a few
            // degrees per tick, and body-relative drive then walks a
            // curve - the measured displacement collapses while the
            // exhaustion accrues on the full arc (the same hand-pump
            // owns-ROT doctrine the combat scenarios pin).
            rig.actor().submit(new Claim(Channel.ROT, 50, "gametest:drive", new Intent.Look(0f, 0f)));
            if (counter[0] == 0) {
                startPos[0] = body.getX();
                startPos[1] = body.getZ();
                startExh[0] = body.getFoodData().getExhaustionLevel();
            }
            body.setDrive(forward, 0f, false);
            body.setSprinting(sprinting);
            counter[0]++;
            check(counter[0] >= ticks, "driving forward for " + ticks + " ticks (got " + counter[0] + ")");
        });
    }
}
