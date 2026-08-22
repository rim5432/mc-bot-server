package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.pathing.Movement;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stage-1 move set: walk, diagonal, step-up and drop - the smallest
 * vocabulary that makes flat ground, single steps and small ledges
 * searchable. Grow per workplan; the A* engine consumes this through
 * {@link MoveGraph} and never learns individual move types.
 *
 * <p>Viability rules (all reads via WorldView, LIVE mode):
 *
 * <ul>
 * <li>passable(cell): block present AND air - unloaded cells return
 * null from getBlock and are treated as impassable, honoring
 * decision 17a ("unknown" is never traversed).</li>
 * <li>supported(cell): the block below is solid (present, not air).</li>
 * </ul>
 */
// contract: see boundaries.md decisions 7/8 + 17a
public final class BasicMoves {

    private BasicMoves() {
    }

    /**
     * All candidate movements leaving one cell.
     *
     * @param src the expansion cell; never null
     * @return unsorted candidate edges; viability not yet checked;
     *         never null
     */
    public static List<Movement> from(CellPos src) {
        Objects.requireNonNull(src, "src");
        List<Movement> out = new ArrayList<>();
        for (Direction d : Direction.values()) {
            out.add(new Walk(src,
                new CellPos(src.x() + d.dx, src.y(), src.z() + d.dz)));
            out.add(new ClimbUp(src,
                new CellPos(src.x() + d.dx, src.y() + 1,
                    src.z() + d.dz)));
            for (int k = 1; k <= MAX_DROP; k++) {
                out.add(new Drop(src,
                    new CellPos(src.x() + d.dx, src.y() - k,
                        src.z() + d.dz), k));
            }
        }
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                out.add(new Diagonal(src,
                    new CellPos(src.x() + dx, src.y(),
                        src.z() + dz)));
            }
        }
        return out;
    }

    /** Longest vertical drop the set considers walkable. */
    public static final int MAX_DROP = 3;

    private enum Direction {
        N(0, -1), S(0, 1), E(1, 0), W(-1, 0);

        final int dx;
        final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    private static boolean passable(WorldView world, CellPos cell) {
        BlockSnapshot s = world.getBlock(cell, ViewMode.LIVE);
        return s != null && !s.isUnknown() && s.isAir();
    }

    private static boolean supported(WorldView world, CellPos cell) {
        BlockSnapshot below = world.getBlock(
            new CellPos(cell.x(), cell.y() - 1, cell.z()),
            ViewMode.LIVE);
        return below != null && !below.isUnknown() && !below.isAir();
    }

    private static boolean standable(WorldView world, CellPos cell) {
        return passable(world, cell)
            && passable(world,
                new CellPos(cell.x(), cell.y() + 1, cell.z()))
            && supported(world, cell);
    }

    record Walk(CellPos source, CellPos destination)
        implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.0;
        }

        @Override
        public String describe() {
            return "walk " + source + "->" + destination;
        }
    }

    record Diagonal(CellPos source, CellPos destination)
        implements Movement {

        private static boolean passable(WorldView world, CellPos cell) {
            BlockSnapshot s = world.getBlock(cell, ViewMode.LIVE);
            return s != null && !s.isUnknown() && s.isAir();
        }

        @Override
        public boolean isViable(WorldView world) {
            int dx = destination.x() - source.x();
            int dz = destination.z() - source.z();
            boolean edgeA = passable(world, new CellPos(
                source.x() + dx, source.y(), source.z()));
            boolean edgeB = passable(world, new CellPos(
                source.x(), source.y(), source.z() + dz));
            return edgeA && edgeB && standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.5;
        }

        @Override
        public String describe() {
            return "diag " + source + "->" + destination;
        }
    }

    record ClimbUp(CellPos source, CellPos destination)
        implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            return standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 2.0;
        }

        @Override
        public String describe() {
            return "up " + source + "->" + destination;
        }
    }

    record Drop(CellPos source, CellPos destination, int depth)
        implements Movement {

        @Override
        public boolean isViable(WorldView world) {
            for (int i = 1; i < depth; i++) {
                CellPos mid = new CellPos(destination.x(),
                    source.y() - i, destination.z());
                if (!passable(world, mid)) {
                    return false;
                }
            }
            return standable(world, destination);
        }

        @Override
        public double cost(WorldView world) {
            return 1.0 + depth * 1.5;
        }

        @Override
        public String describe() {
            return "drop" + depth + " " + source + "->" + destination;
        }
    }
}
