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

    /**
     * Attack / interact press, resolved on the USE channel. Aiming is
     * the ROT channel's job; this only carries the press semantics.
     *
     * @param pressing true to hold the action down this tick
     */
    record Use(boolean pressing) implements Intent {
    }

    /**
     * Hotbar selection, resolved on the SLOT channel.
     *
     * @param slot hotbar index 0..8
     */
    record SelectSlot(int slot) implements Intent {

        /**
         * Creates a validated selection.
         *
         * @param slot must be within 0..8 inclusive
         */
        public SelectSlot {
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException(
                    "slot must be in 0..8, got " + slot);
            }
        }
    }
}
