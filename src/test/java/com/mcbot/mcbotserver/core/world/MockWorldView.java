package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import com.mcbot.mcbotserver.api.world.CollisionShape;
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
    // Per-cell shape overrides: tests that care about partial blocks
    // (slabs, fences, stairs) call putShape() to inject the box.
    // Cells without an explicit shape fall back to the blockId-based
    // default: air -> EMPTY, anything else -> FULL_CUBE. That keeps
    // the existing Stage-0 tests honest without forcing every test
    // to spell out the geometry.
    private final Map<CellPos, CollisionShape> shapes = new HashMap<>();
    // Per-cell trait overrides and a fallback registry. Tests that
    // need a specific climbable / damaging / liquid cell inject
    // either a registry (covers many cells) or a single override
    // (one cell). The fallback for an unknown id is the registry's
    // answer, which is the empty registry's default by default.
    private final Map<CellPos, BlockTraits> traitOverrides = new HashMap<>();
    private BlockTraitsRegistry traitsRegistry = BlockTraitsRegistry.empty();
    /** Scriptable inventory for tests that exercise item sense. */
    private InventoryView inventory = InventoryView.empty();

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
     * Remove every entity with the given id - the offline stand-in for
     * death and despawning.
     *
     * @param id entity identity; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView removeEntity(String id) {
        entities.removeIf(e -> e.id().equals(id));
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

    /**
     * Inject a cell-local shape for a position. Overrides the
     * blockId-based default; tests that exercise slabs, fences, or
     * stairs call this.
     *
     * @param pos   the cell to annotate; must not be null
     * @param shape the shape to install; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView putShape(CellPos pos, CollisionShape shape) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (shape == null) {
            throw new IllegalArgumentException("shape must not be null");
        }
        shapes.put(pos, shape);
        return this;
    }

    /**
     * Inject a per-cell trait override. Wins over the registry for
     * the same cell.
     *
     * @param pos    the cell to annotate; must not be null
     * @param traits the traits to install; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView putTraits(CellPos pos, BlockTraits traits) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (traits == null) {
            throw new IllegalArgumentException("traits must not be null");
        }
        traitOverrides.put(pos, traits);
        return this;
    }

    /**
     * Install the fallback trait registry. Cells with no per-cell
     * override resolve their id through this registry; the default
     * (the empty registry) returns {@link BlockTraits#defaults()}
     * for every id.
     *
     * @param registry the registry to use; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView setTraitsRegistry(BlockTraitsRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.traitsRegistry = registry;
        return this;
    }

    /**
     * Install an inventory snapshot returned by {@link #getInventory()}.
     * Tests that exercise item sense call this; the default is
     * {@link InventoryView#empty()}.
     *
     * @param inventory the snapshot to return; must not be null
     * @return this mock, for fluent test setup
     */
    public MockWorldView setInventory(InventoryView inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("inventory must not be null");
        }
        this.inventory = inventory;
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
    public CollisionShape getCollisionShape(CellPos pos, ViewMode mode) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (unloaded.contains(pos)) {
            return CollisionShape.fullCube();
        }
        // Explicit shape wins; otherwise blockId-based default
        // (air -> empty, anything else -> full cube). Tests that
        // need partial geometry use putShape().
        CollisionShape shape = shapes.get(pos);
        if (shape != null) {
            return shape;
        }
        BlockSnapshot snapshot = blocks.get(pos);
        boolean isAir = snapshot == null
            || BlockSnapshot.AIR.equals(snapshot.blockId());
        return isAir ? CollisionShape.empty() : CollisionShape.fullCube();
    }

    @Override
    public BlockTraits getBlockTraits(CellPos pos, ViewMode mode) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        BlockTraits override = traitOverrides.get(pos);
        if (override != null) {
            return override;
        }
        BlockSnapshot snapshot = blocks.get(pos);
        String id = snapshot == null ? BlockSnapshot.AIR
            : snapshot.blockId();
        return traitsRegistry.traitsFor(id);
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

    @Override
    public InventoryView getInventory() {
        return inventory;
    }
}
