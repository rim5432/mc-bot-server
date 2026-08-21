package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scriptable WorldView for offline tests: a plain map of blocks, a list
 * of entities, and an explicit unloaded set. Defaults to "everything
 * loaded" so simple tests stay one-liners.
 *
 * <p>Contract: see boundaries.md section A — this mock is the reason
 * core logic can be tested without a Forge runtime; it must stay as
 * boring as the interface promises.
 *
 * <p>Implementation note: not thread-safe; tests drive it directly.
 */
public final class MockWorldView implements WorldView {

    private final Map<CellPos, BlockSnapshot> blocks = new HashMap<>();
    private final List<EntitySnapshot> entities = new ArrayList<>();
    private final Set<CellPos> unloaded = new HashSet<>();

    /**
     * Place or replace a block cell.
     *
     * @param snapshot the cell content; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView putBlock(BlockSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        blocks.put(snapshot.pos(), snapshot);
        return this;
    }

    /**
     * Convenience overload for air cells.
     *
     * @param pos the cell to make air; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView putAir(CellPos pos) {
        return putBlock(new BlockSnapshot(pos, BlockSnapshot.AIR));
    }

    /**
     * Add an entity to the visible set.
     *
     * @param snapshot the entity; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView addEntity(EntitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        entities.add(snapshot);
        return this;
    }

    /**
     * Mark a cell's chunk as unloaded; reads there return unknown/null.
     *
     * @param pos any cell inside the unloaded chunk; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView markUnloaded(CellPos pos) {
        unloaded.add(pos);
        return this;
    }

    @Override
    public BlockSnapshot getBlock(CellPos pos, ViewMode mode) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (unloaded.contains(pos)) {
            return null;
        }
        BlockSnapshot snapshot = blocks.get(pos);
        return snapshot != null ? snapshot
            : new BlockSnapshot(pos, BlockSnapshot.AIR);
    }

    @Override
    public List<EntitySnapshot> getEntities(CellPos center, double radius,
                                            ViewMode mode) {
        if (center == null) {
            throw new IllegalArgumentException("center must not be null");
        }
        List<EntitySnapshot> hits = new ArrayList<>();
        for (EntitySnapshot e : entities) {
            if (e.pos().distanceTo(center) <= radius) {
                hits.add(e);
            }
        }
        return hits;
    }

    @Override
    public boolean isLoaded(CellPos pos) {
        return pos != null && !unloaded.contains(pos);
    }
}
