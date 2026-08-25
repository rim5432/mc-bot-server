package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.reflex.ClimbOutOfPowderSnowRule;
import com.mcbot.mcbotserver.core.reflex.EngageOnHostileProximityRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Powder-snow freeze-climb gates: the freezeTicks signal, the trigger
 * threshold, the ASCEND action kind, and the arbitration against
 * ENGAGE (a freezing body climbs instead of continuing a fight) and
 * FREEZE (a low-health freezing body parks for harness triage).
 *
 * <p>Contract: see ADR-0003 section 2 and issue 0008 F3. Numeric
 * grounding (decompiled 1.20.1): freeze accumulates +1/tick in powder
 * snow, capped at 140; fully frozen deals 1.0 per 40 ticks; thaw is
 * -2/tick out of snow. Powder snow is climbable
 * (Entity.isStateClimbable), so a held jump climbs the body out.
 */
class ClimbOutOfPowderSnowRuleTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);

    private static ThreatBlackboard boardAt(int freezeTicks) {
        var board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        board.freezeTicks = freezeTicks;
        return board;
    }

    @Test
    void firesAtOrAboveTriggerAndStaysSilentBelow() {
        ClimbOutOfPowderSnowRule rule = new ClimbOutOfPowderSnowRule();
        assertEquals(ClimbOutOfPowderSnowRule.CLIMB_PRIORITY,
            rule.computePriority(boardAt(100)), "at trigger fires");
        assertEquals(ClimbOutOfPowderSnowRule.CLIMB_PRIORITY,
            rule.computePriority(boardAt(140)), "fully frozen fires");
        assertEquals(-1, rule.computePriority(boardAt(99)),
            "below trigger stays silent");
        assertEquals(-1, rule.computePriority(boardAt(0)),
            "no freeze stays silent");
    }

    @Test
    void actionIsAscend() {
        assertEquals(ReflexAction.ASCEND,
            new ClimbOutOfPowderSnowRule().action());
    }

    @Test
    void unSensedFreezeDefaultsToZeroAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L, POS, 20f);
        assertEquals(0, board.freezeTicks,
            "beginTick resets freezeTicks to 0");
        assertEquals(-1, new ClimbOutOfPowderSnowRule()
            .computePriority(board),
            "a rig whose sensor never stamps freeze must not climb-flap");
    }

    @Test
    void badThresholdsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new ClimbOutOfPowderSnowRule(0, 95),
            "trigger must be >= 1");
        assertThrows(IllegalArgumentException.class,
            () -> new ClimbOutOfPowderSnowRule(140, 95),
            "trigger must be < 140");
        assertThrows(IllegalArgumentException.class,
            () -> new ClimbOutOfPowderSnowRule(100, 0),
            "priority must be positive");
    }

    /**
     * The load-bearing arbitration: freezeTicks 120 + a hostile at
     * melee range must pick climbing, not engaging - a freezing bot
     * that stops to fight will take damage it could have avoided by
     * stepping clear. CLIMB (95) outranks ENGAGE (90).
     */
    @Test
    void freezeClimbOutranksEngageWhenBothFire() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> {
                board.freezeTicks = 120;
                board.nearestThreat = new com.mcbot.mcbotserver
                    .api.world.EntitySnapshot("z-1", "minecraft:zombie",
                    new CellPos(1, 64, 0), 20f, 20f);
                board.nearestThreatDistance = 1.0;
            });
        layer.addRule(new EngageOnHostileProximityRule());
        layer.addRule(new ClimbOutOfPowderSnowRule());
        var decision = layer.tick(WORLD, 1L, 0L, 0L, POS, 20f);
        assertNotNull(decision);
        assertEquals("CLIMB_OUT_OF_POWDER_SNOW", decision.ruleName());
        assertEquals(ReflexAction.ASCEND, decision.action());
    }

    /**
     * Low-health freeze parks instead of climbs: FREEZE (100)
     * outranks CLIMB (95) because a 2-HP bot that steps out of
     * snow may still die from the next hit, and the 47-second
     * freeze budget gives the harness ample response time.
     */
    @Test
    void lowHealthFreezeOutranksClimbWhenBothFire() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.freezeTicks = 120);
        layer.addRule(new FreezeOnLowHealthRule());
        layer.addRule(new ClimbOutOfPowderSnowRule());
        var decision = layer.tick(WORLD, 1L, 0L, 0L, POS, 4f);
        assertNotNull(decision);
        assertEquals("FREEZE_ON_LOW_HEALTH", decision.ruleName());
    }

    /**
     * Healthy freezing bot climbs: above the freeze threshold,
     * CLIMB wins because stepping clear is strictly better than
     * parking.
     */
    @Test
    void healthyFreezeClimbsInsteadOfParking() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.freezeTicks = 120);
        layer.addRule(new FreezeOnLowHealthRule());
        layer.addRule(new ClimbOutOfPowderSnowRule());
        var decision = layer.tick(WORLD, 1L, 0L, 0L, POS, 20f);
        assertNotNull(decision);
        assertEquals("CLIMB_OUT_OF_POWDER_SNOW", decision.ruleName());
    }

    /**
     * Thaw releases the climb: once freezeTicks drops below the
     * trigger, the rule goes silent and the mission resumes.
     * No hysteresis deadband because freezeTicks changes at most
     * 2/tick (thaw rate), so boundary flapping is sub-perceptible.
     */
    @Test
    void thawBelowTriggerReleasesClimb() {
        int[] freeze = {120};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.freezeTicks = freeze[0]);
        layer.addRule(new ClimbOutOfPowderSnowRule());
        assertNotNull(layer.tick(WORLD, 1L, 0L, 0L, POS, 20f),
            "freeze above trigger fires");
        freeze[0] = 50;
        assertNull(layer.tick(WORLD, 2L, 0L, 0L, POS, 20f),
            "freeze below trigger releases immediately (no hysteresis)");
    }
}
