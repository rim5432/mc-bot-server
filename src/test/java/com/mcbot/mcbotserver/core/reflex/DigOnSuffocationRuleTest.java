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
 * Suffocation-reflex gates (issue 0008 F4/D3, upgraded by issue 0009):
 * fires on inWall with the DIG action carrying the eye block as its
 * target, defaults safe, sits between SURFACE and LAVA on the triage
 * ladder, and releases promptly once the eye clears the geometry.
 *
 * <p>Contract: 1 HP/tick with no interval (decompiled
 * LivingEntity.baseTick:387) - instant class. With dig capability the
 * rescue direction is KNOWN (the eye block itself), so the
 * deterministic self-rescue supersedes the FREEZE stopgap; a board
 * without the position still degrades to a targetless decision the
 * controller holds as a freeze.
 */
class DigOnSuffocationRuleTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);
    private static final CellPos EYE_BLOCK = new CellPos(0, 65, 0);

    private static ThreatBlackboard boardAt(boolean inWall, CellPos eyeBlock) {
        var board = new ThreatBlackboard();
        board.beginTick(20f);
        board.inWall = inWall;
        board.suffocationBlock = eyeBlock;
        return board;
    }

    @Test
    void firesInWallAndStaysSilentInOpenAir() {
        DigOnSuffocationRule rule = new DigOnSuffocationRule();
        assertEquals(DigOnSuffocationRule.SUFFOCATION_PRIORITY, rule.computePriority(boardAt(true, EYE_BLOCK)));
        assertEquals(-1, rule.computePriority(boardAt(false, null)));
    }

    @Test
    void actionIsDigAndTheTargetIsTheEyeBlock() {
        DigOnSuffocationRule rule = new DigOnSuffocationRule();
        assertEquals(
                ReflexAction.DIG,
                rule.action(),
                "the rescue direction is the eye block - a known target "
                        + "makes the dig strictly better than the halt");
        assertEquals(EYE_BLOCK, rule.actionTarget(boardAt(true, EYE_BLOCK)));
    }

    @Test
    void targetlessWallYieldsTargetlessDecision() {
        // inWall stamped without the position: the rule still fires
        // (the vital is real), but carries no target so the
        // controller degrades to the freeze hold instead of digging
        // at a guess.
        assertEquals(null, new DigOnSuffocationRule().actionTarget(boardAt(true, null)));
    }

    @Test
    void unSensedWallDefaultsToFalseAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(20f);
        assertEquals(false, board.inWall, "beginTick resets inWall to safe");
        assertEquals(null, board.suffocationBlock, "beginTick resets the rescue target to none");
        assertEquals(-1, new DigOnSuffocationRule().computePriority(board));
    }

    @Test
    void badPriorityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DigOnSuffocationRule(0));
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
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
            board.inWall = inWall[0];
            board.suffocationBlock = inWall[0] ? EYE_BLOCK : null;
            board.airSupply = air[0];
            board.inLethalFluid = inLava[0];
        });
        layer.addRule(new SurfaceOnLowAirRule());
        layer.addRule(new DigOnSuffocationRule());
        layer.addRule(new EscapeLavaRule());
        var d = layer.tick(WORLD, 20f);
        assertEquals("DIG_ON_SUFFOCATION", d.ruleName());
        assertEquals(ReflexAction.DIG, d.action());
        assertEquals(EYE_BLOCK, d.target(), "the decision carries the dig geometry");

        inLava[0] = true;
        d = layer.tick(WORLD, 20f);
        assertEquals("ESCAPE_ON_LAVA", d.ruleName(), "lava does not negotiate with anything");
    }

    /**
     * Eye clears the geometry (the dig finished) -> releases after
     * the hold window; while held with the eye clear, the target is
     * null - the post-rescue ticks must not keep naming a cell.
     */
    @Test
    void holdSurvivesBoundaryFlapThenReleases() {
        boolean[] inWall = {true};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
            board.inWall = inWall[0];
            board.suffocationBlock = inWall[0] ? EYE_BLOCK : null;
        });
        layer.addRule(new DigOnSuffocationRule());
        assertNotNull(layer.tick(WORLD, 20f));
        for (int i = 0; i < DigOnSuffocationRule.SUFFOCATION_HOLD_TICKS; i++) {
            inWall[0] = false;
            var held = layer.tick(WORLD, 20f);
            assertNotNull(held, "hold keeps the rule through the boundary flap");
            assertEquals(null, held.target(), "a clear eye carries no dig target");
        }
        inWall[0] = false;
        assertNull(layer.tick(WORLD, 20f), "clear geometry releases the rule");
    }
}
