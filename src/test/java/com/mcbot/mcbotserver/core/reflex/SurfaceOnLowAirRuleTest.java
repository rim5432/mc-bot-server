package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Drowning-reflex gates: the air signal, the trigger/release
 * thresholds, the ASCEND action kind, and the arbitration against
 * the freeze rule (surfacing outranks freezing - a bleeding body
 * that is also drowning must surface first).
 *
 * <p>Contract: see ADR-0003 section 2 and issue 0004 F6(2). Numeric
 * grounding: vanilla air drains 1/tick from 300, regenerates 4/tick.
 */
class SurfaceOnLowAirRuleTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);

    private static ThreatBlackboard boardAt(int air) {
        var board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        board.airSupply = air;
        return board;
    }

    @Test
    void firesAtOrBelowTriggerAndStaysSilentAbove() {
        SurfaceOnLowAirRule rule = new SurfaceOnLowAirRule();
        assertEquals(SurfaceOnLowAirRule.SURFACE_PRIORITY, rule.computePriority(boardAt(80)), "at trigger fires");
        assertEquals(SurfaceOnLowAirRule.SURFACE_PRIORITY, rule.computePriority(boardAt(0)), "no air fires");
        assertEquals(-1, rule.computePriority(boardAt(81)), "above trigger stays silent");
    }

    @Test
    void actionIsAscendNotFreeze() {
        assertEquals(ReflexAction.ASCEND, new SurfaceOnLowAirRule().action());
    }

    @Test
    void unSensedAirDefaultsToFullAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        assertEquals(ThreatBlackboard.MAX_AIR_SUPPLY, board.airSupply, "beginTick resets air to full");
        assertEquals(
                -1,
                new SurfaceOnLowAirRule().computePriority(board),
                "a rig whose sensor never stamps air must not drown-flap");
    }

    @Test
    void badThresholdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SurfaceOnLowAirRule(0, 200, 110));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceOnLowAirRule(300, 300, 110));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceOnLowAirRule(80, 80, 110),
                "release must be strictly above trigger");
        assertThrows(IllegalArgumentException.class, () -> new SurfaceOnLowAirRule(80, 301, 110));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceOnLowAirRule(80, 200, 0));
    }

    /**
     * The load-bearing arbitration: air 50 + health 4 must pick
     * surfacing, not freezing - the freeze hold under water is how
     * the 2026-08-24 body died.
     */
    @Test
    void drowningOutranksFreezeWhenBothFire() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.airSupply = 50);
        layer.addRule(new FreezeOnLowHealthRule());
        layer.addRule(new SurfaceOnLowAirRule());
        var decision = layer.tick(WORLD, 1L, 0L, 0L, POS, 4f);
        assertNotNull(decision);
        assertEquals("SURFACE_ON_LOW_AIR", decision.ruleName());
        assertEquals(ReflexAction.ASCEND, decision.action());
    }

    /**
     * Surface-film tolerance: the ascend holds through the regen
     * band and releases only above RELEASE_AIR after the hold
     * window - bobbing at the surface must not flap the rule.
     */
    @Test
    void holdSurvivesSurfaceBobbingUntilAirRecovers() {
        int[] air = {50};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.airSupply = air[0]);
        layer.addRule(new SurfaceOnLowAirRule());
        assertNotNull(layer.tick(WORLD, 1L, 0L, 0L, POS, 20f));
        // Regen climbs into the deadband: still held.
        air[0] = 150;
        assertNotNull(layer.tick(WORLD, 2L, 0L, 0L, POS, 20f), "air between trigger and release keeps the ascend held");
        // Full air for the whole hold window: releases afterwards.
        for (int i = 0; i < SurfaceOnLowAirRule.SURFACE_HOLD_TICKS; i++) {
            air[0] = 300;
            assertNotNull(layer.tick(WORLD, 3L + i, 0L, 0L, POS, 20f));
        }
        air[0] = 300;
        assertNull(
                layer.tick(WORLD, 3L + SurfaceOnLowAirRule.SURFACE_HOLD_TICKS, 0L, 0L, POS, 20f),
                "recovered air releases the ascend");
    }
}
