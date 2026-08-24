package com.mcbot.mcbotserver.hygiene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline gate over the reflection one-door rule: scans the test
 * sources for reflective access ({@code getDeclaredField},
 * {@code getDeclaredMethod}, {@code getDeclaredConstructor},
 * {@code Class.forName}) outside {@code PathingTestAccess}.
 *
 * <p>Why textual scanning instead of trusting review: reflection
 * against own package-private classes is an architecture smell
 * that reads like ordinary test code in a diff. The
 * {@code Class.forName} bypass this rule retired lived in
 * {@code AdoptFreshnessGateTest} for weeks with the "one door"
 * rule on the books - an FQN string survives renames silently and
 * only explodes at runtime. Collaborator-level tests belong
 * same-package under {@code core.behavior} (direct access, compile
 * errors on moves); the door is reserved for integration-level
 * mid-drive assertions.
 *
 * <p>Rule: doc/architecture/code-health.md H-R1 (reflection is the
 * exception, not the door).
 */
class ReflectionDoorCheck {

    /** The only files where test-side reflection is legal: the
     * door itself, and this check (its own pattern literals
     * would self-match). Paths are package-relative under
     * src/test/java. */
    private static final Set<String> SKIPPED = Set.of(
        "com/mcbot/mcbotserver/tickpipeline/PathingTestAccess.java",
        "com/mcbot/mcbotserver/hygiene/ReflectionDoorCheck.java");

    /** Each pattern marks a reflection entry point; any hit outside
     * {@link #SKIPPED} is a violation. */
    private static final String[] PATTERNS = {
        "getDeclaredField", "getDeclaredMethod",
        "getDeclaredConstructor", "Class.forName"
    };

    /** Fails listing every violation when any test source outside
     * the door uses reflection. */
    @Test
    void reflectionExistsOnlyBehindTheDoor() throws IOException {
        Path testRoot = repoRoot().resolve(
            Path.of("src", "test", "java"));
        List<String> violations = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(testRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> scanFile(p, testRoot, violations, seen));
        }
        // Non-vacuity pin: the door itself must be among the
        // scanned files, or this gate is scanning the wrong tree
        // and its green means nothing.
        assertTrue(seen.containsAll(SKIPPED),
            "scan did not see the expected files under " + testRoot
                + " - seen " + seen.size() + " files; is the walk "
                + "pointed at the right tree?");
        assertEquals(List.of(), violations,
            "test-side reflection outside PathingTestAccess"
                + " (code-health.md H-R1):\n"
                + String.join("\n", violations)
                + "\nCollaborator-level logic: move the test"
                + " same-package (core.behavior) and call the"
                + " package-private member directly - a moved class"
                + " must be a compile error. Integration mid-drive"
                + " state: route through PathingTestAccess.");
    }

    private void scanFile(Path file, Path testRoot,
                          List<String> violations, Set<String> seen) {
        String relative = testRoot.relativize(file).toString()
            .replace('\\', '/');
        seen.add(relative);
        if (SKIPPED.contains(relative)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            violations.add(relative + ": unreadable (" + e + ")");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            for (String pattern : PATTERNS) {
                if (lines.get(i).contains(pattern)) {
                    violations.add(relative + ":" + (i + 1) + ": "
                        + pattern + " outside the door");
                }
            }
        }
    }

    /** Walks up from the JVM working directory until it finds the
     * project marker chain, mirroring
     * {@code GametestInventoryCheck}.
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
