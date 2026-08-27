package com.mcbot.mcbotserver.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Dig pacing gates (issue 0009, extended by issue 0007 Phase 1 tool
 * supplier): the destroy-progress arithmetic mirrors vanilla's numbers
 * so block-break timing matches a player holding left-click with the
 * selected hotbar item, and the Dig intent pairs only with the INTERACT
 * channel.
 *
 * <p>Contract: see boundaries.md decision 25. Reference points from
 * the decompiled 1.20.1 tree: gravel (hardness 0.6, drops bare)
 * costs 18 hand ticks; stone (1.5, tool required) costs 150 bare-hand
 * but ~8 with an iron pickaxe (speed 6.0, correct tool → divisor 30);
 * zero-hardness blocks pop instantly (digSpeed/0 = +Infinity);
 * unbreakable blocks never accumulate.
 *
 * <p>Tool semantics (issue 0007 Phase 1): {@code toolSpeed} is the
 * selected item's {@code getDestroySpeed} against the block (1.0 for
 * bare hand or non-digger items, tier speed for matching tools).
 * {@code hasCorrectTool} is
 * {@code !requiresCorrectToolForDrops || isCorrectToolForDrops} — it
 * controls the 30/100 divisor and whether drops spawn. A low-tier tool
 * on a high-tier block (wooden pickaxe on obsidian) is fast but not
 * correct: toolSpeed > 1.0 but hasCorrectTool = false, so the divisor
 * stays 100 and the block breaks without drops.
 */
class DigPacingTest {

    /** Vanilla gravel: hardness 0.6, drops without a tool. */
    private static final float GRAVEL_DESTROY_SPEED = 0.6f;

    /** Vanilla stone: hardness 1.5, requires a pickaxe to drop. */
    private static final float STONE_DESTROY_SPEED = 1.5f;

    /** Iron pickaxe tier speed (vanilla Tiers.IRON.getSpeed()). */
    private static final float IRON_PICKAXE_SPEED = 6.0f;

    /** Wooden pickaxe tier speed (vanilla Tiers.WOOD.getSpeed()). */
    private static final float WOODEN_PICKAXE_SPEED = 2.0f;

    @Test
    void gravelCostsEighteenBareHandTicks() {
        float perTick = DigPacing.perTickProgress(GRAVEL_DESTROY_SPEED, DigPacing.BARE_HAND_DIG_SPEED, true, true);
        float before = DigPacing.cumulativeProgress(perTick, 17);
        float at = DigPacing.cumulativeProgress(perTick, 18);
        assertTrue(before < 1.0f, "tick 17 must not complete: " + before);
        assertTrue(at >= 1.0f, "tick 18 must complete the break: " + at);
    }

    @Test
    void stoneWithoutTheToolCostsOneHundredFiftyTicks() {
        float perTick = DigPacing.perTickProgress(STONE_DESTROY_SPEED, DigPacing.BARE_HAND_DIG_SPEED, false, true);
        assertTrue(DigPacing.cumulativeProgress(perTick, 149) < 1.0f);
        assertTrue(
                DigPacing.cumulativeProgress(perTick, 150) >= 1.0f,
                "the five-times tool penalty is vanilla, not balance");
    }

    @Test
    void ironPickaxeDigsStoneInAboutEightTicks() {
        // toolSpeed 6.0, correct tool → divisor 30:
        // perTick = 6.0 / 1.5 / 30 = 0.1333; tick 8 = 1.067.
        float perTick = DigPacing.perTickProgress(STONE_DESTROY_SPEED, IRON_PICKAXE_SPEED, true, true);
        assertTrue(
                DigPacing.cumulativeProgress(perTick, 7) < 1.0f,
                "tick 7 must not complete with iron pickaxe: " + DigPacing.cumulativeProgress(perTick, 7));
        assertTrue(
                DigPacing.cumulativeProgress(perTick, 8) >= 1.0f,
                "tick 8 must complete with iron pickaxe: " + DigPacing.cumulativeProgress(perTick, 8));
    }

