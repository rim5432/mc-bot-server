package com.mcbot.mcbotserver.core.reflex;

import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import java.util.List;
import java.util.Objects;

/**
 * Codec facade for the data-driven reflex rule table - the JSON shape
 * of the seam ADR-0003 promises is rewritable. Parsing lives in
 * {@link ReflexRuleJsonReader}; this class owns the symmetric writer
 * so tests and future tooling can generate tables programmatically.
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
 */
public final class ReflexRuleJson {

    private ReflexRuleJson() {}

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
        return ReflexRuleJsonReader.parse(json);
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
            array.add(entryFor(rule));
        }
        root.add("rules", array);
        return root.toString();
    }

    /** One document entry: the rule's type tag plus its tunable fields. */
    private static JsonObject entryFor(ReflexRule rule) {
        var entry = new JsonObject();
        entry.addProperty("type", rule.name());
        if (rule instanceof FreezeOnLowHealthRule freeze) {
            entry.addProperty("threshold", freeze.threshold());
            entry.addProperty("priority", freeze.priority());
        } else if (rule instanceof SurfaceOnLowAirRule surface) {
            entry.addProperty("trigger", surface.trigger());
            entry.addProperty("release", surface.release());
            entry.addProperty("priority", surface.priority());
        } else if (rule instanceof EngageOnHostileProximityRule engage) {
            entry.addProperty("trigger", engage.trigger());
            entry.addProperty("release", engage.release());
            entry.addProperty("priority", engage.priority());
        } else if (rule instanceof EscapeLavaRule lava) {
            entry.addProperty("priority", lava.priority());
        } else if (rule instanceof ExtinguishFireRule fire) {
            entry.addProperty("priority", fire.priority());
        } else if (rule instanceof DigOnSuffocationRule wall) {
            entry.addProperty("priority", wall.priority());
        } else if (rule instanceof ClimbOutOfPowderSnowRule snow) {
            entry.addProperty("trigger", snow.trigger());
            entry.addProperty("priority", snow.priority());
        } else {
            throw new IllegalArgumentException(
                    "no JSON form for rule type: " + rule.getClass().getSimpleName());
        }
        return entry;
    }
}
