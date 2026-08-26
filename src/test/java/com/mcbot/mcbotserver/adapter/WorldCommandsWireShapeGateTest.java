package com.mcbot.mcbotserver.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static com.mcbot.mcbotserver.testsupport.RepoRoot.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-shape gate over {@code WorldCommands.register}'s volume chain:
 * every axis the runner reads by name must be registered as a
 * {@code volumeArg}, and vice versa - the sets must match exactly.
 *
 * <p>Why a source scan instead of a brigadier parse: the test
 * classpath carries no Minecraft/brigadier classes, and brigadier
 * compiles an unused-lambda read silently - the {@code dz} defect
 * this gate was born from was invisible to javac AND to live smoke
 * that only exercised single-cell reads. Reading the registration
 * source makes the symmetry mechanical and future-axis-proof: adding
 * {@code dw} without registering it (or registering an axis nothing
 * reads) fails here.
 *
 * <p>Rule family: code-health.md scan-gate pattern (H-R series).
 */
class WorldCommandsWireShapeGateTest {

    private static final Pattern VOLUME_ARG = Pattern.compile(
        "volumeArg\\(\"(\\w+)\"");

    private static final Pattern RUNNER_READ = Pattern.compile(
        "getInteger\\(ctx, \"(\\w+)\"\\)");

    private static Path worldCommandsSource() {
        return find()
            .resolve(Path.of("src", "main", "java", "com", "mcbot",
                "mcbotserver", "adapter", "WorldCommands.java"));
    }

    /** Fails unless register's volume axes and runBlocks's reads are
     *  exactly {dx, dy, dz} on both sides. */
    @Test
    void volumeAxesRegisteredAndReadMatchExactly() throws Exception {
        String src = Files.readString(worldCommandsSource());
        // Registration side scans the whole file: the chain lives in
        // register(), above runBlocks.
        TreeSet<String> registered =
            new TreeSet<>(allMatches(src, VOLUME_ARG));
        TreeSet<String> expected = new TreeSet<>(List.of("dx", "dy",
            "dz"));
        assertEquals(expected, registered,
            "registered volume axes drifted - every axis must be "
                + "chained via volumeArg and read by runBlocks");

        String runner = section(src, "private static int runBlocks");
        TreeSet<String> read = new TreeSet<>(allMatches(runner,
            RUNNER_READ));
        read.removeAll(List.of("x", "y", "z"));
        assertEquals(expected, read,
            "runBlocks must read every registered axis by name");
        assertTrue(read.containsAll(expected),
            "a missing runtime read would parse fine and throw at "
                + "dispatch time only");
    }

    private static List<String> allMatches(String text, Pattern p) {
        Matcher m = p.matcher(text);
        return m.results().map(r -> r.group(1))
            .collect(Collectors.toList());
    }

    /**
     * Source text from a method's declaration to the next private
     * boundary - good enough to keep the runner's own reads isolated
     * from other methods'.
     */
    private static String section(String src, String declMarker) {
        int start = src.indexOf(declMarker);
        assertTrue(start >= 0, "marker not found: " + declMarker);
        int end = src.indexOf("private static", start
            + declMarker.length());
        return end < 0 ? src.substring(start)
            : src.substring(start, end);
    }
}
