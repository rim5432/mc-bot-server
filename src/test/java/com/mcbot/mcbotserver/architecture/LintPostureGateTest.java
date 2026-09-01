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
 * example the SpotBugs first-triage flip of 2026-09-01) updates this
 * gate, that table, and the ruling in the same commit.
 *
 * <p>Rule: doc/architecture/code-health.md H-R11.
 */
class LintPostureGateTest {

    /** One pinned configuration block: marker line plus the wiring it must contain. */
    private record Posture(String blockMarker, List<String> requiredLines) {}

    /**
     * The pinned wiring. Hard gates: checkstyle, spotlessCheck, and the
     * api-scoped javadoc doclint (javadocApi) hang
     * off the test task, and the JaCoCo coverage floor rides it as a
     * finalizer (pinned 2026-09-01; a finalizer because the
     * verification task itself depends on test - a dependsOn edge would
     * close a cycle). Red walls under -Plint: PMD (failing since
     * the 2026-08-27 paydown), CPD (>= 140 tokens), and SpotBugs
     * (failing since the 2026-09-01 first-triage flip; its triaged
     * suppressions live in config/spotbugs/exclude.xml, each with an
     * inline justification). No task softens with ignoreFailures.
     * The checkstyle analyzer JVM runs on Java 21 (checkstyle 14 needs
     * it; the mod toolchain stays 17).
     *
     * <p>SpotBugs toolVersion is pinned because the NP capability set is
     * version-sensitive: 4.10.4 is verified (2026-09-01 injection probe)
     * SILENT on both computeIfAbsent-then-get and assignment-then-deref
     * of Map.get() return values. A version bump must re-run the NP probe
     * before this pin moves — the gate's documented null-surface blind
     * spots depend on this fact.
     *
     * <p>Error Prone and NullAway versions are pinned for the same reason:
     * NullAway's nullness inference is version-sensitive. 0.11.2 on EP
     * 2.42.0 is verified clean across api+core (2026-09-01 full annotation
     * wave, 0 warnings). A NullAway or EP core version bump must re-run
     * a full compile -Plint before this pin moves; a plugin version bump
     * (5.1.0) must verify the CheckSeverity enum and option wiring still
     * match. The AnnotatedPackages value pins the nullness domain: api
     * (harness contract) + core (implementation). Expanding it is a ruling.
     */
    private static final List<Posture> PINNED_POSTURES = List.of(
            new Posture(
                    "tasks.named('test', Test).configure {",
                    List.of(
                            "dependsOn 'checkstyleMain', 'checkstyleTest', 'spotlessCheck', 'javadocApi'",
                            "finalizedBy 'jacocoTestCoverageVerification'")),
            new Posture("tasks.register('javadocApi', Javadoc) {", List.of("Xdoclint:all,-missing", "Xwerror")),
            new Posture(
                    "tasks.withType(Checkstyle).configureEach {",
                    List.of("languageVersion = JavaLanguageVersion.of(21)")),
            new Posture("tasks.withType(Pmd).configureEach {", List.of("enabled = project.hasProperty('lint')")),
            new Posture(
                    "tasks.withType(com.github.spotbugs.snom.SpotBugsTask).configureEach {",
                    List.of("enabled = project.hasProperty('lint')")),
            new Posture(
                    "spotbugs {",
                    List.of("toolVersion = '4.10.4'", "excludeFilter = file('config/spotbugs/exclude.xml')")),
            new Posture(
                    "options.errorprone {",
                    List.of(
                            "enabled = project.hasProperty('lint')",
                            "check('NullAway', net.ltgt.gradle.errorprone.CheckSeverity.ERROR)",
                            "option('NullAway:AnnotatedPackages', "
                                    + "'com.mcbot.mcbotserver.api,com.mcbot.mcbotserver.core')")),
            new Posture("tasks.register('cpdCheck', JavaExec) {", List.of("enabled = project.hasProperty('lint')")),
            new Posture("tasks.register('qualityCheck') {", List.of("'pmdMain'", "'spotbugsTest'", "'spotlessCheck'")),
            new Posture("id 'net.ltgt.errorprone' version", List.of("'5.1.0'")),
            new Posture("errorprone 'com.google.errorprone:error_prone_core:", List.of("2.42.0")),
            new Posture("errorprone 'com.uber.nullaway:nullaway:", List.of("0.11.2")));

    /** Soft-posture budget: zero - suppressions live in the SpotBugs
     * exclude filter with a written reason, never in ignoreFailures. */
    private static final int PINNED_IGNORE_FAILURES = 0;

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

    /** Fails when any check grows ignoreFailures. */
    @Test
    void ignoreFailuresStaysScopedToTheDashboards() {
        String gradle = readBuildGradle();
        int count = gradle.split("ignoreFailures = true", -1).length - 1;
        assertEquals(
                PINNED_IGNORE_FAILURES,
                count,
                () -> "ignoreFailures = true appears " + count + "x, pinned " + PINNED_IGNORE_FAILURES
                        + "x. Softening any check is a posture change: see the remediation in"
                        + " lintPosturesStayPinned.");
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
