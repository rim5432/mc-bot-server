package com.mcbot.mcbotserver.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root for scan-style gates that read sources
 * as files: walks up from the working directory until a directory
 * carrying both the main source tree and {@code gradle.properties}
 * is found.
 *
 * <p>Shared test infrastructure; not part of any boundary.
 */
public final class RepoRoot {

    private RepoRoot() {
    }

    /**
     * Find the repository root above the current working directory.
     *
     * @return the absolute repository root, never null
     * @throws IllegalStateException when no ancestor directory holds
     *         the marker layout, meaning the JVM was launched outside
     *         a checkout
     */
    public static Path find() {
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
