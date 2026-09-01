package com.mcbot.mcbotserver.api.world;

import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * Read-only copy of one projectile's flight facts.
 *
 * <p>Contract: see boundaries.md section A; decision 17. Projectiles
 * are invisible to {@link EntitySnapshot}-shaped entity scans (those
 * see living entities only), so - exactly like the fishing bobber -
 * the flight family carries its own snapshot beside the living scan.
 * It deliberately carries NO type and NO owner: the combat consumer
 * needs only "where is it and where is it going"; origin attribution
 * stays unmodelled until a second consumer needs it.
 *
 * @param pos      exact projectile position; sub-block precision is
 *                 the point - a block-cell truncation would blur the
 *                 incoming-heading math by half a block per axis
 * @param velocity current per-tick displacement; a zero vector (a
 *                 spent arrow stuck in the ground) is representable
 *                 and self-filters in every speed-gated consumer
 */
public record ProjectileSnapshot(Vec3 pos, Vec3 velocity) {

    /**
     * Creates a validated snapshot.
     *
     * @param pos      must not be null
     * @param velocity must not be null
     */
    public ProjectileSnapshot {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (velocity == null) {
            throw new IllegalArgumentException("velocity must not be null");
        }
    }
}
