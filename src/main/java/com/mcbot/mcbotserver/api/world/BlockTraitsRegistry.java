package com.mcbot.mcbotserver.api.world;

/**
 * Lookup table from block id (with state suffix when relevant) to
 * {@link BlockTraits}. The geometry is in {@link CollisionShape}; the
 * registry holds the properties the shape cannot say.
 *
 * <p>Contract: see boundaries.md decision 19. The key is state-aware:
 * {@code minecraft:stone_slab[type=bottom]} and
 * {@code minecraft:stone_slab[type=top]} are different keys, because
 * the damage model of the two halves is different (top-half slabs
 * are not standable by a full body in this scheme, and a top-half
 * slab's "is this a foothold" question has a different answer than
 * a bottom-half's). A registry whose keys are type-only would
 * collapse both halves into one entry and the first half-slab the
 * bot walks across would teach the planner the wrong lesson.
 *
 * <p>Why a registry and not a record per cell: the trait of "is
 * this a ladder" is identical for every ladder cell in every world;
 * the shape is per-cell but the trait is per-id. Putting the trait
 * in the cell's data would either duplicate it for every cell of the
 * same id or invent a per-cell id->trait map that already has a
 * natural name: the registry.
 *
 * <p>Implementation note: registries are built at startup and
 * immutable afterwards. The offline tests inject a small hand-rolled
 * registry; the datapack pipeline builds a much larger one from JSON.
 */
public interface BlockTraitsRegistry {

    /**
     * Look up the traits for a cell's block id and state. The state
     * suffix is optional — keys without a state suffix match any
     * state, keys with a state suffix match only that exact state.
     * The block id portion is mandatory.
     *
     * <p>Default contract: an unknown key returns
     * {@link BlockTraits#defaults()}. The bot never crashes on a
     * missing entry; it just gets the conservative
     * "no-special-property" answer and the missing entry is the
     * registry's problem to surface, not the planner's.
     *
     * @param blockId the cell's block id, possibly with
     *                {@code [key=value]} state suffix; must not be
     *                null or blank
     * @return the traits, never null; defaults when the key is
     *         unknown
     */
    BlockTraits traitsFor(String blockId);

    /**
     * Register a single mapping. Implementations are free to be
     * immutable after build; this method is for the builder and the
     * JSON loader, not the runtime.
     *
     * @param blockId the cell's block id, with optional
     *                {@code [key=value]} state suffix; must not be
     *                null or blank
     * @param traits  the traits to associate; must not be null
     * @return this registry, for fluent construction
     */
    BlockTraitsRegistry register(String blockId, BlockTraits traits);

    /**
     * Canonical empty registry: every key maps to
     * {@link BlockTraits#defaults()}. Useful for tests that only
     * care about geometry; production builds always replace this
     * with a loaded one.
     *
     * @return a registry whose only answer is the default
     */
    static BlockTraitsRegistry empty() {
        return new BlockTraitsRegistry() {
            @Override
            public BlockTraits traitsFor(String blockId) {
                return BlockTraits.defaults();
            }

            @Override
            public BlockTraitsRegistry register(String blockId, BlockTraits traits) {
                throw new UnsupportedOperationException("empty() is immutable; build your own");
            }
        };
    }
}
