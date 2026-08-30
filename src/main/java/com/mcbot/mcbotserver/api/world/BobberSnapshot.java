package com.mcbot.mcbotserver.api.world;

import com.mcbot.mcbotserver.api.types.Vec3;

/**
 * Read-only copy of a fishing bobber's model-relevant facts.
 *
 * <p>Contract: see boundaries.md section A; decision 17. Projectiles
 * are invisible to {@link EntitySnapshot}-shaped entity scans (those
 * see living entities only), so the bobber carries its own snapshot.
 * It deliberately carries NO bite flag: vanilla keeps the nibble
 * state private, and a bobber's bite is observable only from the
 * outside as a vertical dip - the consumer derives a bite from
 * consecutive snapshots' positions. Byte-fidelity note: a bobber
 * hooked onto a mob or item (a different state than a fish bite)
 * surfaces through {@code hookedEntity}.
 *
 * @param pos          exact bobber position; the bite watch needs
 *                     the sub-block y, a block-cell truncation
 *                     would hide half the bites (phase-dependent)
 * @param hookedEntity true when the bobber has reeled-into something
 *                     solid (a mob or item entity, not a fish bite)
 */
public record BobberSnapshot(Vec3 pos, boolean hookedEntity) {

    /**
     * Creates a validated snapshot.
     *
     * @param pos must not be null
     */
    public BobberSnapshot {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }
}
