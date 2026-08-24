package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.IdleLook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idle head-tracking policy gate (issue 0005 P0): the constants and
 * the math the adapter glue applies when no behavior owns the ROT
 * channel. Pins selection (nearest within radius, stable ties),
 * angle frames (engine sign, one formula family with steering), and
 * the per-tick turn cap including the +-180 seam.
 */
class IdleLookGateTest {

    private static final Vec3 EYE = new Vec3(0, 64, 0);

    /**
     * Radius is inclusive and selection is nearest-wins: a candidate
     * 3 blocks out beats one at exactly 6 (the boundary itself
     * counts), regardless of list order.
     */
    @Test
    void nearestWithinRadiusWins() {
        Vec3 near = new Vec3(3, 64, 0);
        Vec3 far = new Vec3(6, 64, 0);

        IdleLook.Target t = IdleLook.nearestTarget(EYE,
            List.of(far, near));
        assertEquals(IdleLook.yawTo(EYE, near), t.yawDeg(), 1e-4,
            "nearest candidate must win regardless of list order");
    }

    /** Nothing within the radius yields null. */
    @Test
    void emptyOrDistantCandidatesYieldNull() {
        assertNull(IdleLook.nearestTarget(EYE, List.of()),
            "no candidates must yield null");
        assertNull(IdleLook.nearestTarget(EYE,
                List.of(new Vec3(20, 64, 0), new Vec3(0, 80, 0))),
            "candidates beyond the radius must be ignored");
    }

    /** Null entries are skipped without failing the scan. */
    @Test
    void nullCandidatesAreSkippedNotFatal() {
        assertNull(IdleLook.nearestTarget(EYE,
                java.util.Arrays.asList(null, new Vec3(50, 64, 0))),
            "null entries are skipped; a solely-distant list stays"
                + " null");
        IdleLook.Target t = IdleLook.nearestTarget(EYE,
            java.util.Arrays.asList(null, new Vec3(1, 64, 0)));
        assertEquals(IdleLook.yawTo(EYE, new Vec3(1, 64, 0)),
            t.yawDeg(), 1e-4,
            "a valid candidate after a null must still win");
    }

    /**
     * Engine sign convention, same formula family as steering: target
     * above reads negative pitch, below reads positive, and extremes
     * clamp at the idle limit.
     */
    @Test
    void pitchFollowsEngineSignAndClamps() {
        float up = IdleLook.pitchTo(EYE, new Vec3(1, 65.62, 0));
        assertTrue(up < 0, "target above must read negative pitch, got "
            + up);
        float down = IdleLook.pitchTo(EYE, new Vec3(1, 62.4, 0));
        assertTrue(down > 0, "target below must read positive pitch");
        assertEquals(-IdleLook.PITCH_LIMIT_DEG,
            IdleLook.pitchTo(EYE, new Vec3(0.1, 74, 0)), 0.01,
            "near-zenith target clamps at the up limit");
        assertEquals(IdleLook.PITCH_LIMIT_DEG,
            IdleLook.pitchTo(EYE, new Vec3(0.1, 54, 0)), 0.01,
            "near-nadir target clamps at the down limit");
    }

    /**
     * Yaw frame matches steering's atan2(-dx, dz): due +X (east)
     * reads -90, due +Z (south) reads 0.
     */
    @Test
    void yawMatchesSteeringConvention() {
        assertEquals(-90f, IdleLook.yawTo(EYE, new Vec3(5, 64, 0)),
            0.01, "east target must read yaw -90");
        assertEquals(0f, IdleLook.yawTo(EYE, new Vec3(0, 64, 5)),
            0.01, "south target must read yaw 0");
    }

    /**
     * Turn cap: small corrections complete in one tick; large ones
     * advance exactly TURN_PER_TICK_DEG along the shortest arc,
     * including across the +-180 seam (170 -> -170 is 20 degrees
     * apart, not 345). The exact-180 tie resolves toward + via
     * IEEEremainder's round-to-even - deterministic, which is the
     * design rule.
     */
    @Test
    void turnTowardYawCapsPerTickAndWraps() {
        assertEquals(40f, IdleLook.turnTowardYaw(42f, 40f), 1e-4,
            "a 2-degree correction completes in one tick");
        assertEquals(15f, IdleLook.turnTowardYaw(0f, 90f), 1e-4,
            "a 90-degree address advances exactly the cap");
        assertEquals(-175f, IdleLook.turnTowardYaw(170f, -170f), 1e-4,
            "20 degrees across the seam: +15 through 180, normalized");
        assertEquals(-155f, IdleLook.turnTowardYaw(-170f, 10f), 1e-4,
            "exact-180 tie takes the +cap; -170 + 15 = -155");
    }

    /** Pitch turn cap mirrors yaw without wrap semantics. */
    @Test
    void turnTowardPitchCapsPerTick() {
        assertEquals(15f, IdleLook.turnTowardPitch(0f, 40f), 1e-4,
            "pitch advances at most the cap per tick");
        assertEquals(5f, IdleLook.turnTowardPitch(0f, 5f), 1e-4,
            "small pitch corrections complete in one tick");
        assertEquals(-20f, IdleLook.turnTowardPitch(-5f, -30f), 1e-4,
            "a 25-degree downward correction advances one cap of -15");
    }
}
