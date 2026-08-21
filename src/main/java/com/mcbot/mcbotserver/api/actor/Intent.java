package com.mcbot.mcbotserver.api.actor;

/**
 * The closed vocabulary of physical intents. Values are pure data — an
 * intent never computes physics; the vanilla engine is the only thing
 * that moves the body.
 *
 * <p>Contract: see boundaries.md section A (Actor is intents-only) and
 * numen-notes.md section 4 (InputDriver: the smallest possible input
 * emulation). Sealed so the adapter's exhaustive dispatch stays honest;
 * new channels mean editing this file, not casting from Object.
 */
public sealed interface Intent {

    /**
     * Locomotion drive, resolved on the MOVE channel.
     *
     * @param forward -1..1, +1 = full ahead
     * @param strafe  -1..1, +1 = full left per engine convention
     * @param jump    true to request a jump this tick
     * @param sneak   true to hold the sneak modifier
     */
    record Move(double forward, double strafe,
                boolean jump, boolean sneak) implements Intent {
    }

    /**
     * Absolute rotation target, resolved on the ROT channel.
     *
     * @param yawDeg   target body yaw in degrees
     * @param pitchDeg target view pitch in degrees
     */
    record Look(float yawDeg, float pitchDeg) implements Intent {
    }
}
