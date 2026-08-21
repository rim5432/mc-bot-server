package com.mcbot.mcbotserver.api.world;

/**
 * Coarse collision classification of one block cell. Stage 0 stub per
 * workplan — enough for straight-line movement and goal checks; finer
 * box geometry arrives with the Stage 2 move model.
 *
 * <p>Contract: see boundaries.md decision 17 (primitive families).
 *
 * @param kind coarse shape class; never null
 */
public record CollisionShape(Kind kind) {

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
     * Creates a validated shape.
     *
     * @param kind must not be null
     */
    public CollisionShape {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }
}
