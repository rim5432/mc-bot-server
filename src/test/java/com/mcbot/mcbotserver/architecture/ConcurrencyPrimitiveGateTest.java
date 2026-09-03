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
 * Offline gate over concurrency primitives in core/: the policy of
 * record is that the tick thread owns all bot state (the adapter layer
 * owns cross-thread handoffs at boundary A), with ONE registered
 * exception: core/pathing/PlanWorker, the single-thread async A* worker
 * anticipated by ledger entry 17's ViewMode reservation, which hands
 * results back via completed futures read on the tick thread
 * (ledger 64). A {@code synchronized}, {@code volatile},
 * {@code java.util.concurrent.atomic.*},
 * {@code java.util.concurrent.locks.*}, or {@code java.util.concurrent.Concurrent*}
 * reference in core/ is a signal that the single-writer premise is being
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
 * <p>A second scan ({@link #planWorkerIsTheSoleThreadBearingFile}) covers
 * the construction forms the primitive pattern never anchored on —
 * {@code ExecutorService}/{@code Executors}/{@code new Thread} — and pins
 * the thread-bearing file set to exactly PlanWorker (ledger 64).
 *
 * <p>Task path: runs in the default {@code test} task (no {@code -Plint}
 * required), alongside GetDerefGateTest and EnglishOnlyScan.
 *
 * <p>Scope: core/ only. api/ is pure interfaces + DTOs with zero behavior;
 * adapter/ owns the real cross-thread boundaries (netty handlers, MC tick
 * callbacks) and is excluded by design.
 *
 * <p>Contract: see ledger entries 60 (core single-writer policy; the
 * InMemoryEventQueue AT_NONATOMIC exemption group and its trigger condition)
 * and 64 (PlanWorker registration; thread-bearing scan).
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
     * Thread-bearing primitive pattern: executor and thread construction
     * forms. Wider than {@link #CONCURRENCY_PRIMITIVE}, whose anchor on
     * the atomic/locks/Concurrent subpackages never matched
     * {@code ExecutorService}/{@code Executors}/{@code new Thread} — the
     * gap that let PlanWorker's pool live outside the gate unregistered
     * (ledger 64). Comment mentions count, same as the primitive scan;
     * lowercase prose ("executor", "tick thread") does not match.
     */
    private static final Pattern THREAD_BEARING =
            Pattern.compile("\\bExecutor(Service)?s?\\b|\\bnew Thread\\b|\\bThread\\.currentThread\\b");

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

    /**
     * The one registered thread-bearing file in core/: PlanWorker, the
     * single-thread async A* worker (ledger 64). Identified by file set,
     * not line pins - the file may evolve freely, but a SECOND
     * thread-bearing file, or PlanWorker's disappearance, both fail.
     */
    private static final Set<String> EXPECTED_THREAD_BEARING_FILES =
            Set.of("src/main/java/com/mcbot/mcbotserver/core/pathing/PlanWorker.java");

    /** Fails when the violation set drifts from the canary set. */
    @Test
    void coreHasZeroConcurrencyPrimitives() throws IOException {
        List<String> violations = scanCore(CONCURRENCY_PRIMITIVE);
        assertEquals(
                EXPECTED_CANARY_VIOLATIONS,
                violations,
                "concurrency primitive gate drift (missing canary = blind, extra = policy breach):\n"
                        + "expected: " + EXPECTED_CANARY_VIOLATIONS + "\n"
                        + "actual:   " + violations
                        + "\ncore/ is single-writer by policy (decision 60). Remove the primitive or"
                        + " register it in EXEMPT_FILES with a reason and re-audit the InMemoryEventQueue"
                        + " AT_NONATOMIC trigger (decision 59).");
    }

    /** Fails when core/ grows a second thread-bearing file, or loses the registered one. */
    @Test
    void planWorkerIsTheSoleThreadBearingFile() throws IOException {
        List<String> violations = scanCore(THREAD_BEARING);
        Set<String> files = violations.stream()
                .map(v -> v.substring(0, v.indexOf(':')))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                EXPECTED_THREAD_BEARING_FILES,
                files,
                "thread-bearing file drift (ledger 64):\n"
                        + "expected: " + EXPECTED_THREAD_BEARING_FILES + "\n"
                        + "actual:   " + files + "\nviolations: " + violations
                        + "\nAn extra file = an unregistered thread in core/; an empty set = PlanWorker"
                        + " moved or died. Re-register either way with a ledger entry.");
    }

    private List<String> scanCore(Pattern pattern) throws IOException {
        Path root = RepoRoot.find();
        Path coreDir = root.resolve("src/main/java/com/mcbot/mcbotserver/core");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(coreDir)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, root, violations, pattern));
        }
        return violations;
    }

    private void scanFile(Path file, Path repoRoot, List<String> violations, Pattern pattern) {
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
            if (pattern.matcher(lines.get(i)).find()) {
                violations.add(relative + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }
}
