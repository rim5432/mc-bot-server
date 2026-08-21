package com.mcbot.mcbotserver.api.actor;

/**
 * The four physical output channels of the body. Each tick each channel
 * resolves to exactly one winning claim.
 *
 * <p>Contract: see ADR-0004 D2. Channels are the whole write surface —
 * anything that mutates world state outside these is a boundary-A
 * violation and must be renamed to an obvious intent (requestDig, not
 * dig).
 */
public enum Channel {

    /** Locomotion: forward/strafe drive plus jump/sneak modifiers. */
    MOVE,

    /** Body rotation: absolute yaw/pitch target. */
    ROT,

    /** Attack / interact press. */
    USE,

    /** Hotbar selection. */
    SLOT
}
