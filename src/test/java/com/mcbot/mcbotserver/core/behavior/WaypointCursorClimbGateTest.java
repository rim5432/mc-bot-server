package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Y-aware cursor advance gate for pure-vertical climb segments. The
 * XZ-only {@link WaypointCursor#advance} consumes a directly-overhead
 * waypoint on the first tick (horizontal ~0 <= WAYPOINT_REACH),
 * exhausting the cursor before the body has climbed a single rung;
 * {@link WaypointCursor#advanceClimb} consumes only on exact floor-cell
 * equality. Both halves of that contrast are pinned here.
 *
 * <p>Contract: see boundaries.md decision 8 (movement five-tuple) -
 * horizontal touch stays XZ-only, the vertical variant is Y-aware,
 * and the goal predicate remains the 3D arrival authority.
 */
class WaypointCursorClimbGateTest {

    private static final CellPos BASE = new CellPos(0, 64, 0);
    private static final CellPos RUNG_1 = new CellPos(0, 65, 0);
    private static final CellPos RUNG_2 = new CellPos(0, 66, 0);

    @Test
    void xzReachWouldExhaustButClimbAdvanceWaits() {
        // The regression this method exists to close: a body standing
        // at the ladder base, waypoint directly overhead. advance()
        // consumes it instantly (XZ distance ~0); advanceClimb() must
        // hold the cursor until the floor cell reaches the rung.
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(BASE, RUNG_1, RUNG_2));
        cursor.advance(new Vec3(0.5, 64.0, 0.5));
        assertTrue(cursor.exhausted(), "XZ-only reach must consume the overhead rungs - the old bug");
        assertEquals(RUNG_2, cursor.steerTarget());

        WaypointCursor climb = new WaypointCursor();
        climb.set(List.of(BASE, RUNG_1, RUNG_2));
        climb.advanceClimb(BASE);
        assertEquals(RUNG_1, climb.current(), "advanceClimb must not consume a rung above the floor");
    }

    @Test
    void rungConsumedOnlyWhenFloorEntersIt() {
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(BASE, RUNG_1, RUNG_2));
        // Climb through the first rung's Y band: only floor equality
        // consumes, so a mid-cell position (y ~ 65.4) reading floor
        // (0,65,0) consumes exactly that rung and stops.
        cursor.advanceClimb(RUNG_1);
        assertEquals(RUNG_2, cursor.current(), "one floor cell consumes exactly one rung");
        cursor.advanceClimb(RUNG_2);
        assertTrue(cursor.exhausted(), "reaching the top rung exhausts the vertical chain");
    }
}
