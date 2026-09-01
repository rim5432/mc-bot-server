package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the contract-marker convention (AGENTS.md
 * 1.4.3.1, code-health H-R8): every src/main implementer of a boundary
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
    private static final List<String> BOUNDARY_INTERFACES =
            List.of("Actor", "Behavior", "BotProcess", "WorldView", "EventQueue", "CommandChannel", "MenuTransactions");

    /**
     * One known implementer per interface - proves the scan sees
     * the tree; a silent empty scan must not pass.
     */
    private static final Map<String, String> ANCHORS = Map.of(
            "Actor", "core/actor/ChannelArbiter.java",
            "Behavior", "core/behavior/PathingBehavior.java",
            "BotProcess", "core/process/MissionShell.java",
            "WorldView", "core/world/SnapshotWorldView.java",
            "EventQueue", "core/event/InMemoryEventQueue.java",
            "CommandChannel", "core/command/CommandBus.java",
            "MenuTransactions", "adapter/ActorMenuTransactions.java");

    /**
     * Marker shapes: the inline form and the Javadoc form. The pointer
     * target is validated against the known contract-doc set: ADR-NNNN
     * must resolve to doc/decisions/NNNN-*.md, issue NNNN / issues/NNNN
     * must resolve to doc/architecture/issues/ (open or archive), and
     * boundaries.md references are always valid (the file exists). What
     * the rule freezes is the pointer's presence AND its resolvability.
     */
    private static final Pattern MARKER = Pattern.compile("(?i)contract: see \\w");

    /** Extracts ADR-NNNN references from a marker line. */
    private static final Pattern ADR_REF = Pattern.compile("ADR-(\\d{4})");

    /** Extracts issue NNNN / issues/NNNN references from a marker line. */
    private static final Pattern ISSUE_REF = Pattern.compile("issues?[/ ](\\d{4})");

    /** Fails when any src/main boundary implementer lacks its
     *  contract marker. */
    @Test
    void boundaryImplementersCarryContractMarkers() {
        List<String> violations = new ArrayList<>();
        List<String> seenAnchors = new ArrayList<>();
        try (Stream<Path> files = Files.walk(RepoRoot.find().resolve(Path.of("src", "main", "java")))) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/com/mcbot/mcbotserver/api/"))
                    .forEach(p -> scanFile(p, violations, seenAnchors));
        } catch (IOException e) {
            fail("cannot scan main sources: " + e.getMessage());
            return;
        }
        for (var entry : ANCHORS.entrySet()) {
            assertTrue(
                    seenAnchors.contains(entry.getKey()),
                    () -> "no implementer of " + entry.getKey()
                            + " found - expected at least "
                            + entry.getValue()
                            + "; the scan came back incomplete.");
        }
        assertTrue(
                violations.isEmpty(),
                () -> "boundary-interface implementers without a "
                        + "contract marker (AGENTS.md 1.4.3.1):\n"
                        + String.join("\n", violations)
                        + "\nAdd '// contract: see ADR-NNNN' (or a "
                        + "'Contract: see boundaries.md section X' Javadoc "
                        + "line) at the class level.");
    }

    /**
     * Fails when any contract marker references an ADR or issue that
     * does not resolve to a file in the doc tree. ADR-NNNN must exist
     * under doc/decisions/; issue NNNN must exist under
     * doc/architecture/issues/ (open or archive). boundaries.md
     * references are always valid.
     */
    @Test
    void contractMarkersResolveToKnownDocs() {
        var validAdrs = collectDocNumbers(Path.of("doc", "decisions"));
        var validIssues = new java.util.HashSet<String>();
        validIssues.addAll(collectDocNumbers(Path.of("doc", "architecture", "issues")));
        validIssues.addAll(collectDocNumbers(Path.of("doc", "architecture", "issues", "archive")));
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(RepoRoot.find().resolve(Path.of("src", "main", "java")))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p);
                } catch (IOException e) {
                    violations.add(p + ": unreadable (" + e + ")");
                    return;
                }
                for (String line : lines) {
                    if (!MARKER.matcher(line).find()) {
                        continue;
                    }
                    var adrMatcher = ADR_REF.matcher(line);
                    while (adrMatcher.find()) {
                        String num = adrMatcher.group(1);
                        if (!validAdrs.contains(num)) {
                            violations.add(p + ": ADR-" + num + " not found in doc/decisions/");
                        }
                    }
                    var issueMatcher = ISSUE_REF.matcher(line);
                    while (issueMatcher.find()) {
                        String num = issueMatcher.group(1);
                        if (!validIssues.contains(num)) {
                            violations.add(
                                    p + ": issue " + num + " not found in doc/architecture/issues/ (open or archive)");
                        }
                    }
                }
            });
        } catch (IOException e) {
            fail("cannot scan main sources: " + e.getMessage());
            return;
        }
        assertTrue(
                violations.isEmpty(),
                () -> "contract markers with unresolvable targets:\n" + String.join("\n", violations));
    }

    private static java.util.Set<String> collectDocNumbers(Path dir) {
        Path root = RepoRoot.find().resolve(dir);
        if (!Files.isDirectory(root)) {
            return java.util.Set.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString().split("-")[0])
                    .collect(java.util.stream.Collectors.toSet());
        } catch (IOException e) {
            return java.util.Set.of();
        }
    }

    private static void scanFile(Path file, List<String> violations, List<String> anchorsSeen) {
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
            if (Pattern.compile("(implements|extends)[^;{()]*\\b" + iface + "\\b")
                    .matcher(source)
                    .find()) {
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
}
