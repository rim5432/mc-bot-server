package com.mcbot.mcbotserver.core.reflex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reader half of the reflex rule table codec: parses the datapack
 * JSON document into configured rule instances. Pure Java, offline
 * testable; {@link ReflexRuleJson#write} is the symmetric writer.
 *
 * <p>Unknown rule types and out-of-range parameters are hard errors:
 * a silently ignored typo would leave a bot believing it has a
 * safety reflex it does not have.
 */
// Ruling (2026-08-27 third-seat round): this class is a flat type->
// factory registry - one leaf method per rule kind, zero shared
// fields, so GodClass's cohesion axis reads 0% by construction and
// cannot see registry shape. Suppressed with the ruling; the WMC
// growth lever is the FACTORIES map, not method sprawl.
@SuppressWarnings("PMD.GodClass")
final class ReflexRuleJsonReader {

    private ReflexRuleJsonReader() {}

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
    static List<ReflexRule> parse(String json) {
        Objects.requireNonNull(json, "json");
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rule table is not a JSON object", e);
        }
        if (!root.has("rules") || !root.get("rules").isJsonArray()) {
            throw new IllegalArgumentException("rule table needs a \"rules\" array");
        }
        var out = new ArrayList<ReflexRule>();
        for (var element : root.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("every rule must be an object");
            }
            out.add(parseRule(element.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    /** Type tag -> factory; adding a rule kind is one put, not another branch. */
    private static final java.util.Map<String, java.util.function.Function<JsonObject, ReflexRule>> FACTORIES =
            java.util.Map.of(
                    "FREEZE_ON_LOW_HEALTH", ReflexRuleJsonReader::freezeRule,
                    "SURFACE_ON_LOW_AIR", ReflexRuleJsonReader::surfaceRule,
                    "ENGAGE_ON_HOSTILE_PROXIMITY", ReflexRuleJsonReader::engageRule,
                    "ESCAPE_ON_LAVA", ReflexRuleJsonReader::escapeLavaRule,
                    "EXTINGUISH_FIRE", ReflexRuleJsonReader::extinguishFireRule,
                    "DIG_ON_SUFFOCATION", ReflexRuleJsonReader::suffocationRule,
                    "CLIMB_OUT_OF_POWDER_SNOW", ReflexRuleJsonReader::climbPowderSnowRule,
                    "EAT_WHEN_HUNGRY", ReflexRuleJsonReader::eatRule,
                    "ACQUIRE_FOOD_WHEN_HUNGRY", ReflexRuleJsonReader::acquireRule,
                    "WATER_BUCKET_ON_FALL", ReflexRuleJsonReader::waterBucketOnFallRule);

    private static ReflexRule waterBucketOnFallRule(JsonObject rule) {
        int priority = fInt(rule, "priority", WaterBucketOnFallRule.MLG_PRIORITY);
        try {
            return new WaterBucketOnFallRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("WATER_BUCKET_ON_FALL: " + e.getMessage(), e);
        }
    }

    private static ReflexRule parseRule(JsonObject rule) {
        if (!rule.has("type")) {
            throw new IllegalArgumentException("rule needs a \"type\"");
        }
        String type = rule.get("type").getAsString();
        var factory = FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("unknown rule type: " + type);
        }
        return factory.apply(rule);
    }

    // Typed field reads; every "has ? get : default" ternary in rule
    // factories funnels here so the parsers stay straight-line code.
    private static float fFloat(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    private static double fDouble(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    private static int fInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static ReflexRule freezeRule(JsonObject rule) {
        float threshold = fFloat(rule, "threshold", FreezeOnLowHealthRule.FREEZE_THRESHOLD);
        int priority = fInt(rule, "priority", FreezeOnLowHealthRule.FREEZE_PRIORITY);
        try {
            return new FreezeOnLowHealthRule(threshold, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FREEZE_ON_LOW_HEALTH: " + e.getMessage(), e);
        }
    }

    private static ReflexRule surfaceRule(JsonObject rule) {
        int trigger = fInt(rule, "trigger", SurfaceOnLowAirRule.TRIGGER_AIR);
        int release = fInt(rule, "release", SurfaceOnLowAirRule.RELEASE_AIR);
        int priority = fInt(rule, "priority", SurfaceOnLowAirRule.SURFACE_PRIORITY);
        try {
            return new SurfaceOnLowAirRule(trigger, release, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SURFACE_ON_LOW_AIR: " + e.getMessage(), e);
        }
    }

    private static ReflexRule engageRule(JsonObject rule) {
        double trigger = fDouble(rule, "trigger", EngageOnHostileProximityRule.TRIGGER_DISTANCE);
        double release = fDouble(rule, "release", EngageOnHostileProximityRule.RELEASE_DISTANCE);
        int priority = fInt(rule, "priority", EngageOnHostileProximityRule.ENGAGE_PRIORITY);
        try {
            return new EngageOnHostileProximityRule(trigger, release, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ENGAGE_ON_HOSTILE_PROXIMITY: " + e.getMessage(), e);
        }
    }

    /**
     * Boolean-condition ESCAPE rules carry only their priority -
     * trigger and release are code-level constants (the inverted 0/1
     * signal's thresholds are not tunable data), so the JSON form
     * stays one field wide. ESCAPE_ON_LAVA is the renamed successor
     * of ASCEND_IN_LETHAL_FLUID (issue 0008 D2): the old type string
     * is intentionally a hard parse error now - a datapack still
     * naming it must be updated, never silently ignored.
     * EXTINGUISH_FIRE is the D5-revised fire find-water rule (issue
     * 0008 D5): fires only in the burn-to-death band.
     */
    private static ReflexRule escapeLavaRule(JsonObject rule) {
        int priority = fInt(rule, "priority", EscapeLavaRule.LAVA_ESCAPE_PRIORITY);
        try {
            return new EscapeLavaRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ESCAPE_ON_LAVA: " + e.getMessage(), e);
        }
    }

    private static ReflexRule extinguishFireRule(JsonObject rule) {
        int priority = fInt(rule, "priority", ExtinguishFireRule.FIRE_EXTINGUISH_PRIORITY);
        try {
            return new ExtinguishFireRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("EXTINGUISH_FIRE: " + e.getMessage(), e);
        }
    }

    private static ReflexRule suffocationRule(JsonObject rule) {
        int priority = fInt(rule, "priority", DigOnSuffocationRule.SUFFOCATION_PRIORITY);
        try {
            return new DigOnSuffocationRule(priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DIG_ON_SUFFOCATION: " + e.getMessage(), e);
        }
    }

    /**
     * Powder-snow freeze climb carries trigger + priority only -
     * freezeTicks changes at most 2/tick so no hysteresis deadband
     * is needed (same flat-priority shape as EscapeLavaRule).
     */
    private static ReflexRule climbPowderSnowRule(JsonObject rule) {
        int trigger = fInt(rule, "trigger", ClimbOutOfPowderSnowRule.TRIGGER_FREEZE);
        int priority = fInt(rule, "priority", ClimbOutOfPowderSnowRule.CLIMB_PRIORITY);
        try {
            return new ClimbOutOfPowderSnowRule(trigger, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CLIMB_OUT_OF_POWDER_SNOW: " + e.getMessage(), e);
        }
    }
    /**
     * Eat-when-hungry carries trigger + priority only - the firing
     * condition is a pure threshold on the sensed food level with the
     * sensor's food slot as the availability gate (flat priority
     * shape, same as EscapeLavaRule).
     */
    private static ReflexRule eatRule(JsonObject rule) {
        int trigger = fInt(rule, "trigger", EatWhenHungryRule.TRIGGER_FOOD);
        int priority = fInt(rule, "priority", EatWhenHungryRule.EAT_PRIORITY);
        try {
            return new EatWhenHungryRule(trigger, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("EAT_WHEN_HUNGRY: " + e.getMessage(), e);
        }
    }
    /**
     * Acquire-food-when-hungry carries trigger + priority only - it
     * fires exactly when the eat rule cannot (hungry, no sensed
     * food) and hands off to the forage seat (flat priority shape).
     */
    private static ReflexRule acquireRule(JsonObject rule) {
        int trigger = fInt(rule, "trigger", AcquireFoodWhenHungryRule.TRIGGER_FOOD);
        int priority = fInt(rule, "priority", AcquireFoodWhenHungryRule.ACQUIRE_PRIORITY);
        try {
            return new AcquireFoodWhenHungryRule(trigger, priority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ACQUIRE_FOOD_WHEN_HUNGRY: " + e.getMessage(), e);
        }
    }
}
