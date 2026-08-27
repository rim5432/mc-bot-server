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
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the invariant-marker convention (AGENTS.md 1.4):
 * every method in the {@code BotController.onTick()} call chain that
 * participates in the ADR-0005 crash state machine carries a
 * {@code // invariant: see ADR-0005} pointer, so a future agent
 * editing tick-critical code sees the invariant before changing it.
 *
 * <p>Why a source scan and a pinned list: the marker is a textual
 * convention, and "called from onTick" is not reliably decidable from
 * source text alone (virtual dispatch, lambdas). The pinned list is
 * deliberate — growing it is a review checkpoint, same policy as
 * {@link BoundaryContractMarkerTest}. The list covers the direct
 * onTick callees plus the external crash entry; transitive helpers
 * inside runPipeline are added when they carry ADR-0005 state.
 *
 * <p>Rule: AGENTS.md 1.4 ("Method called from BotController.onTick():
 * // invariant: see ADR-0005").
 */
class InvariantMarkerGateTest {

    /**
     * Methods in the onTick call chain that must carry the invariant
     * marker. Keyed by simple method name; all live in BotController
     * and are unambiguous at that name.
     */
    private static final List<String> TICK_CHAIN_METHODS =
            List.of("onTick", "runPipeline", "handleCrash", "emergencyLatch");

    /**
     * One anchor per method — proves the scan finds each declaration.
     * A silent empty scan must not pass.
     */
    private static final Map<String, String> ANCHORS = Map.of(
            "onTick", "public void onTick(WorldView world)",
            "runPipeline", "private void runPipeline(WorldView world)",
            "handleCrash", "private void handleCrash(RuntimeException e)",
            "emergencyLatch", "public void emergencyLatch(RuntimeException cause)");

    /**
     * Marker shape: the inline comment form. The ADR subsection is
     * deliberately open (D1, D2, D3, ...) — what the rule freezes is
     * the pointer's presence, not its spelling.
     */
    private static final Pattern INVARIANT_MARKER = Pattern.compile("(?i)invariant:\\s*see\\s*ADR-0005");

    /** How many lines above a declaration to search for the marker. */
    private static final int COMMENT_WINDOW = 15;

    /** Fails when any pinned tick-chain method lacks its invariant
     *  marker in the preceding comment block. */
    @Test
    void tickChainMethodsCarryInvariantMarkers() {
        Path controller = RepoRoot.find()
                .resolve(Path.of(
                        "src", "main", "java", "com", "mcbot", "mcbotserver", "core", "tick", "BotController.java"));
        if (!Files.exists(controller)) {
            fail("BotController.java not found at " + controller);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(controller);
        } catch (IOException e) {
            fail("cannot read BotController.java: " + e.getMessage());
            return;
        }

        List<String> violations = new ArrayList<>();
        List<String> seenAnchors = new ArrayList<>();

        for (String method : TICK_CHAIN_METHODS) {
            String anchor = ANCHORS.get(method);
            int declLine = findLineContaining(lines, anchor);
            if (declLine < 0) {
                violations.add(method + ": declaration not found (anchor: '" + anchor + "')");
                continue;
            }
            seenAnchors.add(method);
            if (!hasInvariantMarker(lines, declLine)) {
                violations.add(method + " (line " + (declLine + 1)
                        + "): missing '// invariant: see ADR-0005' in preceding comment block");
            }
        }

        for (var entry : ANCHORS.entrySet()) {
            assertTrue(
                    seenAnchors.contains(entry.getKey()),
                    () -> "anchor for " + entry.getKey() + " not matched — expected '" + entry.getValue()
                            + "', the scan came back incomplete.");
        }
        assertTrue(
                violations.isEmpty(),
                () -> "tick-chain methods without invariant markers (AGENTS.md 1.4):\n"
                        + String.join("\n", violations)
                        + "\nAdd '// invariant: see ADR-0005' (with the relevant subsection) "
                        + "in the comment block immediately above the method declaration.");
    }

    /**
     * Finds the first line containing the anchor string. Anchors are
     * exact method-signature substrings unique within BotController.
     */
    private static int findLineContaining(List<String> lines, String anchor) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(anchor)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Searches the COMMENT_WINDOW lines above declLine for the
     * invariant marker. Stops at the first non-comment, non-blank line
     * above the declaration (the marker must be in the immediately
     * preceding comment block, not somewhere earlier in the file).
     */
    private static boolean hasInvariantMarker(List<String> lines, int declLine) {
        for (int i = declLine - 1; i >= 0 && i >= declLine - COMMENT_WINDOW; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/**")) {
                if (INVARIANT_MARKER.matcher(line).find()) {
                    return true;
                }
            } else {
                // Reached non-comment code above the comment block.
                return false;
            }
        }
        return false;
    }
}
