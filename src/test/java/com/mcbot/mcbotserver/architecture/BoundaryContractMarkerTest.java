package com.mcbot.mcbotserver.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Layer-1 gate for the contract-marker convention (AGENTS.md
 * 1.4.3.1 / 1.4.9): every src/main implementer of a boundary
 * interface carries a {@code contract: see ADR-NNNN} or
 * {@code contract: see boundaries.md section X} pointer, so a
 * future agent's "did I just break a contract?" review is one
 * Ctrl-F away. The api interfaces themselves are excluded - they
 * are the contract, and their Javadocs already point at it.
 *
 * <p>Why a source scan: the marker is a textual convention, and
 * the scan needs no classpath. Implementers are found by their
 * implements/extends clause; the interface inventory below is a
 * deliberate pinned list - growing it is a review checkpoint.
 *
 * <p>Rule: doc/architecture/code-health.md H-R8 (closes open item
 * H3).
 */
class BoundaryContractMarkerTest {

    /**
     * The boundary interfaces whose implementers must carry the
     * marker, keyed by simple name. Simple names are unambiguous
     * here: no non-api type in the tree shares them.
     */
    private static final List<String> BOUNDARY_INTERFACES = List.of(
        "Actor", "Behavior", "BotProcess", "WorldView", "EventQueue",
        "CommandChannel");

    /**
     * One known implementer per interface - proves the scan sees
     * the tree; a silent empty scan must not pass.
     */
    private static final Map<String, String> ANCHORS = Map.of(
        "Actor", "core/actor/ChannelArbiter.java",
        "Behavior", "core/behavior/PathingBehavior.java",
        "BotProcess", "core/process/GotoProcess.java",
        "WorldView", "core/world/SnapshotWorldView.java",
        "EventQueue", "core/event/InMemoryEventQueue.java",
        "CommandChannel", "core/command/CommandBus.java");

    /**
     * Marker shapes: the inline form and the Javadoc form. The
     * pointer target is deliberately open (ADR, boundaries.md,
     * issues/NNNN) - issue files are live contract records under
     * the doc governance tree; what the rule freezes is the
     * pointer's presence, not its spelling.
     */
    private static final Pattern MARKER = Pattern.compile(
        "(?i)contract: see \\w");

    /** Fails when any src/main boundary implementer lacks its
     *  contract marker. */
    @Test
    void boundaryImplementersCarryContractMarkers() {
        List<String> violations = new ArrayList<>();
        List<String> seenAnchors = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoRoot().resolve(
                Path.of("src", "main", "java")))) {
            files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().replace('\\', '/')
                    .contains("/com/mcbot/mcbotserver/api/"))
                .forEach(p -> scanFile(p, violations, seenAnchors));
        } catch (IOException e) {
            fail("cannot scan main sources: " + e.getMessage());
            return;
        }
        for (var entry : ANCHORS.entrySet()) {
            assertTrue(seenAnchors.contains(entry.getKey()),
                () -> "no implementer of " + entry.getKey()
                    + " found - expected at least "
                    + entry.getValue()
                    + "; the scan came back incomplete.");
        }
        assertTrue(violations.isEmpty(),
            () -> "boundary-interface implementers without a "
                + "contract marker (AGENTS.md 1.4.3.1):\n"
                + String.join("\n", violations)
                + "\nAdd '// contract: see ADR-NNNN' (or a "
                + "'Contract: see boundaries.md section X' Javadoc "
                + "line) at the class level.");
    }

    private static void scanFile(Path file, List<String> violations,
                                 List<String> anchorsSeen) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            violations.add(file + ": unreadable (" + e + ")");
            return;
        }
        String path = file.toString().replace('\\', '/');
        boolean implementsBoundary = false;
        for (String iface : BOUNDARY_INTERFACES) {
            if (Pattern.compile(
                    "(implements|extends)[^;{()]*\\b" + iface + "\\b")
                    .matcher(source).find()) {
                implementsBoundary = true;
                if (path.endsWith(ANCHORS.getOrDefault(iface, ""))) {
                    anchorsSeen.add(iface);
                }
            }
        }
        if (implementsBoundary && !MARKER.matcher(source).find()) {
            violations.add(path);
        }
    }

    /** Walks up from the JVM working directory until it finds the
     *  project marker chain, so the check is independent of which
     *  directory the Gradle test task runs in.
     *
     * @return the repository root, never null
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
