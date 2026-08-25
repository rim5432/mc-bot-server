package com.mcbot.mcbotserver.core.reflex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parser for the data-driven reflex rule table - the JSON shape of
 * the seam ADR-0003 promises is rewritable. Pure Java, offline
 * testable; the adapter only feeds it bytes and applies the result.
 *
 * <p>Format (one file, datapack path {@code
 * data/mcbotserver/reflex_rules.json}):
 *
 * <pre>{@code
 * {"rules": [
 *   {"type": "FREEZE_ON_LOW_HEALTH",
 *    "threshold": 10.0, "priority": 100}
 * ]}
 * }</pre>
 *
 * <p>Unknown rule types and out-of-range parameters are hard errors:
 * a silently ignored typo would leave a bot believing it has a
 * safety reflex it does not have.
 */
public final class ReflexRuleJson {

    private ReflexRuleJson() {
    }

    /**
     * Parse one rule-table document into configured rules.
     *
     * @param json the document text; never null, must be a JSON object
     * @return the parsed rules in document order; never null; may be
     *         empty only when the array itself is empty
     * @throws IllegalArgumentException on malformed documents, unknown
     *                                  rule types, or invalid
     *                                  parameter values - with the
     *                                  offending detail in the message
     */
    public static List<ReflexRule> parse(String json) {
        Objects.requireNonNull(json, "json");
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "rule table is not a JSON object", e);
        }
        if (!root.has("rules") || !root.get("rules").isJsonArray()) {
            throw new IllegalArgumentException(
                "rule table needs a \"rules\" array");
        }
        var out = new ArrayList<ReflexRule>();
        for (var element : root.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                    "every rule must be an object");
            }
            out.add(parseRule(element.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    /**
     * Serialize a table back to text - round-trip support so tests and
     * future tooling can generate tables programmatically.
     *
     * @param rules the rules to write; never null
     * @return the document text; never null
     */
    public static String write(List<ReflexRule> rules) {
        Objects.requireNonNull(rules, "rules");
        var root = new JsonObject();
        var array = new com.google.gson.JsonArray();
        for (ReflexRule rule : rules) {
            var entry = new JsonObject();
            entry.addProperty("type", rule.name());
            if (rule instanceof FreezeOnLowHealthRule freeze) {
                entry.addProperty("threshold", freeze.threshold());
                entry.addProperty("priority", freeze.priority());
            } else if (rule instanceof SurfaceOnLowAirRule surface) {
                entry.addProperty("trigger", surface.trigger());
                entry.addProperty("release", surface.release());
                entry.addProperty("priority", surface.priority());
            } else if (rule instanceof EngageOnHostileProximityRule
                    engage) {
                entry.addProperty("trigger", engage.trigger());
                entry.addProperty("release", engage.release());
                entry.addProperty("priority", engage.priority());
            } else if (rule instanceof AscendInLethalFluidRule lava) {
                entry.addProperty("priority", lava.priority());
            } else if (rule instanceof DigOnSuffocationRule wall) {
                entry.addProperty("priority", wall.priority());
            } else {
                throw new IllegalArgumentException(
                    "no JSON form for rule type: "
                        + rule.getClass().getSimpleName());
            }
            array.add(entry);
        }
        root.add("rules", array);
        return root.toString();
    }

    private static ReflexRule parseRule(JsonObject rule) {
        if (!rule.has("type")) {
            throw new IllegalArgumentException("rule needs a \"type\"");
        }
        String type = rule.get("type").getAsString();
        if ("FREEZE_ON_LOW_HEALTH".equals(type)) {
            return freezeRule(rule);
        }
        if ("SURFACE_ON_LOW_AIR".equals(type)) {
            return surfaceRule(rule);
        }
        if ("ENGAGE_ON_HOSTILE_PROXIMITY".equals(type)) {
            return engageRule(rule);
        }
        if ("ASCEND_IN_LETHAL_FLUID".equals(type)) {
            return lavaRule(rule);
        }
        if ("DIG_ON_SUFFOCATION".equals(type)) {
            return suffocationRule(rule);
        }
        throw new IllegalArgumentException(
            "unknown rule type: " + type);
    }

    private static ReflexRule freezeRule(JsonObject rule) {
        float threshold = rule.has("threshold")
            ? rule.get("threshold").getAsFloat()
            : FreezeOnLowHealthRule.FREEZE_THRESHOLD;
        int priority = rule.has("priority")
            ? rule.get("priority").getAsInt()
            : FreezeOnLowHealthRule.FREEZE_PRIORITY;
        try {
            return new FreezeOnLowHealthRule(threshold, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "FREEZE_ON_LOW_HEALTH: " + e.getMessage(), e);
        }
    }

    private static ReflexRule surfaceRule(JsonObject rule) {
        int trigger = rule.has("trigger")
            ? rule.get("trigger").getAsInt()
            : SurfaceOnLowAirRule.TRIGGER_AIR;
        int release = rule.has("release")
            ? rule.get("release").getAsInt()
            : SurfaceOnLowAirRule.RELEASE_AIR;
        int priority = rule.has("priority")
            ? rule.get("priority").getAsInt()
            : SurfaceOnLowAirRule.SURFACE_PRIORITY;
        try {
            return new SurfaceOnLowAirRule(trigger, release, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "SURFACE_ON_LOW_AIR: " + e.getMessage(), e);
        }
    }

    private static ReflexRule engageRule(JsonObject rule) {
        double trigger = rule.has("trigger")
            ? rule.get("trigger").getAsDouble()
            : EngageOnHostileProximityRule.TRIGGER_DISTANCE;
        double release = rule.has("release")
            ? rule.get("release").getAsDouble()
            : EngageOnHostileProximityRule.RELEASE_DISTANCE;
        int priority = rule.has("priority")
            ? rule.get("priority").getAsInt()
            : EngageOnHostileProximityRule.ENGAGE_PRIORITY;
        try {
            return new EngageOnHostileProximityRule(trigger, release,
                priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "ENGAGE_ON_HOSTILE_PROXIMITY: " + e.getMessage(), e);
        }
    }

    /**
     * Boolean-condition rules carry only their priority - trigger and
     * release are code-level constants (the inverted 0/1 signal's
     * thresholds are not tunable data), so the JSON form stays
     * one field wide. DIG_ON_SUFFOCATION is the renamed successor of
     * the FREEZE_ON_SUFFOCATION stopgap (issue 0009): the old type
     * string is intentionally a hard parse error now - a datapack
     * still naming it must be updated, never silently ignored.
     */
    private static ReflexRule lavaRule(JsonObject rule) {
        int priority = rule.has("priority")
            ? rule.get("priority").getAsInt()
            : AscendInLethalFluidRule.LAVA_PRIORITY;
        try {
            return new AscendInLethalFluidRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "ASCEND_IN_LETHAL_FLUID: " + e.getMessage(), e);
        }
    }

    private static ReflexRule suffocationRule(JsonObject rule) {
        int priority = rule.has("priority")
            ? rule.get("priority").getAsInt()
            : DigOnSuffocationRule.SUFFOCATION_PRIORITY;
        try {
            return new DigOnSuffocationRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "DIG_ON_SUFFOCATION: " + e.getMessage(), e);
        }
    }
}
