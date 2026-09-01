package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.types.Vec3;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Idle head-tracking policy (issue 0005 P0): whom the body looks at
 * when no behavior owns the ROT channel this tick - the "device
 * becomes an actor" layer. The adapter feeds candidate eye positions
 * and applies the result; this class is pure math with no randomness,
 * so behaviour is deterministic and pinnable by tests.
 *
 * <p>Deliberately NOT a {@code Behavior}: boundary B freezes behaviors
 * to claim-free null-directive ticks, while idle presence is spectator
 * presentation, not mission semantics - the same adapter-local
 * placement class as the issue 0005 P3 fidget ruling. Keeping the
 * math here (zero MC imports) is what lets layer-1 tests pin the
 * constants; the adapter glue around it stays thin.
 *
 * <p>Implementation note: stateless; safe to call from the server
 * tick thread only, like everything in the pipeline.
 */
public final class IdleLook {

    /**
     * How close a player's eyes must be for the head to track them,
     * in metres (eye to eye). Issue 0005 P0 default.
     */
    public static final double PLAYER_LOOK_RADIUS = 6.0;

    /**
     * Maximum head turn per tick, in degrees. Instant snap rotation
     * is exactly the device-read the issue diagnoses; this cap turns
     * a 180-degree address into roughly half a second of visible
     * head motion.
     */
    public static final float TURN_PER_TICK_DEG = 15f;

    /**
     * Pitch clamp while tracking, in degrees, engine sign (negative =
     * up). A player directly above a stairwell should not pull the
     * view to -90; past this limit the glance stops climbing.
     */
    public static final float PITCH_LIMIT_DEG = 60f;

    /**
     * Idle ticks between sweep direction flips when no player is in
     * range (issue 0005 P2.3). Purely deterministic - the sweep is a
     * direction flip on a counter, no randomness.
     */
    public static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * How far left/right of the idle base yaw a sweep glance reaches,
     * in degrees (issue 0005 P2.3). The base is the yaw at the moment
     * idleness began, so sweeps stay anchored instead of drifting.
     */
    public static final float SWEEP_AMPLITUDE_DEG = 60f;

    /** Look angles toward one candidate; engine conventions. */
    public record Target(float yawDeg, float pitchDeg) {}

    private IdleLook() {}

    /**
     * Pick the track target: the nearest candidate within
     * {@link #PLAYER_LOOK_RADIUS}, ties resolving to the earlier
     * list entry so a static scene is frame-stable.
     *
     * @param eye            the tracker's eye position; never null
     * @param candidateEyes  candidate eye positions in stable order;
     *                       never null, may be empty or contain nulls
     *                       (skipped)
     * @return angles toward the nearest candidate, or {@code null}
     *         when none is within the radius
     */
    @Nullable
    public static Target nearestTarget(Vec3 eye, List<Vec3> candidateEyes) {
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (Vec3 candidate : candidateEyes) {
            if (candidate == null) {
                continue;
            }
            double dist = eye.distanceTo(candidate);
            if (dist <= PLAYER_LOOK_RADIUS && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        return new Target(yawTo(eye, best), pitchTo(eye, best));
    }

    /**
     * Yaw from one point toward another, engine convention (same
     * formula as {@code PathingBehavior} steering and
     * {@code CombatBehavior} aiming - one concept, one formula).
     *
     * @param from origin; never null
     * @param to   destination; never null
     * @return yaw in degrees, -180..180
     */
    public static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x() - from.x()), to.z() - from.z()));
    }

    /**
     * Pitch from one point toward another, engine sign (negative =
     * up), clamped to {@link #PITCH_LIMIT_DEG}.
     *
     * @param from origin; never null
     * @param to   destination; never null
     * @return pitch in degrees within
     *         {@code [-PITCH_LIMIT_DEG, +PITCH_LIMIT_DEG]}
     */
    public static float pitchTo(Vec3 from, Vec3 to) {
        double dy = to.y() - from.y();
        double horizontal = Math.hypot(to.x() - from.x(), to.z() - from.z());
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Math.max(-PITCH_LIMIT_DEG, Math.min(PITCH_LIMIT_DEG, pitch));
    }

    /**
     * Advance one tick of yaw toward the target along the shortest
     * arc, capped at {@link #TURN_PER_TICK_DEG}. Wrap-aware: a target
     * 20 degrees across the +-180 seam moves 15 degrees through the
     * seam, not 345 degrees the long way round.
     *
     * @param currentYaw current yaw in degrees
     * @param targetYaw  desired yaw in degrees
     * @return the next yaw, normalized to -180..180
     */
    public static float turnTowardYaw(float currentYaw, float targetYaw) {
        double delta = Math.IEEEremainder(targetYaw - currentYaw, 360.0);
        double step = Math.min(TURN_PER_TICK_DEG, Math.max(-TURN_PER_TICK_DEG, delta));
        return (float) Math.IEEEremainder(currentYaw + step, 360.0);
    }

    /**
     * Advance one tick of pitch toward the target, capped at
     * {@link #TURN_PER_TICK_DEG}. No wrap: pitch is a straight line,
     * not a circle.
     *
     * @param currentPitch current pitch in degrees
     * @param targetPitch  desired pitch in degrees
     * @return the next pitch
     */
    public static float turnTowardPitch(float currentPitch, float targetPitch) {
        double delta = targetPitch - currentPitch;
        double step = Math.min(TURN_PER_TICK_DEG, Math.max(-TURN_PER_TICK_DEG, delta));
        return (float) (currentPitch + step);
    }
}
