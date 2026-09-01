package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Offline gate over chained get-deref patterns in the offline-testable
 * layers (api/ + core/): any {@code receiver.get(key).member} chain is a
 * site where the non-null guarantee may live in a convention (keySet-origin
 * key, computeIfAbsent predecessor, has-check guard, index-bounds check)
 * rather than in the type system. The 2026-09-01 audit cleared all six
 * existing sites; this gate prevents a seventh.
 *
 * <p>Semantic boundary — this gate catches the <em>form</em> of a chained
 * dereference, not the <em>semantics</em> of null flow. Documented blind
 * spots:
 * <ul>
 *   <li>assignment-then-unchecked-use (CommandBus-type missed check:
 *       {@code var x = map.get(k); x.method()} without a null check) —
 *       the get and the deref are on different lines, the line scanner
 *       cannot connect them. <strong>Verified 2026-09-01 via injection
 *       probe: SpotBugs 4.10.4 NP is SILENT on this pattern</strong>
 *       (same disease as computeIfAbsent-then-get). This is an unguarded
 *       null surface in core/; closure requires NullAway expansion into
 *       core/ (deferred, next-round main debt per the B1 probe verdict).</li>
 *   <li>nested function arguments: {@code get(f(k)).x()} is not caught
 *       because {@code [^)]*} stops at the first {@code )} inside the
 *       argument list. Zero live sites in api/ + core/ as of 2026-09-01;
 *       the canary set pins this boundary.</li>
 *   <li>whether a particular get is on a List (index-safe within bounds)
 *       vs a Map (key-absent returns null) — a source scan has no type
 *       info.</li>
 * </ul>
 * Closing the first two requires an Error Prone custom BugChecker (AST +
 * type resolution, can see across statements and into nested args) or
 * NullAway expansion into core/. The List/Map distinction is a
 * false-positive tax the text gate pays for zero dependencies.
 *
 * <p>Task path: runs in the default {@code test} task (no {@code -Plint}
 * required), alongside EnglishOnlyScan and ZeroMcImport — the architecture
 * gate set is part of every commit's compile+test flow, not the lint-only
 * dashboard. This is the "feedback-distance inversion": EP/NullAway/SpotBugs
 * ride -Plint (CI static-analysis job), but this text gate is in the default
 * loop, closer to the developer.
 *
 * <p>Scope: api/ + core/ only, matching SpotBugs onlyAnalyze and the
 * JaCoCo offline-tested exclusions. adapter/, client/, gametest/, and
 * McBotServer are engine-adjacent and excluded — their get chains ride MC
 * types whose nullness is engine-defined and covered by gametests.
 *
 * <p>Contract: see ledger entry (core nullness strategy: the six-site
 * get-deref audit of 2026-09-01; this gate is the regression guard).
 */
class GetDerefGateTest {

    /**
     * Chained get-deref pattern: dot, literal {@code get(}, anything but
     * close paren, close paren, optional whitespace, dot, letter. Matches
     * {@code map.get(k).method()} and {@code list.get(i).field()} but not
     * {@code getAsJsonObject(} (method name is not exactly "get"),
     * {@code getOrDefault(} (same), or {@code map.get(k)} as an argument
     * (no dot after the close paren).
     */
    private static final Pattern GET_DEREF = Pattern.compile("\\.get\\([^)]*\\)\\s*\\.[a-zA-Z]");

    /**
     * Exempted files, as repo-relative POSIX paths. Empty at launch: the
     * 2026-09-01 audit cleared all api/ + core/ sites. Every future entry
     * is a review decision recorded in the committing message with a
     * reason — the same discipline as config/spotbugs/exclude.xml and
     * EnglishOnlyScan.ALLOWED_DATA_FILES. A non-empty set is the trigger
     * for the Error Prone custom BugChecker upgrade path (see class
     * Javadoc).
     */
    private static final Set<String> EXEMPT_FILES = Set.of();

    /**
     * Known canary violations — set-equality assertion, not zero. A
     * missing canary means the scan went blind (regex changed, scope
     * shrank, walk root moved); an extra violation means a real get-deref
     * appeared. The must-match canary (simple-arg chain) lives in
     * core/CanaryNotes.java:28; the must-not-match canary (nested-arg
     * chain, line 29) must NOT appear here — it pins the regex blind
     * spot documented in the class Javadoc.
     */
    private static final List<String> EXPECTED_CANARY_VIOLATIONS =
            List.of("src/main/java/com/mcbot/mcbotserver/core/CanaryNotes.java:28: "
                    + "// canary(getderef): state.get(ruleName).lastPriority");

    /** Fails when the violation set drifts from the canary set. */
    @Test
    void offlineLayersHaveZeroChainedGetDerefs() throws IOException {
        Path root = RepoRoot.find();
        List<String> violations = new ArrayList<>();
        for (String module : new String[] {"api", "core"}) {
            Path moduleDir = root.resolve("src/main/java/com/mcbot/mcbotserver/" + module);
            scanTree(moduleDir, root, violations);
        }
        assertEquals(
                EXPECTED_CANARY_VIOLATIONS,
                violations,
                "get-deref gate drift (missing canary = blind, extra = leak):\n"
                        + "expected: " + EXPECTED_CANARY_VIOLATIONS + "\n"
                        + "actual:   " + violations
                        + "\nExtract the get result to a local variable, or guard with has()/containsKey()"
                        + " before dereferencing. If the site is genuinely safe (List.get with bounds"
                        + " check, keySet-origin key), register it in EXEMPT_FILES with a reason.");
    }

    private void scanTree(Path root, Path repoRoot, List<String> violations) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, repoRoot, violations));
        }
    }

    private void scanFile(Path file, Path repoRoot, List<String> violations) {
        String relative = repoRoot.relativize(file).toString().replace('\\', '/');
        if (EXEMPT_FILES.contains(relative)) {
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
            if (GET_DEREF.matcher(lines.get(i)).find()) {
                violations.add(relative + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }
}
