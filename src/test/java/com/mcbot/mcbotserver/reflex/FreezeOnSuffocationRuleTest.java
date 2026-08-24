package com.mcbot.mcbotserver.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.reflex.AscendInLethalFluidRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Suffocation-reflex gates (issue 0008 F4/D3): fires on inWall with
 * the FREEZE action, defaults safe, sits between SURFACE and LAVA on
 * the triage ladder, and releases promptly once the eye clears the
 * geometry.
 *
 * <p>Contract: 1 HP/tick with no interval (decompiled
 * LivingEntity.baseTick:387) - instant class, and the rescue
 * direction is unknown, so halting is the only strictly-correct
 * reflex response.
 */
class FreezeOnSuffocationRuleTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);

    private static ThreatBlackboard boardAt(boolean inWall) {
        var board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        board.inWall = inWall;
        return board;
    }

    @Test
    void firesInWallAndStaysSilentInOpenAir() {
        FreezeOnSuffocationRule rule = new FreezeOnSuffocationRule();
        assertEquals(FreezeOnSuffocationRule.SUFFOCATION_PRIORITY,
            rule.computePriority(boardAt(true)));
        assertEquals(-1, rule.computePriority(boardAt(false)));
    }

    @Test
    void actionIsFreezeNotMovement() {
        assertEquals(ReflexAction.FREEZE,
            new FreezeOnSuffocationRule().action(),
            "a reflex guessing a rescue direction can push the body "
                + "deeper into the glitch - the only correct verb is "
                + "halt");
    }

    @Test
    void unSensedWallDefaultsToFalseAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        assertEquals(false, board.inWall,
            "beginTick resets inWall to safe");
        assertEquals(-1,
            new FreezeOnSuffocationRule().computePriority(board));
    }

    @Test
    void badPriorityIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FreezeOnSuffocationRule(0));
    }

    /**
     * Triage ladder: suffocation (115) outranks drowning (110) -
     * drowning does not negotiate with geometry - but lava (130)
     * outranks suffocation.
     */
    @Test
    void sitsBetweenSurfaceAndLavaOnTheLadder() {
        boolean[] inWall = {true};
        int[] air = {50};
        boolean[] inLava = {false};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> {
                board.inWall = inWall[0];
                board.airSupply = air[0];
                board.inLethalFluid = inLava[0];
            });
        layer.addRule(new SurfaceOnLowAirRule());
        layer.addRule(new FreezeOnSuffocationRule());
        layer.addRule(new AscendInLethalFluidRule());
        var d = layer.tick(WORLD, 1L, 0L, 0L, POS, 20f);
        assertEquals("FREEZE_ON_SUFFOCATION", d.ruleName());

        inLava[0] = true;
        d = layer.tick(WORLD, 2L, 0L, 0L, POS, 20f);
        assertEquals("ASCEND_IN_LETHAL_FLUID", d.ruleName(),
            "lava does not negotiate with anything");
    }

    /** Eye clears the geometry -> releases after the hold window. */
    @Test
    void holdSurvivesBoundaryFlapThenReleases() {
        boolean[] inWall = {true};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.inWall = inWall[0]);
        layer.addRule(new FreezeOnSuffocationRule());
        assertNotNull(layer.tick(WORLD, 1L, 0L, 0L, POS, 20f));
        for (int i = 0;
                i < FreezeOnSuffocationRule.SUFFOCATION_HOLD_TICKS;
                i++) {
            inWall[0] = false;
            assertNotNull(layer.tick(WORLD, 2L + i, 0L, 0L, POS, 20f),
                "hold keeps the halt through the boundary flap");
        }
        inWall[0] = false;
        assertNull(layer.tick(WORLD,
            2L + FreezeOnSuffocationRule.SUFFOCATION_HOLD_TICKS,
            0L, 0L, POS, 20f), "clear geometry releases the halt");
    }
}