    @Test
    void woodenPickaxeDigsStoneSlowerThanIron() {
        // toolSpeed 2.0, correct tool → divisor 30:
        // perTick = 2.0 / 1.5 / 30 = 0.0444; tick 23 = 1.022.
        float perTick = DigPacing.perTickProgress(STONE_DESTROY_SPEED, WOODEN_PICKAXE_SPEED, true, true);
        assertTrue(DigPacing.cumulativeProgress(perTick, 22) < 1.0f);
        assertTrue(DigPacing.cumulativeProgress(perTick, 23) >= 1.0f);
        // Wooden is strictly slower than iron on the same block.
        float ironPerTick = DigPacing.perTickProgress(STONE_DESTROY_SPEED, IRON_PICKAXE_SPEED, true, true);
        assertTrue(ironPerTick > perTick, "iron pickaxe must dig faster than wooden");
    }

    @Test
    void correctToolFlagControlsTheDivisor() {
        // Same tool speed, same block: hasCorrectTool=true → divisor 30,
        // hasCorrectTool=false → divisor 100. The false case is a
        // low-tier tool on a high-tier block (e.g. wooden pickaxe on
        // obsidian): fast but no drops.
        float correct = DigPacing.perTickProgress(STONE_DESTROY_SPEED, IRON_PICKAXE_SPEED, true, true);
        float incorrect = DigPacing.perTickProgress(STONE_DESTROY_SPEED, IRON_PICKAXE_SPEED, false, true);
        assertEquals(
                100f / 30f,
                correct / incorrect,
                1e-4f,
                "correct-tool divisor is 30, incorrect is 100 — " + "the ratio must be 10/3");
    }

    @Test
    void zeroHardnessPopsOnTheFirstTick() {
        float perTick = DigPacing.perTickProgress(0f, DigPacing.BARE_HAND_DIG_SPEED, true, true);
        assertTrue(Float.isInfinite(perTick), "digSpeed / 0 is vanilla's insta-mine, and so is ours");
        assertTrue(DigPacing.cumulativeProgress(perTick, 1) >= 1.0f);
    }

    @Test
    void unbreakableNeverAccumulates() {
        assertEquals(
                0f,
                DigPacing.perTickProgress(-1f, DigPacing.BARE_HAND_DIG_SPEED, true, true),
                0f,
                "negative destroy speed means no progress, ever");
    }

    @Test
    void airborneDiggingIsFiveTimesSlower() {
        float grounded = DigPacing.perTickProgress(GRAVEL_DESTROY_SPEED, DigPacing.BARE_HAND_DIG_SPEED, true, true);
        float airborne = DigPacing.perTickProgress(GRAVEL_DESTROY_SPEED, DigPacing.BARE_HAND_DIG_SPEED, true, false);
        assertEquals(0.2f, airborne / grounded, 1e-6f, "Player.getDigSpeed divides mid-air speed by 5");
    }

    @Test
    void crackStagesSweepZeroThroughNineAndNeverReachTen() {
        for (int stage = 0; stage <= 9; stage++) {
            // Mid-stage progress: the vanilla broadcast is
            // (int)(f * 10), which lands exactly on stage only at
            // its left edge.
            float f = stage / 10f;
            int observed = DigPacing.stageOf(f + 1e-6f);
            assertTrue(observed >= stage, "stage " + stage + " must be reachable, got " + observed);
        }
        assertEquals(9, DigPacing.stageOf(0.999f), "a completed dig is a broken block, not crack 10");
        assertEquals(9, DigPacing.stageOf(Float.MAX_VALUE));
    }

    /**
     * Channel pairing: the Dig intent rides INTERACT and nothing
     * else - a mismatched claim must fail construction, where the
     * sealed vocabulary puts that failure.
     */
    @Test
    void digIntentPairsOnlyWithTheInteractChannel() {
        CellPos target = new CellPos(0, 64, 0);
        new Claim(Channel.INTERACT, 10, "test", new Intent.Dig(target));
        assertThrows(IllegalArgumentException.class, () -> new Claim(Channel.USE, 10, "test", new Intent.Dig(target)));
        assertThrows(
                IllegalArgumentException.class, () -> new Claim(Channel.INTERACT, 10, "test", new Intent.Use(true)));
        assertThrows(IllegalArgumentException.class, () -> new Intent.Dig(null));
    }
}
