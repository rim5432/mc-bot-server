package com.mcbot.mcbotserver.api.actor;

/**
 * The physical output channels of the body. Each tick each channel
 * resolves to exactly one winning claim.
 *
 * <p>Contract: see ADR-0004 D2 as grown by ledger 25 (issue 0009).
 * Channels are the whole write surface — anything that mutates world
 * state outside these is a boundary-A violation and must be renamed
 * to an obvious intent (requestDig, not dig). Vocabulary grew once
 * beyond ADR-0004's four channels: INTERACT carries held block
 * interaction (dig today; the 0007 review adds use/place shapes
 * there, USE stays entity-melee).
 */
public enum Channel {

    /** Locomotion: forward/strafe drive plus jump/sneak modifiers. */
    MOVE,

    /** Body rotation: absolute yaw/pitch target. */
    ROT,

    /** Attack / interact press. */
    USE,

    /** Hotbar selection. */
    SLOT,

    /**
     * Held block interaction: dig today. A claim this tick means the
     * interaction button is held at the claimed cell; multi-tick
     * progress (destroy stages) accumulates adapter-side, exactly the
     * way vanilla accumulates it in ServerPlayerGameMode - the claim
     * stays a per-tick declarative statement.
     */
    INTERACT
}
