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
    void xzReachConsumesJumpUpButStopsAtVerticalSegment() {
        // The 0017 fix: advance() must NOT consume a waypoint more than
        // one block above the floor. A y+1 JumpUp rung is still
        // consumed (the body is mid-jump and reaches that Y); a y+2
        // (or higher) vertical segment is held for advanceClimb.
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(BASE, RUNG_1, RUNG_2));
        cursor.advance(new Vec3(0.5, 64.0, 0.5));
        // BASE (same cell) + RUNG_1 (y+1, horizontal 0) consumed;
        // RUNG_2 (y+2) held by the vertical guard.
        assertEquals(2, cursor.index(), "y+1 consumed, y+2 held");
        assertEquals(RUNG_2, cursor.current(), "the vertical guard stops at the first high waypoint");

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

    /**
     * Issue 0017 regression: the smoothed ladder plan collapses the
     * 5-cell climb into a single waypoint at the top. A bot standing
     * one cell west of the ladder base is within 0.8 m XZ of that top
     * waypoint but 5 m below it; advance() must NOT consume the climb
     * leg on horizontal reach alone, or the bot skips the entire
     * ascent and oscillates under the platform until TIMEOUT.
     */
    @Test
    void smoothedLadderTopWaypointNotConsumedOneCellWest() {
        CellPos start = new CellPos(4, 1, 8);
        CellPos ladderBase = new CellPos(9, 1, 8);
        CellPos ladderTop = new CellPos(9, 6, 8);
        CellPos platformGoal = new CellPos(6, 6, 8);
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(start, ladderBase, ladderTop, platformGoal));

        // Bot at x=8.8 (floor cell 8, one west of the ladder), within
        // WAYPOINT_REACH of the ladderBase waypoint's XZ centre (9.5).
        // 8.8 (not 8.7) avoids the IEEE-754 0.8 boundary.
        Vec3 oneCellWest = new Vec3(8.8, 1.0, 8.5);
        cursor.advance(oneCellWest);

        // ladderBase consumed (same Y, horizontal <= 0.8); ladderTop
        // must be held by the vertical guard (y=6 > floor.y+1=2).
        assertEquals(
                ladderTop,
                cursor.current(),
                "the merged climb top must not be consumed from one cell west of the ladder");
        assertEquals(2, cursor.index(), "cursor stops at the climb top, not the platform waypoint");

        // Once the body steps into the ladder column, isVerticalClimb
        // (in PathingBehavior) routes to advanceClimb, which holds the
        // top until the body actually reaches y=6.
        cursor.advanceClimb(new CellPos(9, 1, 8));
        assertEquals(ladderTop, cursor.current(), "advanceClimb holds the top through the lower rungs");
        cursor.advanceClimb(new CellPos(9, 5, 8));
        assertEquals(ladderTop, cursor.current(), "advanceClimb still holds one rung below the top");
        cursor.advanceClimb(new CellPos(9, 6, 8));
        assertEquals(platformGoal, cursor.current(), "reaching the top rung consumes the climb leg");
    }
}
