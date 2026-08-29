package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable copy of a bounded region - blocks, collision shapes, and
 * block traits - captured on the server tick thread and read from the
 * replan worker thread; decision 17b's SNAPSHOT half made concrete.
 * Cells outside the captured box answer null / not-loaded / solid,
 * which the planner already prunes as unknown; a snapshot therefore
 * can never route through terrain it did not see.
 *
 * <p>Why shapes and traits are copied too: the planner's viability
 * queries go through {@link #getCollisionShape} /
 * {@link #getBlockTraits}, not through block ids. A snapshot that
 * served only blocks would fall back to the interface defaults -
 * solid everywhere - and every plan would come back empty.
 *
 * <p>Contract: see boundaries.md section A (read-only) and decision
 * 17b (ViewMode). The capture is a one-shot constructor side effect on
 * the live view - after construction this class is deeply immutable
 * and safe from any thread. Entity queries return empty by design:
 * pathfinding consumes blocks only, and pretending otherwise would
 * hide staleness.
 */
public final class SnapshotWorldView implements WorldView {

    /** XZ padding around the start-goal hull when capturing. */
    public static final int XZ_MARGIN = 8;

    /** Y padding around the start-goal hull when capturing. */
    public static final int Y_MARGIN = 4;

    private final Map<CellPos, BlockSnapshot> cells;
    private final Map<CellPos, CollisionShape> shapes;
    private final Map<CellPos, BlockTraits> traits;

    private SnapshotWorldView(
            Map<CellPos, BlockSnapshot> cells, Map<CellPos, CollisionShape> shapes, Map<CellPos, BlockTraits> traits) {
        this.cells = cells;
        this.shapes = shapes;
        this.traits = traits;
    }

    /**
     * Capture the box spanning both cells plus margins, reading
     * through the given live view. Unloaded chunks contribute nothing;
     * their cells simply stay absent.
     *
     * <p>Runs on the server tick thread; do not call with a live view
     * from any other thread.
     *
     * @param live the authoritative view to copy from; never null
     * @param a    first region anchor (typically the bot cell); never
     *             null
     * @param b    second region anchor (typically the goal cell);
     *             never null
     * @return an immutable snapshot; never null
     */
    // runs on server tick thread; reads live world state
    public static SnapshotWorldView capture(WorldView live, CellPos a, CellPos b) {
        Objects.requireNonNull(live, "live");
        long volume =
                (long) (Math.abs(a.x() - b.x()) + 17) * (Math.abs(a.y() - b.y()) + 9) * (Math.abs(a.z() - b.z()) + 17);
        if (volume > 20000) {
            com.mojang.logging.LogUtils.getLogger().info("[SNAPSHOT] large capture a={} b={} volume={}", a, b, volume);
        }
        int minX = Math.min(a.x(), b.x()) - XZ_MARGIN;
        int maxX = Math.max(a.x(), b.x()) + XZ_MARGIN;
        int minY = Math.min(a.y(), b.y()) - Y_MARGIN;
        int maxY = Math.max(a.y(), b.y()) + Y_MARGIN;
        int minZ = Math.min(a.z(), b.z()) - XZ_MARGIN;
        int maxZ = Math.max(a.z(), b.z()) + XZ_MARGIN;

        var copied = new HashMap<CellPos, BlockSnapshot>();
        var shapes = new HashMap<CellPos, CollisionShape>();
        var traits = new HashMap<CellPos, BlockTraits>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var pos = new CellPos(x, y, z);
                    BlockSnapshot snap = live.getBlock(pos, ViewMode.LIVE);
                    if (snap != null) {
                        copied.put(pos, snap);
                    }
                    shapes.put(pos, live.getCollisionShape(pos, ViewMode.LIVE));
                    traits.put(pos, live.getBlockTraits(pos, ViewMode.LIVE));
                }
            }
        }
        return new SnapshotWorldView(Map.copyOf(copied), Map.copyOf(shapes), Map.copyOf(traits));
    }

    /**
     * The copied snapshot, or null outside the captured box. Mode is
     * ignored: a snapshot has exactly one consistency level.
     *
     * @param pos  the cell to read; must not be null
     * @param mode ignored; accepted for interface conformance
     * @return the captured snapshot or null when never captured
     */
    // runs on any thread; immutable after capture
    @Override
    public BlockSnapshot getBlock(CellPos pos, ViewMode mode) {
        return cells.get(Objects.requireNonNull(pos, "pos"));
    }

    /**
     * The captured shape inside the box; solid outside it - unknown
     * ground must read as impassable, never as walkable.
     *
     * @param pos  the cell to read; must not be null
     * @param mode ignored; accepted for interface conformance
     * @return the captured shape; never null
     */
    @Override
    public CollisionShape getCollisionShape(CellPos pos, ViewMode mode) {
        CollisionShape shape = shapes.get(Objects.requireNonNull(pos, "pos"));
        return shape != null ? shape : CollisionShape.fullCube();
    }

    /**
     * The captured traits inside the box; conservative defaults
     * outside it.
     *
     * @param pos  the cell to read; must not be null
     * @param mode ignored; accepted for interface conformance
     * @return the captured traits; never null
     */
    @Override
    public BlockTraits getBlockTraits(CellPos pos, ViewMode mode) {
        BlockTraits t = traits.get(Objects.requireNonNull(pos, "pos"));
        return t != null ? t : BlockTraits.defaults();
    }

    /**
     * Always empty - snapshots carry blocks only, by design.
     *
     * @param center unused
     * @param radius unused
     * @param mode   unused
     * @return an empty list; never null
     */
    @Override
    public List<EntitySnapshot> getEntities(CellPos center, double radius, ViewMode mode) {
        return List.of();
    }

    /**
     * Whether the cell was captured - "loaded" in snapshot terms.
     *
     * @param pos the cell to probe; must not be null
     * @return true only when a block snapshot exists for the cell
     */
    @Override
    public boolean isLoaded(CellPos pos) {
        return cells.containsKey(Objects.requireNonNull(pos, "pos"));
    }
}
