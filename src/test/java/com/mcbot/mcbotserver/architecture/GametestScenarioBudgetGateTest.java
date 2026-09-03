package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 trend gate over the gametest suites: scenarios per file
 * never grow past the pinned budget. The 2026-09-03 refactor round
 * measured the maintenance cost of hand-copied scenario skeletons
 * (CPD families across potion/combat/hunger, BotCombatGameTests at
 * 1271 lines) and paid it down with shared fixtures; this gate keeps
 * the paid trend from silently regressing - a new scenario goes into
 * an existing suite only under the budget, otherwise it starts a new
 * suite class (and inherits the fixture families).
 *
 * <p>Why a source scan: the scenario set lives in {@code src/main}
 * gametest annotations and the count needs no classpath. The budget
 * is the post-split maximum (BotCombatGameTests, 18); lowering it is
 * a one-line ratchet - raise it only with a ruling, never to make a
 * red build green.
 *
 * <p>Rule: doc/architecture/code-health.md H-R11 neighborhood (trend
 * gates pin paid-down metrics).
 */
class GametestScenarioBudgetGateTest {

    /** Pinned ceiling: scenarios (@GameTest methods) per suite file. */
    private static final int MAX_SCENARIOS_PER_FILE = 18;

    /** Fails when any gametest suite file exceeds the scenario budget. */
    @Test
    void scenarioCountStaysUnderBudget() {
        Path gametestRoot = RepoRoot.find().resolve("src/main/java/com/mcbot/mcbotserver/gametest");
        try (Stream<Path> files = Files.list(gametestRoot)) {
            List<String> violations = files.filter(
                            p -> p.getFileName().toString().endsWith("GameTests.java"))
                    .map(p -> {
                        try {
                            long count = Files.readAllLines(p).stream()
                                    .filter(line -> line.contains("@GameTest("))
                                    .count();
                            if (count > MAX_SCENARIOS_PER_FILE) {
                                return p.getFileName() + ": " + count + " scenarios > budget " + MAX_SCENARIOS_PER_FILE;
                            }
                            return null;
                        } catch (IOException e) {
                            return p.getFileName() + ": unreadable (" + e.getMessage() + ")";
                        }
                    })
                    .filter(v -> v != null)
                    .toList();
            assertTrue(
                    violations.isEmpty(),
                    () -> "gametest scenario budget exceeded (trend gate):\n"
                            + String.join("\n", violations)
                            + "\nSplit the suite by capability family instead of growing one"
                            + " file past the budget; the fixture families in GametestRig are"
                            + " the opening rig for a new suite.");
        } catch (IOException e) {
            fail("cannot list gametest directory: " + e.getMessage());
        }
    }
}
