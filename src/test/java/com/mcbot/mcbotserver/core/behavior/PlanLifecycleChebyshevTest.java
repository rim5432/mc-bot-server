package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Chebyshev distance (the freshness metric) at the boundary cases,
 * called directly: this test lives in the same package as the
 * package-private helper it pins (code-health.md H-R1 -
 * collaborator-level tests are same-package, never reflective).
 *
 * <p>Why a direct unit test at all: the integration 2-cell-discard
 * case is non-deterministic (a discarded plan immediately triggers
 * a fresh plan that re-populates waypoints, so the only observable
 * difference is the discard's primitive effect, not the eventual
 * state). Boundary semantics: 1 = fresh (issue 0001 Ruling (c) -
 * Chebyshev-1 matches every BasicMoves move), 2 = stale.
 */
class PlanLifecycleChebyshevTest {

    @Test
    void chebyshevBoundaryCases() {
        // Same cell: 0.
        assertEquals(
                0,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(5, 64, 0)),
                "same cell has Chebyshev 0");

        // 1-cell offset on each axis independently: 1.
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(6, 64, 0)),
                "+X by 1: Chebyshev 1");
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(4, 64, 0)),
                "-X by 1: Chebyshev 1");
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(5, 65, 0)),
                "+Y by 1: Chebyshev 1");
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(5, 63, 0)),
                "-Y by 1: Chebyshev 1");
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(5, 64, 1)),
                "+Z by 1: Chebyshev 1");

        // Diagonal 1: max of 1, 1, 0 = 1. Still fresh.
        assertEquals(
                1,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(6, 65, 0)),
                "diagonal 1 (X+Y): Chebyshev 1, still fresh");

        // 2-cell offset: stale.
        assertEquals(
                2,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(7, 64, 0)),
                "+X by 2: Chebyshev 2, stale");
        assertEquals(
                2,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(5, 64, 2)),
                "+Z by 2: Chebyshev 2, stale");
        assertEquals(
                2,
                PlanLifecycle.chebyshevDistance(new CellPos(5, 64, 0), new CellPos(6, 66, 0)),
                "X+1 Y+2: Chebyshev 2, stale");
    }
}
