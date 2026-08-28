package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Offline gate over the reflex triage partial order: reads the
 * shipped datapack table {@code reflex_rules.json} as a file and
 * pins both the rule-type inventory and the strict priority order
 * between survival rungs.
 *
 * <p>Why a gate instead of prose: boundaries.md carried a frozen
 * five-rung ladder sentence that decisions 26 and 27 extended
 * without rewriting - the constitution lied about its own ladder
 * until this order was machine-checked. Priorities are tuning data,
 * not contract text: any re-tuning lands here, reviewed together
 * with the JSON diff, exactly the friction
 * {@code shippedDatapackTableCoversEveryCodeRegisteredRuleType}
 * applies to rule registration.
 *
 * <p>Rule: boundaries.md ledger 24/26/27 (triage ladder); the ladder
 * is prose-free outside this gate and the shipped JSON (the retired
 * capability map's prose ladder drifted to eight rungs while the
 * table grew to ten - this gate is the only reader).
 */
class ReflexPriorityOrderGateTest {

    /** type -> exact shipped priority. Re-tuning edits both sides. */
    private static final Map<String, Integer> EXPECTED = new LinkedHashMap<>();

    private static void expect(String type, int priority) {
        EXPECTED.put(type, priority);
    }

    static {
        // Mapping of ladder rung -> shipped rule type, strict order:
        expect("ESCAPE_ON_LAVA", 130);
        // MLG sits between lava and suffocation: landing damage is
        // deferred but certain, and the placement window is short.
        expect("WATER_BUCKET_ON_FALL", 120);
        expect("DIG_ON_SUFFOCATION", 115);
        expect("SURFACE_ON_LOW_AIR", 110);
        expect("EXTINGUISH_FIRE", 105);
        expect("FREEZE_ON_LOW_HEALTH", 100);
        expect("CLIMB_OUT_OF_POWDER_SNOW", 95);
        expect("ENGAGE_ON_HOSTILE_PROXIMITY", 90);
        // Issue 0010 D6: combat first, hunger is routine - the eat
        // reflex sits below ENGAGE.
        expect("EAT_WHEN_HUNGRY", 85);
        // Acquisition is the eat rule's complement, one rung below.
        expect("ACQUIRE_FOOD_WHEN_HUNGRY", 80);
    }

    private static final Pattern BLOCK = Pattern.compile("\\{[^{}]*}");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Pattern PRIORITY = Pattern.compile("\"priority\"\\s*:\\s*(\\d+)");

    /**
     * Pair each flat JSON object's type with its priority. The
     * shipped table is flat (no nested objects), so an innermost
     * block walk cannot mis-pair.
     */
    private Map<String, Integer> parseShipped() throws IOException {
        Path json = RepoRoot.find()
                .resolve(Path.of("src", "main", "resources", "data", "mcbotserver", "reflex_rules.json"));
        String text = Files.readString(json);
        Map<String, Integer> found = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            Matcher t = TYPE.matcher(m.group());
            Matcher p = PRIORITY.matcher(m.group());
            if (t.find() && p.find()) {
                found.put(t.group(1), Integer.valueOf(p.group(1)));
            }
        }
        return found;
    }

    @Test
    void shippedTableCarriesExactlyTheGatedRungs() throws IOException {
        assertEquals(
                EXPECTED,
                parseShipped(),
                "reflex_rules.json must carry the gated rung set - "
                        + "a new rung extends this gate in the same change");
    }

    @Test
    void triageOrderIsStrictFromLavaDownToEngage() throws IOException {
        Map<String, Integer> shipped = parseShipped();
        String[] rungs = EXPECTED.keySet().toArray(String[]::new);
        for (int i = 0; i + 1 < rungs.length; i++) {
            int higher = shipped.get(rungs[i]);
            int lower = shipped.get(rungs[i + 1]);
            assertTrue(higher > lower, rungs[i] + "(" + higher + ") must outrank " + rungs[i + 1] + "(" + lower + ")");
        }
    }
}
