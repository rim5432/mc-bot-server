package com.mcbot.mcbotserver.api.world;

/**
 * Non-geometric properties of a block cell. The geometry (can a body
 * pass / can a body stand) is derived from {@link CollisionShape} by
 * mechanism; the traits here are the things the shape cannot say.
 *
 * <p>Contract: see boundaries.md section A and the
 * shape-vs-traits discipline (decision 19). The split exists because
 * "is the top half of this cell solid?" is geometry and is true for
 * every block with a high top box, but "is this ladder climbable?" is
 * a property the shape cannot infer — a ladder's collision box is
 * empty above the rung, but the body can grab the rung and climb.
 * Putting climbable in the geometry registry would force a 2x2 entry
 * per ladder variant; putting it in traits is one entry per ladder.
 *
 * <p>Default block: the {@link #defaults()} factory returns the
 * conservative "no special property" record. An unknown id gets
 * defaults; that is the right answer for "we don't know, do not
 * engage the special behaviour".
 *
 * @param climbable true when the body can ascend/descend this cell
 *                  via a vertical channel (ladders, vines, scaffold)
 * @param liquid    true when the cell holds a fluid the body can
 *                  swim through (water, lava — lava is also
 *                  {@code damaging})
 * @param damaging  true when standing in or touching this cell hurts
 *                  the body (lava, fire, sweet berry bush, cactus)
 */
public record BlockTraits(boolean climbable, boolean liquid, boolean damaging) {

    /** The "no special property" record; the safe default. */
    public static final BlockTraits NONE = new BlockTraits(false, false, false);

    /**
     * Convenience factory for the safe default.
     *
     * @return NONE
     */
    public static BlockTraits defaults() {
        return NONE;
    }

    /**
     * Ladder-style cell: vertical channel the body can climb. Not a
     * liquid; not damaging.
     *
     * @return the climbable-only trait
     */
    public static BlockTraits climbableOnly() {
        return new BlockTraits(true, false, false);
    }

    /**
     * Water cell: body can swim through, not damaging.
     *
     * @return the liquid-only trait
     */
    public static BlockTraits liquidOnly() {
        return new BlockTraits(false, true, false);
    }

    /**
     * Lava / fire / cactus / sweet berry: body takes damage on touch.
     * Lava is also a liquid; the {@link #dangerousLiquid()} factory
     * covers that.
     *
     * @return the damaging-only trait
     */
    public static BlockTraits damagingOnly() {
        return new BlockTraits(false, false, true);
    }

    /**
     * Lava cell: liquid AND damaging.
     *
     * @return the combined dangerous-liquid trait
     */
    public static BlockTraits dangerousLiquid() {
        return new BlockTraits(false, true, true);
    }
}
