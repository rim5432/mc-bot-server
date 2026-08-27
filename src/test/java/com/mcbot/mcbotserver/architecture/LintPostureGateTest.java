package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate over the lint postures wired in {@code build.gradle}
 * (code-health H-R11): which checks block the default {@code test}
 * flow and which stay advisory dashboards behind {@code -Plint} is a
 * recorded ruling, not an accident, and a posture change that skips
 * its ruling must fail loud instead of silently disarming a gate.
 *
 * <p>Why a source scan: the postures live in Gradle configuration
 * text and the scan needs no classpath. The inventory below is a
 * deliberate pinned snapshot mirroring the posture table in
 * doc/guide/build-and-run.md; a deliberate posture change (for
 * example the planned PMD flip to failing once its paydown lands)
 * updates this gate, that table, and the ruling in the same commit.
 *
 * <p>Rule: doc/architecture/code-health.md H-R11.
 */
class LintPostureGateTest {

    /** One pinned configuration block: marker line plus the wiring it must contain. */
    private record Posture(String blockMarker, List<String> requiredLines) {}

    /**
     * The pinned wiring. Hard gates: checkstyle and spotlessCheck hang
     * off the test task; PMD is a RED WALL under -Plint since its flip
     * condition landed (zero findings held, 2026-08-27 paydown).
     * Dashboards: SpotBugs and cpdCheck enable only under -Plint;
     * SpotBugs additionally softens with ignoreFailures until its own
     * first-triage flip lands.
     */
    private static final List<Posture> PINNED_POSTURES = List.of(
            new Posture(
                    "tasks.named('test', Test).configure {",
                    List.of("dependsOn 'checkstyleMain', 'checkstyleTest', 'spotlessCheck'")),
            new Posture("tasks.withType(Pmd).configureEach {", List.of("enabled = project.hasProperty('lint')")),
            new Posture(
                    "tasks.withType(com.github.spotbugs.snom.SpotBugsTask).configureEach {",
                    List.of("enabled = project.hasProperty('lint')", "ignoreFailures = true")),
            new Posture("options.errorprone {", List.of("enabled = project.hasProperty('lint')")),
            new Posture("tasks.register('cpdCheck', JavaExec) {", List.of("enabled = project.hasProperty('lint')")));

    /** Soft-posture budget: exactly the SpotBugs dashboard softens, nothing else. */
    private static final int PINNED_IGNORE_FAILURES = 1;

    /** Fails when any pinned posture block drifts inside build.gradle. */
    @Test
    void lintPosturesStayPinned() {
        String gradle = readBuildGradle();
        List<String> violations = PINNED_POSTURES.stream()
                .flatMap(posture -> check(posture, gradle).stream())
                .toList();
        assertTrue(
                violations.isEmpty(),
                () -> "lint posture drift in build.gradle (H-R11):\n"
                        + String.join("\n", violations)
                        + "\nA posture change is a ruling: update this gate, the posture table"
                        + " in doc/guide/build-and-run.md, and the ruling in the same commit.");
    }

    /** Fails when any check beyond the two dashboards grows ignoreFailures. */
    @Test
    void ignoreFailuresStaysScopedToTheDashboards() {
        String gradle = readBuildGradle();
        int count = gradle.split("ignoreFailures = true", -1).length - 1;
        assertEquals(
                PINNED_IGNORE_FAILURES,
                count,
                () -> "ignoreFailures = true appears " + count + "x, pinned " + PINNED_IGNORE_FAILURES
                        + "x (PMD + SpotBugs dashboards only). Softening any other check is a"
                        + " posture change: see the remediation in lintPosturesStayPinned.");
    }

    private static String readBuildGradle() {
        try {
            return Files.readString(RepoRoot.find().resolve("build.gradle"));
        } catch (IOException e) {
            fail("cannot read build.gradle: " + e.getMessage());
            return "";
        }
    }

    private static List<String> check(Posture posture, String gradle) {
        int start = gradle.indexOf(posture.blockMarker());
        if (start < 0) {
            return List.of("missing block: " + posture.blockMarker());
        }
        // blocks close at column 0, so the first "\n}" after the marker
        // bounds the configuration this posture pins
        int end = gradle.indexOf("\n}", start);
        if (end < 0) {
            return List.of("unterminated block: " + posture.blockMarker());
        }
        String block = gradle.substring(start, end);
        return posture.requiredLines().stream()
                .filter(line -> !block.contains(line))
                .map(line -> posture.blockMarker() + " no longer contains: " + line)
                .toList();
    }
}
