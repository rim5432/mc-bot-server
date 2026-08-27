package com.mcbot.mcbotserver.api.types;

/**
 * The six cube faces, in the same ordinal order as
 * {@code net.minecraft.core.Direction} so the adapter's mapping is a
 * plain {@code Direction.values()[apiDir.ordinal()]} (or an explicit
 * switch — either is a one-liner). Exists as an api-layer type because
 * {@code api/} must not import {@code net.minecraft.*} (boundary A);
 * the adapter maps between this enum and the engine enum.
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 1 (InteractBlock carries a clicked face). The six values are
 * the only legal faces — a ray trace that hits a cube always lands on
 * one of them.
 *
 * <p>Axis convention (matches vanilla): +Y is up, +Z is south (toward
 * increasing Z), +X is east. NORTH is -Z, SOUTH is +Z, WEST is -X,
 * EAST is +X.
 */
public enum Direction {

    /** Y- face (the bottom of the cube). */
    DOWN(0, -1, 0),

    /** Y+ face (the top of the cube). */
    UP(0, 1, 0),

    /** Z- face. */
    NORTH(0, 0, -1),

    /** Z+ face. */
    SOUTH(0, 0, 1),

    /** X- face. */
    WEST(-1, 0, 0),

    /** X+ face. */
    EAST(1, 0, 0);

    private final int dx;
    private final int dy;
    private final int dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /**
     * The unit-step vector of this face, used to compute the adjacent
     * cell (e.g. the placement position is {@code target.relative(face)}).
     *
     * @return x component of the face normal
     */
    public int dx() {
        return dx;
    }

    /** @return y component of the face normal. */
    public int dy() {
        return dy;
    }

    /** @return z component of the face normal. */
    public int dz() {
        return dz;
    }

    /**
     * The cell adjacent to {@code origin} on this face. Equivalent to
     * {@code new CellPos(origin.x() + dx, origin.y() + dy, origin.z() + dz)}.
     *
     * @param origin the source cell; must not be null
     * @return the adjacent cell in this face's direction
     */
    public CellPos relative(CellPos origin) {
        return new CellPos(origin.x() + dx, origin.y() + dy, origin.z() + dz);
    }
}
