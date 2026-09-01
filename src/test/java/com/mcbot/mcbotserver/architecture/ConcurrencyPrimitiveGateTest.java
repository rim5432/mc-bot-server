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
 * Offline gate over concurrency primitives in core/: the core layer is
 * single-threaded by policy (everything runs on the tick thread; the
 * adapter layer owns all cross-thread handoffs at boundary A). A
 * {@code synchronized}, {@code volatile}, {@code java.util.concurrent.atomic.*},
 * {@code java.util.concurrent.locks.*}, or {@code java.util.concurrent.Concurrent*}
 * reference in core/ is a signal that the single-threaded premise is being
 * questioned — either the code is paper (unnecessary atomicity under a
 * single-threaded invariant) or the thread model quietly changed (which
 * should trigger the InMemoryEventQueue AT_NONATOMIC exemption re-audit,
 * decision 59).
 *
 * <p>Semantic boundary — this gate catches the <em>form</em> of a concurrency
 * primitive reference, not a thread-safety proof. It catches keyword mentions
 * in comments as well as real code: any discussion of synchronization in
 * core/ is worth a review. Documented blind spots:
 * <ul>
 *   <li>concurrency utilities referenced by fully-qualified name without the
 *       {@code java.util.concurrent} prefix (e.g. a wildcard import plus
 *       bare {@code AtomicLong}) — the pattern anchors on the package path.
 *       Zero live sites in core/ as of 2026-09-01.</li>
 *   <li>{@code ThreadLocal}, {@code java.util.concurrent.Future},
 *       {@code CompletableFuture} — these are cross-thread types but not
 *       primitives; the InMemoryEventQueue pendingPlan field is a
 *       CompletableFuture and is the single documented exception (the future
 *       itself is the cross-thread boundary, its completion is single-threaded
 *       on the tick thread). This is registered in decision 59.</li>
 * </ul>
 *
 * <p>Task path: runs in the default {@code test} task (no {@code -Plint}
 * required), alongside GetDerefGateTest and EnglishOnlyScan.
 *
 * <p>Scope: core/ only. api/ is pure interfaces + DTOs with zero behavior;
 * adapter/ owns the real cross-thread boundaries (netty handlers, MC tick
 * callbacks) and is excluded by design.
 *
 * <p>Contract: see ledger entry 60 (core single-threaded policy; the
 * InMemoryEventQueue AT_NONATOMIC exemption group and its trigger condition).
 */
class ConcurrencyPrimitiveGateTest {

    /**
     * Concurrency primitive pattern: synchronized or volatile keyword, or
     * a fully-qualified reference to java.util.concurrent.atomic, .locks,
     * or Concurrent* collections. Catches both real code and comment
     * mentions (commented-out concurrency is banned per AGENTS.md 1.4).
     */
    private static final Pattern CONCURRENCY_PRIMITIVE =
            Pattern.compile("\\b(synchronized|volatile)\\b|java\\.util\\.concurrent\\.(atomic|locks|Concurrent)");

    /**
     * Exempted files, as repo-relative POSIX paths. Empty at launch: the
     * 2026-09-01 audit replaced the sole AtomicLong (InMemoryEventQueue
     * sessionLocalEpochAllocator) with a long[] holder. Every future entry
     * is a review decision with a written reason — the same discipline as
     * config/spotbugs/exclude.xml.
     */
    private static final Set<String> EXEMPT_FILES = Set.of();

    /**
     * Known canary violations — set-equality assertion, not zero. The
     * must-match canary (commented synchronized block) lives in
     * core/CanaryNotes.java; a missing canary means the scan went blind.
     */
    private static final List<String> EXPECTED_CANARY_VIOLATIONS =
            List.of("src/main/java/com/mcbot/mcbotserver/core/CanaryNotes.java:33: "
                    + "// canary(concurrency): synchronized (lock) {");

    /** Fails when the violation set drifts from the canary set. */
    @Test
    void coreHasZeroConcurrencyPrimitives() throws IOException {
        Path root = RepoRoot.find();
        Path coreDir = root.resolve("src/main/java/com/mcbot/mcbotserver/core");
        List<String> violations = new ArrayList<>();
        scanTree(coreDir, root, violations);
        assertEquals(
                EXPECTED_CANARY_VIOLATIONS,
                violations,
                "concurrency primitive gate drift (missing canary = blind, extra = policy breach):\n"
                        + "expected: " + EXPECTED_CANARY_VIOLATIONS + "\n"
                        + "actual:   " + violations
                        + "\ncore/ is single-threaded by policy (decision 60). Remove the primitive or"
                        + " register it in EXEMPT_FILES with a reason and re-audit the InMemoryEventQueue"
                        + " AT_NONATOMIC trigger (decision 59).");
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
            if (CONCURRENCY_PRIMITIVE.matcher(lines.get(i)).find()) {
                violations.add(relative + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }
}
