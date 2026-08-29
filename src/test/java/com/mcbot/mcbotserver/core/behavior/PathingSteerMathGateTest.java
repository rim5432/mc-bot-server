package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Math gate for PathingBehavior's off-path drift metric
 * ({@code distanceToSegment}): the XZ-only point-to-segment distance
 * with endpoint clamping. Every arithmetic mutant in this method
 * produces a wrong distance on at least one of these probes - the
 * metric decides REPLAN_DISTANCE breaches, so a silent wrong value
 * means either constant replanning or never noticing drift.
 *
 * <p>Same-package test (H-R1): package-private access, no
 * reflection.
 */
class PathingSteerMathGateTest {

    private static final CellPos A = new CellPos(0, 64, 0);
    private static final CellPos B = new CellPos(10, 64, 0);

    @Test
    void onSegmentReadsZero() {
        // Midpoint and quarter-point of the segment sit ON it.
        Vec3 mid = new Vec3(5.5, 64.0, 0.5);
        assertEquals(0.0, PathingBehavior.distanceToSegment(mid, A, B), 1e-9, "midpoint distance must be 0");
        Vec3 quarter = new Vec3(2.5, 64.0, 0.5);
        assertEquals(0.0, PathingBehavior.distanceToSegment(quarter, A, B), 1e-9, "quarter distance must be 0");
    }

    @Test
    void perpendicularDistanceIsExact() {
        // 2 blocks off the segment, projected between the ends.
        Vec3 off = new Vec3(5.5, 64.0, 2.5);
        assertEquals(2.0, PathingBehavior.distanceToSegment(off, A, B), 1e-9, "perpendicular distance");
        // The Y coordinate is intentionally ignored (XZ-only metric).
        Vec3 above = new Vec3(5.5, 80.0, 2.5);
        assertEquals(2.0, PathingBehavior.distanceToSegment(above, A, B), 1e-9, "Y must be ignored");
    }

    @Test
    void projectionClampsToSegmentEnds() {
        // Beyond the B end: distance to B's center, not to the
        // infinite line.
        Vec3 pastB = new Vec3(20.5, 64.0, 0.5);
        assertEquals(
                Math.hypot(10.0, 0.0),
                PathingBehavior.distanceToSegment(pastB, A, B),
                1e-9,
                "past-B distance clamps to B");
        // Before the A end likewise.
        Vec3 beforeA = new Vec3(-3.5, 64.0, 4.5);
        assertEquals(
                Math.hypot(4.0, 4.0),
                PathingBehavior.distanceToSegment(beforeA, A, B),
                1e-9,
                "before-A distance clamps to A");
    }

    @Test
    void degenerateSegmentMeasuresPointDistance() {
        // from == to: lenSq == 0 branch, distance to the single point.
        Vec3 pos = new Vec3(3.5, 64.0, 4.5);
        assertEquals(
                Math.hypot(3.0, 4.0),
                PathingBehavior.distanceToSegment(pos, A, A),
                1e-9,
                "degenerate segment measures point distance");
        assertTrue(PathingBehavior.distanceToSegment(new Vec3(0.5, 64.0, 0.5), A, A) < 1e-9);
    }

    @Test
    void diagonalOffsetDistancesDistinguishAxes() {
        // (3,0,4)-style offset: a swapped dx/dz or a squared-instead-of
        // hypot mutant produces a different value here.
        Vec3 pos = new Vec3(5.5, 64.0, 5.0);
        assertEquals(4.5, PathingBehavior.distanceToSegment(pos, A, B), 1e-9, "vertical offset 4.5");
    }
}
