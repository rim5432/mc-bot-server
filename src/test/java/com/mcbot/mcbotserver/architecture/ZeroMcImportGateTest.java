package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 architecture gate: {@code api/} and {@code core/} must never
 * import Minecraft or Forge types.
 *
 * <p>Contract: see boundaries.md section A and ledger entry 5. The whole
 * point of the api/core split is that Stage 0 acceptance runs as plain
 * JUnit on a machine without a Forge runtime; a single leaked
 * {@code net.minecraft.*} import would silently break that property.
 * The gate scans source text, so it needs no classpath trickery and
 * cannot be fooled by compile-time-only visibility.
 *
 * <p>Why a file scan instead of an import-analysis library: zero extra
 * dependencies, and the rule we enforce is literally textual ("no such
 * import line may exist"), not semantic.
 */
class ZeroMcImportGateTest {

    private static final String[] FORBIDDEN_PREFIXES = {
        "import net.minecraft.",
        "import net.minecraftforge.",
        "import static net.minecraft.",
        "import static net.minecraftforge."
    };

    /**
     * Known canary violations — the gate assertion is set-equality, not
     * zero: a missing canary means the scan went blind (scope shrank, walk
     * root moved, comment-detection dropped); an extra violation means a
     * real MC import appeared. The canary lives in
     * core/CanaryNotes.java as a commented import (commented-out code is
     * banned by AGENTS.md 1.4; catching it here is the gate's job).
     */
    private static final List<String> EXPECTED_CANARY_VIOLATIONS =
            List.of("CanaryNotes.java:27: // canary(zeromc): import net.minecraft.world.level.Level;");

    /**
     * Fail with a per-file violation report when any api/ or core/
     * source file contains a forbidden import line (real or commented-out).
     */
    @Test
    void apiAndCoreTreesHaveZeroMcImports() throws IOException {
        Path projectRoot = RepoRoot.find();
        List<String> violations = new ArrayList<>();
        for (String pkg : new String[] {"api", "core"}) {
            Path pkgDir = projectRoot.resolve("src/main/java/com/mcbot/mcbotserver/" + pkg);
            assertTrue(Files.isDirectory(pkgDir), () -> "expected package dir to exist: " + pkgDir);
            scanTree(pkgDir, violations);
        }
        assertEquals(
                EXPECTED_CANARY_VIOLATIONS,
                violations,
                "MC import gate drift (zero means blind, extra means leak):\n"
                        + "expected canary set: " + EXPECTED_CANARY_VIOLATIONS + "\n"
                        + "actual: " + violations);
    }

    private void scanTree(Path root, List<String> violations) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanFile(p, violations));
        }
    }

    private void scanFile(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            violations.add(file + ": unreadable (" + e + ")");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            boolean isComment = trimmed.startsWith("//");
            for (String forbidden : FORBIDDEN_PREFIXES) {
                boolean realImport = trimmed.startsWith(forbidden);
                boolean commentedImport = isComment && trimmed.contains(forbidden);
                if (realImport || commentedImport) {
                    violations.add(file.getFileName() + ":" + (i + 1) + ": " + trimmed);
                    break;
                }
            }
        }
    }
}
