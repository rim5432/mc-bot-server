package com.mcbot.mcbotserver.api.world;

import com.mcbot.mcbotserver.api.types.CellPos;

import java.util.List;

/**
 * The read half of boundary A: pure perception queries with zero side
 * effects. Implementations must be mockable; nothing here mutates the
 * world and nothing here computes physics.
 *
 * <p>Contract: see boundaries.md section A and decisions 17/17a/17b.
 * Two properties are load-bearing:
 *
 * <ul>
 * <li>{@link #isLoaded} exists because "air" and "unloaded" are
 * different answers — a naive {@code getBlock(unloaded) == AIR} lies to
 * the planner and routes through ungenerated terrain (decision 17a).</li>
 * <li>The {@link ViewMode} parameter is reserved so the Stage 2
 * off-thread planner needs no interface change (decision 17b); snapshot
 * lifecycle is the caller's concern, not this interface's.</li>
 * </ul>
 *
 * <p>Implementation note: all methods run on the server tick thread in
 * LIVE mode; SNAPSHOT views are captured once and read anywhere.
 */
public interface WorldView {

    /**
     * Identity of one block cell.
     *
     * @param pos  the cell to read; must not be null
     * @param mode consistency requested, per decision 17b
     * @return the cell's snapshot; {@code null} when the cell's chunk
     *         is not loaded — callers must treat that as "unknown",
     *         never as air
     */
    BlockSnapshot getBlock(CellPos pos, ViewMode mode);

    /**
     * Cell-local box geometry of one block cell. The shape is the
     * source of truth for "can a body pass / can a body stand" -
     * planners derive their viability from this and only this, never
     * from the block id (decision 19).
     *
     * <p>Default contract: when the cell is unknown / unloaded /
     * the implementation does not carry geometry, return
     * {@link CollisionShape#fullCube()} — a conservative "blocking"
     * answer that fails the planner's viability check rather than
     * letting it walk through unrendered terrain. Implementations
     * that have real geometry MUST override.
     *
     * @param pos  the cell to read; must not be null
     * @param mode consistency requested, per decision 17b
     * @return the cell's collision shape; never null
     */
    default CollisionShape getCollisionShape(CellPos pos, ViewMode mode) {
        return CollisionShape.fullCube();
    }

    /**
     * Non-geometric traits (climbable, liquid, damaging) of one block
     * cell. The shape cannot say "is this a ladder"; the registry
     * can. Production builds thread a {@link BlockTraitsRegistry}
     * into their adapter; the default here is the empty registry.
     *
     * @param pos  the cell to read; must not be null
     * @param mode consistency requested, per decision 17b
     * @return the cell's traits; never null
     */
    default BlockTraits getBlockTraits(CellPos pos, ViewMode mode) {
        return BlockTraits.defaults();
    }

    /**
     * All entities within a spherical range of a cell center.
     *
     * @param center search origin; must not be null
     * @param radius search radius in blocks; positive
     * @param mode   consistency requested, per decision 17b
     * @return matching snapshots, unordered; never null, possibly empty
     */
    List<EntitySnapshot> getEntities(CellPos center, double radius,
                                     ViewMode mode);

    /**
     * Whether the chunk containing the cell is loaded — the first-class
     * "do I know this?" query (decision 17a).
     *
     * @param pos the cell to probe; must not be null
     * @return true only when a read would return real data rather than
     *         unknown
     */
    boolean isLoaded(CellPos pos);
}
