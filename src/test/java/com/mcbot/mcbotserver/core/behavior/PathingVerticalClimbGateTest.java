package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.CollisionShape.Box;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vertical-climb detection gate: {@code PathingBehavior.isVerticalClimb}
 * is the single predicate that routes cursor advance and steer yaw
 * through the climb overrides. Its three conjuncts (same XZ, strictly
 * higher waypoint, climbable floor cell) each have a false case here -
 * a too-greedy predicate steers a walking body into walls, a
 * too-strict one drops the yaw override mid-climb.
 *
 * <p>Contract: see boundaries.md decision 19 (trait-driven climb) -
 * climbability is read from BlockTraits, never from the shape.
 */
class PathingVerticalClimbGateTest {

    private static final CellPos LADDER_FLOOR = new CellPos(0, 65, 0);
    private static final CellPos OVERHEAD = new CellPos(0, 69, 0);

    /**
     * A floor cell carrying the climbable trait (plate shape, as the
     * adapter reads a real ladder facing east) with air above.
     */
    private static MockWorldView ladderFloor() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(LADDER_FLOOR, "minecraft:ladder"));
        w.putTraits(LADDER_FLOOR, BlockTraits.climbableOnly());
        w.putShape(LADDER_FLOOR, CollisionShape.partial(new Box(0.875, 0, 0, 1, 1, 1)));
        w.putBlock(new BlockSnapshot(OVERHEAD, "minecraft:air"));
        w.putShape(OVERHEAD, CollisionShape.empty());
        return w;
    }

    @Test
    void overheadWaypointOverClimbableFloorIsAClimb() {
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(LADDER_FLOOR, OVERHEAD));
        assertTrue(
                PathingBehavior.isVerticalClimb(ladderFloor(), LADDER_FLOOR, cursor),
                "same XZ, higher waypoint, climbable floor: the climb override applies");
    }

    @Test
    void plainFloorNeverTriggersTheClimbOverride() {
        // Same geometry, but the floor cell has no climbable trait:
        // the yaw must derive from the waypoint, not a wall.
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(LADDER_FLOOR, "minecraft:stone"));
        w.putShape(LADDER_FLOOR, CollisionShape.fullCube());
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(LADDER_FLOOR, OVERHEAD));
        assertFalse(PathingBehavior.isVerticalClimb(w, LADDER_FLOOR, cursor));
    }

    @Test
    void horizontalAndDownwardWaypointsKeepTheXzContract() {
        MockWorldView w = ladderFloor();
        // Walk-in leg: body beside the ladder, waypoint at the base,
        // same Y - still a walk, the yaw must aim at the cell centre.
        CellPos beside = new CellPos(1, 65, 0);
        WaypointCursor walkIn = new WaypointCursor();
        walkIn.set(List.of(beside, LADDER_FLOOR));
        assertFalse(PathingBehavior.isVerticalClimb(w, beside, walkIn), "a same-Y waypoint is a walk, not a climb");

        // Downward waypoint from a climbable floor (ladder descent):
        // the XZ-only advance contract applies, no wall-yaw override.
        WaypointCursor descent = new WaypointCursor();
        descent.set(List.of(LADDER_FLOOR, new CellPos(0, 61, 0)));
        assertFalse(
                PathingBehavior.isVerticalClimb(w, LADDER_FLOOR, descent),
                "a waypoint below the floor is a descent, not a climb");
    }

    @Test
    void exhaustedCursorHasNoClimb() {
        // After the top rung is consumed the cursor is exhausted;
        // steerTarget clamps to the last cell but the override must be
        // off so the exit leg steers toward the platform, not the wall.
        WaypointCursor cursor = new WaypointCursor();
        cursor.set(List.of(LADDER_FLOOR, OVERHEAD));
        cursor.advanceClimb(OVERHEAD);
        assertTrue(cursor.exhausted());
        assertFalse(PathingBehavior.isVerticalClimb(ladderFloor(), OVERHEAD, cursor));
    }
}
