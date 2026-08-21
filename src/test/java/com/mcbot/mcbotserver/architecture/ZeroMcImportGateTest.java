package com.mcbot.mcbotserver.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer-1 architecture gate: {@code api/} and {@code core/} must never
 * import Minecraft or Forge types.
 *
 * <p>Contract: see boundaries.md section A and AGENTS.md 2.1. The whole
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
     * Fail with a per-file violation report when any api/ or core/
     * source file contains a forbidden import line.
     */
    @Test
    void apiAndCoreTreesHaveZeroMcImports() throws IOException {
        Path projectRoot = findProjectRoot();
        List<String> violations = new ArrayList<>();
        for (String pkg : new String[] {"api", "core"}) {
            Path pkgDir = projectRoot.resolve(
                "src/main/java/com/mcbot/mcbotserver/" + pkg);
            assertTrue(Files.isDirectory(pkgDir),
                () -> "expected package dir to exist: " + pkgDir);
            scanTree(pkgDir, violations);
        }
        assertEquals(List.of(), violations,
            "MC imports leaked into the pure-Java trees:\n"
                + String.join("\n", violations));
    }

    private void scanTree(Path root, List<String> violations)
            throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> scanFile(p, violations));
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
            for (String forbidden : FORBIDDEN_PREFIXES) {
                if (trimmed.startsWith(forbidden)) {
                    violations.add(file.getFileName() + ":" + (i + 1)
                        + ": " + trimmed);
                }
            }
        }
    }

    /**
     * Walk up from the working directory until a directory containing
     * src/main/java/com/mcbot/mcbotserver is found.
     *
     * @return the project root directory
     */
    private Path findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        assertNotNull(dir);
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(
                    "src/main/java/com/mcbot/mcbotserver"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "project root not found from working dir");
    }
}
