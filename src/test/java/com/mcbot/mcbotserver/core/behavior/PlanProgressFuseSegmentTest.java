package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Criterion-3 latch reset on waypoint advance (ledger 20 re-test,
 * triggered by issue 0005 P1.2 smoothing): the latched 3D-distance
 * minimum belongs to the waypoint it was measured against. On a
 * smoothed plan the next waypoint starts much farther away, and a
 * stale latch would silence criterion 3 for a whole segment - on a
 * detour leg that also opens the goal distance, a moving body would
 * false-STUCK.
 *
 * <p>Same-package collaborator test (H-R1): direct construction, no
 * reflection.
 */
class PlanProgressFuseSegmentTest {

    /**
     * After the cursor advances, the next step toward the new
     * waypoint must fire criterion 3 again. The anchor is placed
     * orthogonal to the walk so criterion 2 stays silent by more
     * than STUCK_EPSILON and the assertion isolates criterion 3.
     */
    @Test
    void waypointAdvanceResetsCriterion3Latch() {
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(new CellPos(0, 64, 0), new CellPos(1, 64, 0), new CellPos(9, 64, 0)));
        PlanProgressFuse fuse = new PlanProgressFuse();
        fuse.onAdopted(cursor.index());

        CellPos anchor = new CellPos(1, 64, 99);
        // First observation: criterion 3 fires against the sentinel
        // and latches 1.0 m to waypoint (1, 64, 0).
        assertTrue(fuse.evaluate(cursor, new Vec3(0.5, 64, 0.5), anchor));

        // Reach the corner: index advances to the far waypoint.
        Vec3 atCorner = new Vec3(1.5, 64, 0.5);
        cursor.advance(atCorner);
        assertTrue(fuse.evaluate(cursor, atCorner, anchor), "index advance is progress");

        // One step toward the new waypoint (8 m away): only criterion
        // 3 can fire here, and only if the advance reset its latch -
        // the stale 1.0 m minimum would otherwise silence it until
        // the body is inside 1 m of the new waypoint.
        assertTrue(
                fuse.evaluate(cursor, new Vec3(1.6, 64, 0.5), anchor),
                "criterion 3 must re-arm when the cursor advances");
    }
}
