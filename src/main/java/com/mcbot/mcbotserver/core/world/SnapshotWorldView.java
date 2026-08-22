package com.mcbot.mcbotserver.core.world;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable block copy of a bounded region, captured on the server
 * tick thread and read from the replan worker thread - decision 17b's
 * SNAPSHOT half made concrete. Cells outside the captured box answer
 * null / not-loaded, which the planner already prunes as unknown; a
 * snapshot therefore can never route through terrain it did not see.
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

    private SnapshotWorldView(Map<CellPos, BlockSnapshot> cells) {
        this.cells = cells;
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
    public static SnapshotWorldView capture(WorldView live, CellPos a,
                                            CellPos b) {
        Objects.requireNonNull(live, "live");
        int minX = Math.min(a.x(), b.x()) - XZ_MARGIN;
        int maxX = Math.max(a.x(), b.x()) + XZ_MARGIN;
        int minY = Math.min(a.y(), b.y()) - Y_MARGIN;
        int maxY = Math.max(a.y(), b.y()) + Y_MARGIN;
        int minZ = Math.min(a.z(), b.z()) - XZ_MARGIN;
        int maxZ = Math.max(a.z(), b.z()) + XZ_MARGIN;

        var copied = new HashMap<CellPos, BlockSnapshot>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var pos = new CellPos(x, y, z);
                    BlockSnapshot snap = live.getBlock(pos,
                        ViewMode.LIVE);
                    if (snap != null) {
                        copied.put(pos, snap);
                    }
                }
            }
        }
        return new SnapshotWorldView(Map.copyOf(copied));
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
     * Always empty - snapshots carry blocks only, by design.
     *
     * @param center unused
     * @param radius unused
     * @param mode   unused
     * @return an empty list; never null
     */
    @Override
    public List<EntitySnapshot> getEntities(CellPos center,
                                            double radius,
                                            ViewMode mode) {
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
