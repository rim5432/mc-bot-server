package com.mcbot.mcbotserver.core.actor;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.CellPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dig pacing gates (issue 0009): the bare-hand destroy-progress
 * arithmetic mirrors vanilla's numbers so block-break timing matches
 * a player holding left-click with an empty hand, and the Dig intent
 * pairs only with the INTERACT channel.
 *
 * <p>Contract: see boundaries.md decision 25. Reference points from
 * the decompiled 1.20.1 tree: gravel (hardness 0.6, drops bare)
 * costs 18 hand ticks; stone (1.5, tool required) costs 150;
 * zero-hardness blocks pop instantly (digSpeed/0 = +Infinity);
 * unbreakable blocks never accumulate.
 */
class DigPacingTest {

    /** Vanilla gravel: hardness 0.6, drops without a tool. */
    private static final float GRAVEL_DESTROY_SPEED = 0.6f;

    /** Vanilla stone: hardness 1.5, requires a pickaxe to drop. */
    private static final float STONE_DESTROY_SPEED = 1.5f;

    @Test
    void gravelCostsEighteenBareHandTicks() {
        float perTick = DigPacing.perTickProgress(
            GRAVEL_DESTROY_SPEED, false, true);
        float before = DigPacing.cumulativeProgress(perTick, 17);
        float at = DigPacing.cumulativeProgress(perTick, 18);
        assertTrue(before < 1.0f,
            "tick 17 must not complete: " + before);
        assertTrue(at >= 1.0f,
            "tick 18 must complete the break: " + at);
    }

    @Test
    void stoneWithoutTheToolCostsOneHundredFiftyTicks() {
        float perTick = DigPacing.perTickProgress(
            STONE_DESTROY_SPEED, true, true);
        assertTrue(DigPacing.cumulativeProgress(perTick, 149) < 1.0f);
        assertTrue(DigPacing.cumulativeProgress(perTick, 150) >= 1.0f,
            "the five-times tool penalty is vanilla, not balance");
    }

    @Test
    void zeroHardnessPopsOnTheFirstTick() {
        float perTick = DigPacing.perTickProgress(0f, false, true);
        assertTrue(Float.isInfinite(perTick),
            "digSpeed / 0 is vanilla's insta-mine, and so is ours");
        assertTrue(DigPacing.cumulativeProgress(perTick, 1) >= 1.0f);
    }

    @Test
    void unbreakableNeverAccumulates() {
        assertEquals(0f, DigPacing.perTickProgress(-1f, false, true),
            0f, "negative destroy speed means no progress, ever");
    }

    @Test
    void airborneDiggingIsFiveTimesSlower() {
        float grounded = DigPacing.perTickProgress(
            GRAVEL_DESTROY_SPEED, false, true);
        float airborne = DigPacing.perTickProgress(
            GRAVEL_DESTROY_SPEED, false, false);
        assertEquals(0.2f, airborne / grounded, 1e-6f,
            "Player.getDigSpeed divides mid-air speed by 5");
    }

    @Test
    void crackStagesSweepZeroThroughNineAndNeverReachTen() {
        for (int stage = 0; stage <= 9; stage++) {
            // Mid-stage progress: the vanilla broadcast is
            // (int)(f * 10), which lands exactly on stage only at
            // its left edge.
            float f = stage / 10f;
            int observed = DigPacing.stageOf(f + 1e-6f);
            assertTrue(observed >= stage,
                "stage " + stage + " must be reachable, got "
                    + observed);
        }
        assertEquals(9, DigPacing.stageOf(0.999f),
            "a completed dig is a broken block, not crack 10");
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
        new Claim(Channel.INTERACT, 10, "test",
            new Intent.Dig(target));
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.USE, 10, "test",
                new Intent.Dig(target)));
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.INTERACT, 10, "test",
                new Intent.Use(true)));
        assertThrows(IllegalArgumentException.class,
            () -> new Intent.Dig(null));
    }
}
