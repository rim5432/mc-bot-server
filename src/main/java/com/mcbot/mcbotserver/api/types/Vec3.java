package com.mcbot.mcbotserver.api.types;

/**
 * Double-precision position/velocity vector. Exists because
 * block-granularity cells cannot express sub-cell motion - the stuck
 * fuse of a straight-line mover needs real displacement, not "which
 * cell am I in" (a slowly accelerating body stays in one cell for many
 * ticks while genuinely moving).
 *
 * <p>Contract: value semantics, immutable; pure data like every api
 * type.
 *
 * @param x double x
 * @param y double y
 * @param z double z
 */
public record Vec3(double x, double y, double z) {

    /**
     * Euclidean distance to another vector.
     *
     * @param other target; must not be null
     * @return straight-line distance
     */
    public double distanceTo(Vec3 other) {
        return distanceTo(other.x, other.y, other.z);
    }

    /**
     * Euclidean distance to the given coordinates. Overload for
     * cell-center arithmetic so callers need not build a throwaway
     * vector per check.
     *
     * @param targetX target x
     * @param targetY target y
     * @param targetZ target z
     * @return straight-line distance
     */
    public double distanceTo(double targetX, double targetY,
                             double targetZ) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
