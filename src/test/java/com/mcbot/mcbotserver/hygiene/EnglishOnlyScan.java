package com.mcbot.mcbotserver.hygiene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.mcbot.mcbotserver.testsupport.RepoRoot;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Offline gate over the English-only mandate: scans every tree the
 * mandate covers - all repository markdown (AGENTS.md 0.3) and all
 * Java sources including comments and Javadoc (code-health.md H-R3)
 * - and fails on the first CJK codepoint found.
 *
 * <p>Why whole-file scanning of Java instead of comment-stripping:
 * a CJK codepoint inside a string literal is almost always a comment
 * that forgot it was a comment, and the rare genuine data literal
 * (a preserved game-content string) goes through
 * {@link #ALLOWED_DATA_FILES} deliberately - the same review friction
 * {@code GametestInventoryCheck.EXPECTED} applies to the gametest
 * inventory.
 *
 * <p>Scope note: {@code tool/mcbot_tool.py} is outside the mandate's
 * letter (markdown and Java only) and is not scanned; translating its
 * comments is a separate hygiene round if ever scheduled.
 *
 * <p>Rule: doc/architecture/code-health.md H-R3 (English-only
 * everywhere); this test replaces the manual regex scrub that rule
 * used to require.
 */
class EnglishOnlyScan {

    /**
     * Files exempted from the scan, as repo-relative POSIX paths.
     * Empty today; every future entry is a review decision recorded
     * in the committing message, not a silent escape hatch.
     */
    private static final Set<String> ALLOWED_DATA_FILES = Set.of();

    /** Fails listing every violation when any covered file carries
     * a CJK codepoint. */
    @Test
    void coveredTreesCarryZeroCjkCodepoints() throws IOException {
        Path root = RepoRoot.find();
        List<String> violations = new ArrayList<>();
        scanMarkdown(root, violations);
        for (String tree : new String[] {"src/main/java", "src/test/java"}) {
            scanJavaTree(root.resolve(tree), root, violations);
        }
        assertEquals(List.of(), violations,
            "CJK codepoints leaked into mandate-covered sources"
                + " (AGENTS.md 0.3, code-health.md H-R3):\n"
                + String.join("\n", violations)
                + "\nTranslate the offending prose to English; citations"
                + " from non-English sources are English translations,"
                + " never reproduced originals. A genuine data literal"
                + " joins ALLOWED_DATA_FILES by explicit review.");
    }

    /** Scans root-level markdown plus every .md under doc/ and
     * tool/. */
    private void scanMarkdown(Path root, List<String> violations)
            throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                .forEach(p -> scanFile(p, root, violations));
        }
        for (String dir : new String[] {"doc", "tool"}) {
            try (Stream<Path> paths = Files.walk(root.resolve(dir))) {
                paths.filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> scanFile(p, root, violations));
            }
        }
    }

    private void scanJavaTree(Path tree, Path root,
                              List<String> violations) {
        if (!Files.isDirectory(tree)) {
            throw new IllegalStateException(
                "expected source tree to exist: " + tree);
        }
        try (Stream<Path> paths = Files.walk(tree)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> scanFile(p, root, violations));
        } catch (IOException e) {
            violations.add("cannot walk " + tree + ": " + e.getMessage());
        }
    }

    /** Records file:line plus the offending codepoint for each CJK
     * character in one file. */
    private void scanFile(Path file, Path root, List<String> violations) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ALLOWED_DATA_FILES.contains(relative)) {
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
            final int lineNumber = i + 1;
            lines.get(i).codePoints().forEach(cp -> {
                if (isCjk(cp)) {
                    violations.add(relative + ":" + lineNumber
                        + ": U+" + Integer.toHexString(cp).toUpperCase()
                        + " '" + new String(Character.toChars(cp)) + "'");
                }
            });
        }
    }

    /**
     * Broad CJK detector: Han, Kana and Hangul scripts plus CJK
     * punctuation and fullwidth forms (which carry no script and
     * would otherwise slip a script-only check).
     *
     * @param cp the codepoint to classify
     * @return true when the codepoint is CJK in any of those blocks
     */
    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return true;
        }
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFF01 && cp <= 0xFF60);
    }
}
