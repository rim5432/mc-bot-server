package com.mcbot.mcbotserver.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.reflex.AscendInLethalFluidRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lava-reflex gates: the inLethalFluid signal, the ASCEND action
 * kind, priority arbitration against SURFACE and FREEZE (lava is
 * 4 HP/tick — the fastest killer — and must outrank everything),
 * and the hold window that clears the lava edge after exit.
 *
 * <p>Contract: see ADR-0003 section 2 and issue 0008 F2. The
 * board.inLethalFluid field was dead since ADR-0003 (zero sensor
 * writers); this rule + the vitals sensing pass close the
 * live-pipeline lava blind spot. Numeric grounding: lava deals
 * 4.0F per tick with no interval (Entity.lavaHurt, decompiled
 * 1.20.1); a full-health body dies in ~5 ticks.
 */
class AscendInLethalFluidRuleTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);

    private static ThreatBlackboard boardAt(boolean inLava) {
        var board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        board.inLethalFluid = inLava;
        return board;
    }

    @Test
    void firesInLavaAndSilentOnDryLand() {
        AscendInLethalFluidRule rule = new AscendInLethalFluidRule();
        assertEquals(AscendInLethalFluidRule.LAVA_PRIORITY,
            rule.computePriority(boardAt(true)), "in lava fires");
        assertEquals(-1, rule.computePriority(boardAt(false)),
            "dry land stays silent");
    }

    @Test
    void actionIsAscendNotFreeze() {
        assertEquals(ReflexAction.ASCEND,
            new AscendInLethalFluidRule().action());
    }

    @Test
    void unSensedLavaDefaultsToFalseAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        assertEquals(false, board.inLethalFluid,
            "beginTick resets inLethalFluid to false");
        assertEquals(-1, new AscendInLethalFluidRule()
                .computePriority(board),
            "a rig whose sensor never stamps lava must not flap");
    }

    @Test
    void badPriorityIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new AscendInLethalFluidRule(0));
    }

    /**
     * The load-bearing arbitration: lava + low air + low health must
     * pick lava ascent — 4 HP/tick does not negotiate, and surfacing
     * from lava is the same physical action as surfacing from water
     * (held jump -> jumpInFluid).
     */
    @Test
    void lavaOutranksSurfaceAndFreezeWhenAllFire() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> {
                board.inLethalFluid = true;
                board.airSupply = 50;
            });
        layer.addRule(new FreezeOnLowHealthRule());
        layer.addRule(new SurfaceOnLowAirRule());
        layer.addRule(new AscendInLethalFluidRule());
        var decision = layer.tick(WORLD, 1L, 0L, 0L, POS, 4f);
        assertNotNull(decision);
        assertEquals("ASCEND_IN_LETHAL_FLUID", decision.ruleName());
        assertEquals(ReflexAction.ASCEND, decision.action());
    }

    /**
     * Edge-clear hold: after the body leaves lava the ascend stays
     * for LAVA_HOLD_TICKS to clear the shore boundary, then releases.
     * A body bobbing at the lava-air interface can re-enter on the
     * next tick; the hold absorbs that.
     */
    @Test
    void holdSurvivesLavaExitThenReleases() {
        boolean[] inLava = {true};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.inLethalFluid = inLava[0]);
        layer.addRule(new AscendInLethalFluidRule());
        assertNotNull(layer.tick(WORLD, 1L, 0L, 0L, POS, 20f),
            "in lava fires");
        // Exit lava: still held through the hold window.
        inLava[0] = false;
        for (int i = 0;
             i < AscendInLethalFluidRule.LAVA_HOLD_TICKS; i++) {
            assertNotNull(layer.tick(WORLD, 2L + i, 0L, 0L, POS, 20f),
                "hold tick " + i + " keeps ascending to clear edge");
        }
        // After the hold window with stable dry land: releases.
        assertNull(layer.tick(WORLD,
            2L + AscendInLethalFluidRule.LAVA_HOLD_TICKS,
            0L, 0L, POS, 20f),
            "hold expired + stable dry land releases");
    }
}
