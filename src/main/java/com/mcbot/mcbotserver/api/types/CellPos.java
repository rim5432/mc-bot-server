package com.mcbot.mcbotserver.api.types;

/**
 * Integer grid position in a block cell. The bot's own coordinate
 * vocabulary — the pure-Java stand-in for engine-specific position
 * types, keeping api/ and core/ free of Minecraft imports.
 *
 * <p>Contract: see boundaries.md section A; decision 17 (WorldView
 * primitive families). Value semantics, immutable.
 *
 * @param x block-cell x
 * @param y block-cell y
 * @param z block-cell z
 */
public record CellPos(int x, int y, int z) {

    /**
     * Euclidean distance to another cell, in double precision.
     *
     * @param other the target cell; must not be null
     * @return straight-line distance between cell centers
     */
    public double distanceTo(CellPos other) {
        long dx = (long) x - other.x;
        long dy = (long) y - other.y;
        long dz = (long) z - other.z;
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }

    /**
     * Chebyshev distance to another cell — the "king move" metric used
     * by goal tolerance checks.
     *
     * @param other the target cell; must not be null
     * @return max of absolute axis deltas
     */
    public int chebyshevTo(CellPos other) {
        return Math.max(Math.abs(x - other.x), Math.max(Math.abs(y - other.y), Math.abs(z - other.z)));
    }
}
