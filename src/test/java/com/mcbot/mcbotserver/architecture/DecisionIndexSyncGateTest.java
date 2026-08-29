package com.mcbot.mcbotserver.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Offline gate that keeps the decision index in
 * {@code doc/architecture/boundaries.md} in sync with the entry body in
 * {@code doc/architecture/ledger.md}. The boundaries index is the
 * repository-wide resolution point for every {@code decision N} citation
 * (AGENTS.md 0.3); the ledger body is where verdict text lives. A new
 * landing in the ledger body without a matching index row silently breaks
 * citation resolution — this gate fails before that drift reaches main.
 *
 * <p>Why a max-number equality check rather than a full set comparison: the
 * index and the body use different shapes (table rows vs. numbered prose,
 * with sub-entries like {@code 17a} / {@code 23a} that are deliberately
 * not top-level numbers). The invariant that matters for citation
 * resolution is that no top-level verdict lands in the body without an
 * index row — max equality catches every append-only gap, and the
 * append-only protocol (ledger.md admission) guarantees no gaps inside the
 * range once both ends agree.
 *
 * <p>Scope note: this gate complements {@code doc check}, which covers
 * front-matter and covers-drift but does not parse decision numbers. It is
 * the second insurance the ledger-split P4 follow-up ("wire doc check into
 * offline suite") was queued for.
 *
 * <p>Rule: AGENTS.md 0.3 (boundaries decision index resolves every
 * {@code decision N} citation); ledger.md admission protocol (append-only,
 * one numbered verdict per ruling); code-health.md H-R12 (this gate's
 * registry row).
 */
class DecisionIndexSyncGateTest {

    /**
     * Table-row pattern for the boundaries decision index: a pipe, a space,
     * one or more digits, a space, a pipe. Sub-entries
     * ({@code &nbsp;&nbsp;17a}) do not match because they carry the
     * non-breaking-space prefix and a letter suffix.
     */
    private static final Pattern BOUNDARY_INDEX_ROW = Pattern.compile("^\\| (\\d+) \\|");

    /**
     * Entry-line pattern for the ledger body: optional leading whitespace,
     * digits, a period, a space, at the start of the line. Entries 1-19
     * begin at column zero; entries 20+ are indented four spaces (a
     * formatting drift that predates this gate; the append-only protocol
     * forbids reformatting published entries). Sub-entries ({@code 19a.},
     * {@code 23a.}) and continuation lines do not match because a letter
     * or non-digit text follows the leading whitespace.
     */
    private static final Pattern LEDGER_ENTRY_LINE = Pattern.compile("^\\s*(\\d+)\\. ");

    /**
     * Fails when the highest decision number in the boundaries index differs
     * from the highest entry number in the ledger body.
     */
    @Test
    void boundaryIndexMaxMatchesLedgerEntryMax() throws IOException {
        Path root = RepoRoot.find();
        int boundaryMax = maxBoundaryIndex(root.resolve(Path.of("doc", "architecture", "boundaries.md")));
        int ledgerMax = maxLedgerEntry(root.resolve(Path.of("doc", "architecture", "ledger.md")));
        assertTrue(
                boundaryMax > 0,
                "boundaries.md decision index parsed zero rows — "
                        + "the section header or table format changed; update this gate.");
        assertTrue(
                ledgerMax > 0,
                "ledger.md entries section parsed zero rows — "
                        + "the section header or entry format changed; update this gate.");
        assertEquals(
                ledgerMax,
                boundaryMax,
                "decision index drift: boundaries.md index max=" + boundaryMax
                        + " but ledger.md body max=" + ledgerMax
                        + ". Every ledger body entry needs a matching row in the boundaries decision index"
                        + " (AGENTS.md 0.3). Add the missing rows to boundaries.md ## Decision ledger (binding)"
                        + " — do not renumber or remove ledger entries (append-only protocol).");
    }

    /**
     * Parse the highest top-level decision number from the boundaries
     * decision-index table. Only rows inside the {@code ## Decision ledger}
     * section are counted; the scan stops at the next {@code ## } header.
     *
     * @param boundaries path to boundaries.md
     * @return the highest decision number found, or 0 if the section is
     *         empty or absent
     * @throws IOException when the file cannot be read
     */
    private static int maxBoundaryIndex(Path boundaries) throws IOException {
        List<String> lines = Files.readAllLines(boundaries);
        int max = 0;
        boolean inIndex = false;
        for (String line : lines) {
            if (line.startsWith("## Decision ledger")) {
                inIndex = true;
                continue;
            }
            if (inIndex && line.startsWith("## ")) {
                break;
            }
            if (inIndex) {
                var matcher = BOUNDARY_INDEX_ROW.matcher(line);
                if (matcher.find()) {
                    max = Math.max(max, Integer.parseInt(matcher.group(1)));
                }
            }
        }
        return max;
    }

    /**
     * Parse the highest top-level entry number from the ledger body. Only
     * lines inside the {@code ## Entries} section are counted; sub-entries
     * ({@code 23a.}) and continuation lines are excluded by the pattern.
     *
     * @param ledger path to ledger.md
     * @return the highest entry number found, or 0 if the section is empty
     *         or absent
     * @throws IOException when the file cannot be read
     */
    private static int maxLedgerEntry(Path ledger) throws IOException {
        List<String> lines = Files.readAllLines(ledger);
        int max = 0;
        boolean inEntries = false;
        for (String line : lines) {
            if (line.startsWith("## Entries")) {
                inEntries = true;
                continue;
            }
            if (inEntries) {
                var matcher = LEDGER_ENTRY_LINE.matcher(line);
                if (matcher.find()) {
                    max = Math.max(max, Integer.parseInt(matcher.group(1)));
                }
            }
        }
        return max;
    }
}
