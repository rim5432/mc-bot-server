package com.mcbot.mcbotserver.api.world;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Read-only copy of one block cell's identity. Pure data — carrying it
 * out of a {@link WorldView} must never have side effects.
 *
 * <p>Contract: see boundaries.md section A; decision 17 (primitive
 * families). Block ids let data-driven rule tables reference blocks
 * without engine types.
 *
 * @param pos     the cell this snapshot describes; never null
 * @param blockId id of the block; never null; two styles by design -
     *           real blocks use the registry key ({@code minecraft:stone}),
 *                ambient markers use the canonical constants {@link #AIR}
 *                and {@link #UNKNOWN}; never null - unknown is expressed
 *                by {@link #UNKNOWN}, not by null
 */
public record BlockSnapshot(CellPos pos, String blockId) {

    /** Block id used when the cell is outside any loaded chunk. */
    public static final String UNKNOWN = "?unknown";

    /**
     * Canonical (not registry-style) id for air cells. Adapters must
     * normalize the engine's {@code minecraft:air} to this constant -
     * the Stage 2 planner bug was exactly that mismatch.
     */
    public static final String AIR = "air";

    /**
     * Creates a validated snapshot.
     *
     * @param pos     must not be null
     * @param blockId must not be null or blank
     */
    public BlockSnapshot {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException(
                "blockId must not be blank");
        }
    }

    /**
     * Convenience predicate for the overwhelmingly common air check.
     *
     * @return true only for the exact {@link #AIR} id
     */
    public boolean isAir() {
        return AIR.equals(blockId);
    }

    /**
     * Convenience predicate for the unloaded/unknown case.
     *
     * @return true only for {@link #UNKNOWN}
     */
    public boolean isUnknown() {
        return UNKNOWN.equals(blockId);
    }
}
