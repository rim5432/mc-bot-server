package com.mcbot.mcbotserver.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.ReflexRuleJson;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-2 rule-table gate: the reflex seam is data-driven - JSON in,
 * configured rules out; unknown types and bad values are hard errors;
 * a whole-table swap is invisible to anything holding the layer.
 *
 * <p>Contract: see ADR-0003 (rewritable table) and workplan S item
 * (rule-table JSON).
 */
class RuleTableGateTest {

    private static final String TABLE = """
        {"rules": [
          {"type": "FREEZE_ON_LOW_HEALTH",
           "threshold": 5.0, "priority": 90}
        ]}
        """;

    @Test
    void jsonTableDrivesThresholdAndPriority() {
        List<ReflexRule> rules = ReflexRuleJson.parse(TABLE);
        assertEquals(1, rules.size());
        var freeze = (FreezeOnLowHealthRule) rules.get(0);

        assertEquals(5.0f, freeze.threshold());
        assertEquals(90, freeze.computePriority(boardAt(5f)),
            "at threshold fires");
        assertEquals(-1, freeze.computePriority(boardAt(5.1f)),
            "above threshold stays silent");
    }

    @Test
    void defaultsApplyWhenFieldsMissing() {
        List<ReflexRule> rules = ReflexRuleJson.parse(
            "{\"rules\": [{\"type\": \"FREEZE_ON_LOW_HEALTH\"}]}");
        var freeze = (FreezeOnLowHealthRule) rules.get(0);
        assertEquals(FreezeOnLowHealthRule.FREEZE_THRESHOLD,
            freeze.threshold());
        assertEquals(FreezeOnLowHealthRule.FREEZE_PRIORITY,
            freeze.priority());
    }

    @Test
    void surfaceRuleParsesThresholdsAndRoundTrips() {
        List<ReflexRule> rules = ReflexRuleJson.parse("{\"rules\": ["
            + "{\"type\": \"SURFACE_ON_LOW_AIR\","
            + " \"trigger\": 60, \"release\": 220, \"priority\": 120}"
            + "]}");
        var surface =
            (com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule)
                rules.get(0);
        assertEquals(60, surface.trigger());
        assertEquals(220, surface.release());
        assertEquals(120, surface.priority());
        var reparsed = (com.mcbot.mcbotserver.core.reflex
                .SurfaceOnLowAirRule)
            ReflexRuleJson.parse(ReflexRuleJson.write(rules)).get(0);
        assertTrue(surface.trigger() == reparsed.trigger()
            && surface.release() == reparsed.release()
            && surface.priority() == reparsed.priority(),
            "write->parse must preserve the surface configuration");
    }

    @Test
    void surfaceDefaultsMatchCodeTable() {
        List<ReflexRule> rules = ReflexRuleJson.parse(
            "{\"rules\": [{\"type\": \"SURFACE_ON_LOW_AIR\"}]}");
        var surface =
            (com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule)
                rules.get(0);
        assertEquals(
            com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule
                .TRIGGER_AIR,
            surface.trigger());
        assertEquals(
            com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule
                .RELEASE_AIR,
            surface.release());
    }

    @Test
    void unknownTypeAndBadValuesAreHardErrors() {
        assertThrows(IllegalArgumentException.class,
            () -> ReflexRuleJson.parse("{\"rules\": ["
                + "{\"type\": \"TELEPORT_HOME\"}]}"),
            "a silently ignored typo would fake a safety net");
        assertThrows(IllegalArgumentException.class,
            () -> ReflexRuleJson.parse("{\"rules\": ["
                + "{\"type\": \"FREEZE_ON_LOW_HEALTH\", "
                + "\"threshold\": 40.0}]}"));
        assertThrows(IllegalArgumentException.class,
            () -> ReflexRuleJson.parse("{\"rules\": ["
                + "{\"type\": \"SURFACE_ON_LOW_AIR\","
                + " \"trigger\": 80, \"release\": 80}]}"),
            "release at trigger is a hard error, not a silent clamp");
        assertThrows(IllegalArgumentException.class,
            () -> ReflexRuleJson.parse("{\"no_rules_here\": []}"));
    }

    @Test
    void wholeTableSwapIsInvisibleToHolders() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = 4f);
        layer.addRule(new FreezeOnLowHealthRule());
        assertEquals("FREEZE_ON_LOW_HEALTH",
            layer.tick(mockWorld(), 1L, 0L, 0L, pos(), 4f).ruleName());

        // Reload installs an impossible threshold: same layer object,
        // different behavior - and nothing else changed.
        layer.replaceRules(List.of(new FreezeOnLowHealthRule(1f, 100)));
        assertEquals(null, layer.tick(mockWorld(), 2L, 0L, 0L, pos(), 4f),
            "health 4 must stay silent under a threshold of 1");

        layer.replaceRules(List.of());
        assertEquals(null, layer.tick(mockWorld(), 3L, 0L, 0L, pos(), 0.1f));
    }

    @Test
    void writeRoundTripsThroughParse() {
        List<ReflexRule> original = ReflexRuleJson.parse(TABLE);
        List<ReflexRule> reparsed =
            ReflexRuleJson.parse(ReflexRuleJson.write(original));
        var a = (FreezeOnLowHealthRule) original.get(0);
        var b = (FreezeOnLowHealthRule) reparsed.get(0);
        assertTrue(a.threshold() == b.threshold()
            && a.priority() == b.priority(),
            "write->parse must preserve configuration");
    }

    private com.mcbot.mcbotserver.api.world.WorldView mockWorld() {
        return new com.mcbot.mcbotserver.core.world.MockWorldView();
    }

    private ThreatBlackboard boardAt(float health) {
        var board = new ThreatBlackboard();
        board.beginTick(0L, 0L, 0L,
            new com.mcbot.mcbotserver.api.types.CellPos(0, 64, 0),
            health);
        return board;
    }

    private com.mcbot.mcbotserver.api.types.CellPos pos() {
        return new com.mcbot.mcbotserver.api.types.CellPos(0, 64, 0);
    }
}
