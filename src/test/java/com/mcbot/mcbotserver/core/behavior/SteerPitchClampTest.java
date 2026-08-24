package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Steer-pitch clamp math at the extremes, called directly: this
 * test lives in the same package as the package-private helper it
 * pins (code-health.md H-R1 - collaborator-level tests are
 * same-package, never reflective).
 *
 * <p>Why a direct unit test instead of a driven plan: a waypoint
 * 3 up and 1 across reads -71.6 unclamped and must stop at -60;
 * the mirror case downward clamps at +60. Drops of 3 with the
 * waypoint one cell ahead exist in real plans, so these cases are
 * plan-reachable geometry, not fabricated shapes.
 */
class SteerPitchClampTest {

    @Test
    void steepWaypointsClampToLimit() {
        Vec3 position = new Vec3(0.5, 64, 0.5);
        assertEquals(-PathingBehavior.STEER_PITCH_LIMIT_DEG,
            PathingBehavior.steerPitch(position,
                new CellPos(1, 67, 0)), 0.01,
            "near-vertical ascent clamps at the up limit");
        assertEquals(PathingBehavior.STEER_PITCH_LIMIT_DEG,
            PathingBehavior.steerPitch(position,
                new CellPos(1, 61, 0)), 0.01,
            "near-vertical descent clamps at the down limit");
        assertEquals(0f,
            PathingBehavior.steerPitch(position,
                new CellPos(1, 64, 0)), 1e-6,
            "level waypoint reads exactly 0");
    }
}
