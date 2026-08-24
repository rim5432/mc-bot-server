package com.mcbot.mcbotserver.reflexlayer;

import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Anti-oscillation gate: when a signal jitters around the rule
 * trigger threshold, the layer state machine (double threshold +
 * hold window) absorbs the jitter and the rule fires at most once
 * per threat.
 */
class ReflexHysteresisGateTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(0, 64, 0);

    private static SurvivalReflexLayer layerWith(float[] health) {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());
        return layer;
    }

    private static SurvivalReflexLayer.ReflexDecision tick(
            SurvivalReflexLayer layer, long t, float health) {
        return layer.tick(WORLD, t, 0L, 0L, POS, health);
    }

    @Test
    void jitterInsideDeadbandIsAbsorbedByThresholdGap() {
        float[] health = {9f};
        SurvivalReflexLayer layer = layerWith(health);
        int transitions = 0;
        boolean prevFiring = false;
        for (int i = 0; i < 100; i++) {
            health[0] = (i % 2 == 0) ? 9f : 11f;
            var d = tick(layer, i, health[0]);
            boolean firing = d != null;
            if (firing != prevFiring) {
                transitions++;
            }
            prevFiring = firing;
        }
        assertEquals(1, transitions,
            "jitter inside the deadband must collapse to one fire");
    }

    @Test
    void releaseRequiresHoldWindowToElapse() {
        float[] health = {4f};
        SurvivalReflexLayer layer = layerWith(health);
        health[0] = 4f;
        assertNotNull(tick(layer, 1L, health[0]), "low health must fire");
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            health[0] = 20f;
            assertNotNull(tick(layer, 2L + i, health[0]),
                "hold must keep the rule firing for FREEZE_HOLD_TICKS");
        }
        health[0] = 20f;
        assertNull(tick(layer,
            2L + FreezeOnLowHealthRule.FREEZE_HOLD_TICKS, health[0]),
            "after the hold the rule must release");
    }

    @Test
    void nonHystereticRuleIsUnaffected() {
        ReflexRule always = new ReflexRule() {
            @Override
            public int computePriority(ThreatBlackboard board) {
                return 5;
            }

            @Override
            public String name() {
                return "ALWAYS";
            }
        };
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = 20f);
        layer.addRule(always);

        for (int i = 0; i < 5; i++) {
            var d = layer.tick(WORLD, i, 0L, 0L, POS, 20f);
            assertNotNull(d);
            assertEquals("ALWAYS", d.ruleName());
        }
    }

    @Test
    void reloadResetsHysteresisStateForSameName() {
        float[] health = {4f};
        SurvivalReflexLayer layer = layerWith(health);
        health[0] = 4f;
        assertNotNull(tick(layer, 1L, health[0]));

        layer.replaceRules(List.of(new FreezeOnLowHealthRule(1f, 100)));
        health[0] = 4f;
        assertNull(tick(layer, 2L, health[0]),
            "reload must reset hysteresis state so the new rule starts clean");
    }

    @Test
    void freezeRuleExposesHysteresisContract() {
        FreezeOnLowHealthRule r = new FreezeOnLowHealthRule();
        assertEquals(FreezeOnLowHealthRule.FREEZE_THRESHOLD,
            r.triggerThreshold());
        assertEquals(FreezeOnLowHealthRule.FREEZE_RELEASE,
            r.releaseThreshold());
        assertEquals(FreezeOnLowHealthRule.FREEZE_HOLD_TICKS,
            r.minHoldTicks());
        ThreatBlackboard board = new ThreatBlackboard();
        board.botHealth = 12f;
        assertEquals(12f, r.signalValue(board));
        assert r.triggerThreshold() < r.releaseThreshold()
            : "trigger must be strictly below release";
    }

    /**
     * Activation at exactly the trigger: the state machine's edge
     * must agree with computePriority's documented "at or below" -
     * a strict < would shift the effective trigger one tick late
     * (air 80 firing at 79 instead).
     */
    @Test
    void signalExactlyAtTriggerActivates() {
        float[] health = {FreezeOnLowHealthRule.FREEZE_THRESHOLD};
        SurvivalReflexLayer layer = layerWith(health);
        var d = tick(layer, 1L, health[0]);
        assertNotNull(d, "the boundary value itself must fire");
    }

    @Test
    void heldPhaseReportsLastPositivePriority() {
        float[] health = {4f};
        SurvivalReflexLayer layer = layerWith(health);
        health[0] = 4f;
        var first = tick(layer, 1L, health[0]);
        assertNotNull(first);
        assertEquals(FreezeOnLowHealthRule.FREEZE_PRIORITY, first.priority());

        health[0] = 20f;
        var held = tick(layer, 2L, health[0]);
        assertNotNull(held, "held tick still reports firing");
        assertEquals(FreezeOnLowHealthRule.FREEZE_PRIORITY, held.priority(),
            "held phase reuses the last positive priority");
    }
}
