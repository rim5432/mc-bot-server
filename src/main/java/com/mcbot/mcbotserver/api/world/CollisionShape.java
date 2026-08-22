package com.mcbot.mcbotserver.api.world;

/**
 * Coarse collision classification of one block cell, plus the cell-local
 * box geometry needed to derive geometry-grounded movement predicates
 * (canStand, canPass). Stage 0 stub used only as an enum; Stage 2
 * carries the box.
 *
 * <p>Contract: see boundaries.md section A and decision 17 (primitive
 * families). The shape family is the source of truth for "can the body
 * pass through this cell" and "can the body stand on the cell below".
 * Non-geometric properties (climbable, liquid, damaging) live in
 * {@link BlockTraits} and are looked up by id, not derived from the
 * shape.
 *
 * <p>Why a box at all: the four cells around a diagonal corner and the
 * 1.8-tall body that sweeps through them need real coordinates, not
 * just an enum label, to answer "is the corner tight enough for the
 * head". A {@code PARTIAL} label that doesn't carry a box is a lie the
 * planner will eventually believe; the box is the discipline.
 *
 * <p>Coordinate convention: the box is in cell-local units, with
 * {@code (0,0,0)} at the cell's min corner and {@code (1,1,1)} at its
 * max. A full cube is {@code (0,0,0)-(1,1,1)}; a lower slab is
 * {@code (0,0,0)-(1,0.5,1)}; air is the empty box {@code (0,0,0)-(0,0,0)}.
 */
public record CollisionShape(Kind kind, Box box) {

    /** Coarse classes the executor reasons about. */
    public enum Kind {

        /** Nothing blocks the cell; a body may occupy it. */
        EMPTY,

        /** A full solid cube; walkable top, blocking sides. */
        FULL_CUBE,

        /** Anything in between (slabs, fences, open doors, fluids). */
        PARTIAL
    }

    /**
     * Cell-local axis-aligned box. All components in {@code [0,1]};
     * {@code min <= max} on every axis, otherwise the shape is invalid.
     *
     * @param minX lower X bound in cell-local units, {@code [0,1]}
     * @param minY lower Y bound in cell-local units, {@code [0,1]}
     * @param minZ lower Z bound in cell-local units, {@code [0,1]}
     * @param maxX upper X bound, {@code [minX,1]}
     * @param maxY upper Y bound, {@code [minY,1]}
     * @param maxZ upper Z bound, {@code [minZ,1]}
     */
    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {

        /**
         * Creates a validated cell-local box. Empty boxes (max equal
         * to min on every axis) are allowed and mean "no body
         * collides here".
         *
         * @throws IllegalArgumentException if any component is out of
         *                                  range or min exceeds max
         */
        public Box {
            if (minX < 0 || minY < 0 || minZ < 0
                || maxX > 1 || maxY > 1 || maxZ > 1) {
                throw new IllegalArgumentException(
                    "box bounds must be in [0,1]");
            }
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException(
                    "box min must be <= max on every axis");
            }
        }

        /**
         * The empty box (volume zero). Use for cells that hold no
         * body.
         *
         * @return the canonical empty box
         */
        public static Box empty() {
            return new Box(0, 0, 0, 0, 0, 0);
        }

        /**
         * The full cell box. Use for solid cubes and the bounding
         * shell of a partial block whose interior we don't need to
         * distinguish.
         *
         * @return the canonical full cell box
         */
        public static Box full() {
            return new Box(0, 0, 0, 1, 1, 1);
        }
    }

    /**
     * Creates a validated shape.
     *
     * @param kind must not be null
     * @param box  must not be null; the box's emptiness or fullness
     *             should agree with {@code kind} (EMPTY -> empty box,
     *             FULL_CUBE -> full cell box) but the constructor does
     *             not enforce that — the derived predicates answer
     *             from the box directly.
     */
    public CollisionShape {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (box == null) {
            throw new IllegalArgumentException("box must not be null");
        }
    }

    /**
     * Canonical shape for an air cell.
     *
     * @return EMPTY with an empty box
     */
    public static CollisionShape empty() {
        return new CollisionShape(Kind.EMPTY, Box.empty());
    }

    /**
     * Canonical shape for a full solid cube.
     *
     * @return FULL_CUBE with a full cell box
     */
    public static CollisionShape fullCube() {
        return new CollisionShape(Kind.FULL_CUBE, Box.full());
    }

    /**
     * Canonical shape for a partial block (slab, fence, stairs).
     *
     * @param box the cell-local box; must not be null
     * @return PARTIAL carrying the supplied box
     */
    public static CollisionShape partial(Box box) {
        return new CollisionShape(Kind.PARTIAL, box);
    }

    /**
     * Whether a body can pass through this cell. Derived from the
     * box: the body box ({@code stepHeight = 0.625} for a hop, the
     * 0.6-wide hitbox horizontal) can clear the cell if the top of
     * the box is below stepHeight. An EMPTY cell trivially passes.
     *
     * @return true if the cell is body-passable
     */
    public boolean passable() {
        return kind == Kind.EMPTY || box.maxY < STEP_HEIGHT;
    }

    /**
     * Whether a body can stand on the top of this cell. Derived from
     * the box: the cell's top must be at or above a comfortable
     * step-up height (a half-slab top at y=0.5 is the threshold).
     *
     * @return true if the cell's top can hold the body
     */
    public boolean walkableTop() {
        return kind == Kind.FULL_CUBE
            || (kind == Kind.PARTIAL
                && box.maxY >= STEP_HEIGHT
                && box.minY < STEP_HEIGHT);
    }

    /**
     * The step-up height the body can clear without a jump. Hard
     * constant for Stage 2 (matches the player step-up in MC 1.20.1);
     * lift to a config when vocabulary needs differ.
     */
    public static final double STEP_HEIGHT = 0.625;
}
