package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayList;
import java.util.List;

/**
 * Waypoint simplification applied to an adopted plan before the
 * cursor installs it (issue 0005 P1.2/P1.3): grid A* emits one
 * waypoint per cell, which reads as right-angle zigzag with a stop
 * at every cell centre. Two merge rules run in order:
 *
 * <ol>
 * <li>Collinear-run merge: consecutive cells with the SAME step
 * vector collapse to their endpoints. Pure list operation - every
 * collapsed edge was A*-validated, so no world read is needed.
 * Covers flat runs, uniform staircases (the P1.3 payoff: steering
 * sees one far-above waypoint and holds jump through the whole
 * climb - continuous hop cadence instead of per-step waits), and
 * uniform drop runs.</li>
 * <li>Line-of-sight merge: on segments that stay at one Y, greedily
 * skip to the farthest waypoint whose connecting corridor is
 * standable end to end (sampled every half block). Removes zigzag
 * corners on open ground. Vertical segments are rule-1-only: the
 * corridor check has no terrain-following Y logic.</li>
 * </ol>
 *
 * <p>Ledger 20 interaction (boundaries.md decision ledger entry 20):
 * this is the first consumer that makes waypoint gaps exceed one
 * cell, which is the entry's named hard re-test trigger. Two
 * downstream semantics were revisited in the same change:
 * off-path drift now measures distance to the WALKED SEGMENT
 * (previous to current waypoint), and the progress fuse resets its
 * criterion-3 latch when the cursor advances - the old latched
 * minimum is meaningless against a new, farther waypoint. Criterion
 * 3 itself stays waypoint-distance (not arclength) because merged
 * segments are straight, so the distance to the current waypoint
 * decreases monotonically while the segment is walked.
 *
 * <p>Implementation note: pure function of the plan and the world
 * reads; runs on the tick thread at adoption time.
 */
// contract: see boundaries.md decision ledger 20 (hard re-test
// trigger) and issues/0005-player-feel-motion-layer.md P1
public final class PlanSmoother {

    /**
     * Corridor sampling step, in blocks. Half a block keeps the
     * sampled cells adjacent along diagonal lines tight enough that
     * a blocking cell cannot sit between two samples unnoticed.
     */
    public static final double SAMPLE_STEP = 0.5;

    private PlanSmoother() {}

    /**
     * Simplify an adopted waypoint chain. The first and last cells
     * are always preserved; interior cells survive only where a
     * turn or an unmergeable vertical change forces them.
     *
     * @param world the live view the corridor checks read; never null
     * @param plan  the adopted chain (index 0 = the cell the search
     *              started from); never null, treated as read-only
     * @return a new simplified chain; never null, never longer than
     *         the input
     */
    public static List<CellPos> smooth(WorldView world, List<CellPos> plan) {
        if (plan.size() <= 2) {
            return plan;
        }
        List<CellPos> runs = collapseRuns(plan);
        return mergeVisible(world, runs);
    }

    private static List<CellPos> collapseRuns(List<CellPos> plan) {
        List<CellPos> out = new ArrayList<>();
        out.add(plan.get(0));
        int i = 0;
        while (i < plan.size() - 1) {
            CellPos a = plan.get(i);
            CellPos b = plan.get(i + 1);
            int dx = b.x() - a.x();
            int dy = b.y() - a.y();
            int dz = b.z() - a.z();
            int j = i + 1;
            while (j + 1 < plan.size()) {
                CellPos cur = plan.get(j);
                CellPos next = plan.get(j + 1);
                if (next.x() - cur.x() != dx || next.y() - cur.y() != dy || next.z() - cur.z() != dz) {
                    break;
                }
                j++;
            }
            out.add(plan.get(j));
            i = j;
        }
        return out;
    }

    private static List<CellPos> mergeVisible(WorldView world, List<CellPos> runs) {
        List<CellPos> out = new ArrayList<>();
        out.add(runs.get(0));
        int i = 0;
        while (i < runs.size() - 1) {
            int j = runs.size() - 1;
            while (j > i + 1
                    && (runs.get(i).y() != runs.get(j).y() || !corridorStandable(world, runs.get(i), runs.get(j)))) {
                j--;
            }
            out.add(runs.get(j));
            i = j;
        }
        return out;
    }

    /**
     * Whether the straight corridor between two same-Y cells is
     * standable end to end, sampled every {@link #SAMPLE_STEP}
     * blocks between the cell centres.
     *
     * <p>ponytail: point-sampled corridor, no body-width inflation -
     * a merged segment only replaces an A*-validated chain that
     * roughly follows the same line, so corner clipping is bounded;
     * inflate the samples if a gametest ever shows a hip catch.
     *
     * @param world the view to read; never null
     * @param from  corridor start; never null
     * @param to    corridor end, same Y as {@code from}; never null
     * @return true when every sample stands on a walkable floor with
     *         headroom
     */
    static boolean corridorStandable(WorldView world, CellPos from, CellPos to) {
        double x0 = from.x() + 0.5;
        double z0 = from.z() + 0.5;
        double x1 = to.x() + 0.5;
        double z1 = to.z() + 0.5;
        double dist = Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) Math.ceil(dist / SAMPLE_STEP));
        for (int k = 1; k < steps; k++) {
            double t = (double) k / steps;
            CellPos sample =
                    new CellPos((int) Math.floor(x0 + (x1 - x0) * t), from.y(), (int) Math.floor(z0 + (z1 - z0) * t));
            if (!BasicMoves.standable(world, sample)) {
                return false;
            }
        }
        return true;
    }
}
