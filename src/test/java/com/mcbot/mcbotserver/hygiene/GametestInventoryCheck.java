package com.mcbot.mcbotserver.hygiene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Offline gate over the gametest inventory: scans the gametest
 * sources and pins the exact set of registered {@code @GameTest}
 * scenarios. The offline suite cannot see engine-side registration,
 * so a deleted scenario stays green everywhere except
 * {@code build runGameTest} - which is exactly how
 * {@code routesThroughFenceGap} was once lost to a probe-cleanup
 * regex and only restored after the drift was noticed (5e733bd).
 *
 * <p>Why a pinned list instead of a minimum count: a count cannot
 * tell "one test replaced by another" from "one test lost". Updating
 * {@link #EXPECTED} is deliberate friction - it is the review
 * checkpoint for every intentional add or remove.
 *
 * <p>Rule: doc/architecture/code-health.md H-R5 (gametest edits are
 * engine-verified before commit); this test makes the silent-loss
 * half of that rule mechanical.
 */
class GametestInventoryCheck {

    /** Every scenario that must stay registered, keyed by simple
     * class name. Must match the {@code @GameTest} methods under
     * the gametest package exactly; any diff fails this test. */
    private static final Map<String, Set<String>> EXPECTED = Map.of(
        "BotSliceGameTests", Set.of(
            "walksToBlock",
            "crossesWaterTrench",
            "crossesDeepPool",
            "crossesLavaTrench",
            "failsCleanlyWhenUnwalkable",
            "recoversWhenShoved",
            "refusesRangedItCannotAnswer",
            "defendsByKillingZombie",
            "survivesRetaliatingZombie",
            "hostilesAggroOnSight",
            "holdsFireWhenSightBlocked",
            "surfacesWhenAirRunsLow",
            "escapesLavaToShore",
            "findsWaterWhenBurning",
            "climbsOutOfPowderSnow",
            "digsFreeWhenSuffocating",
            "regeneratesHealthWhenBelowMax",
            "dropsSelectedItem",
            "placesBlockOnInteract",
            "craftsDiamondBlockAtTable",
            "picksUpFromBackpackSlot",
            "returnsCraftingGridOnClose",
            "rejectsMenuBeyondReach",
            "returnsGridWithoutCorruptingOccupiedSlot0",
            "dropsGridMaterialsWhenInventoryFull"),
        "GauntletGameTests", Set.of(
            "gauntletEndToEnd",
            "routesThroughFenceGap"),
        "CrashRecoveryGameTests", Set.of(
            "latchesAndRecoversAfterCrash"),
        "ProductionWiringGameTests", Set.of(
            "walksViaCommandAndServerTick"));

    /** Pulls the method name out of each
     * {@code @GameTest(...)} declaration in a source file. */
    private static final Pattern GAME_TEST_METHOD = Pattern.compile(
        "@GameTest[\\s\\S]*?public\\s+static\\s+void"
            + "\\s+(\\w+)\\s*\\(");

    /** Fails when the registered gametest set differs from the
     * pinned inventory in either direction. */
    @Test
    void registeredScenariosMatchThePinnedInventory() {
        Path dir = repoRoot().resolve(
            "src/main/java/com/mcbot/mcbotserver/gametest");
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName()
                    .toString().endsWith(".java")).toList()) {
                Matcher m = GAME_TEST_METHOD.matcher(Files.readString(p));
                while (m.find()) {
                    String holder = p.getFileName().toString()
                        .replace(".java", "");
                    actual.add(holder + "." + m.group(1));
                }
            }
        } catch (IOException e) {
            fail("cannot scan the gametest sources under " + dir
                + ": " + e.getMessage());
        }
        Set<String> expected = new TreeSet<>();
        EXPECTED.forEach((holder, names) ->
            names.forEach(n -> expected.add(holder + "." + n)));
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            fail("gametest inventory drifted. missing=" + missing
                + " unexpected=" + unexpected
                + ". If this diff is intentional, update EXPECTED in "
                + GametestInventoryCheck.class.getSimpleName()
                + "; if not, a scenario was lost on its way to commit "
                + "- restore it and run build runGameTest before "
                + "committing (the routesThroughFenceGap incident).");
        }
    }

    /** Walks up from the JVM working directory until it finds the
     * project marker, so the check is independent of which directory
     * the Gradle test task runs in.
     *
     * @return the repository root, never null
     * @throws IllegalStateException when no directory above the
     *         working directory carries the project marker chain
     */
    private static Path repoRoot() {
        for (Path p = Path.of("").toAbsolutePath();
             p != null;
             p = p.getParent()) {
            boolean hasSources = Files.exists(p.resolve(Path.of("src",
                "main", "java", "com", "mcbot", "mcbotserver")));
            if (hasSources && Files.exists(p.resolve("gradle.properties"))) {
                return p;
            }
        }
        throw new IllegalStateException(
            "project root not found above " + Path.of("").toAbsolutePath());
    }
}
