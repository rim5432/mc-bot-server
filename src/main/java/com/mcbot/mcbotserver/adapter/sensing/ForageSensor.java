package com.mcbot.mcbotserver.adapter.sensing;

import com.mcbot.mcbotserver.api.types.CellPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Forage scan (issue 0010 section 4.3, strategy 1): nearest
 * sweet-berry-bush cell within a radius. A once-per-mission scan,
 * not per-tick - the block walk is thousands of getBlockState calls,
 * cheap exactly once and ruinous every tick. Any age counts: a bare
 * bush that drops nothing honestly burns the one forage attempt
 * (section 4.5 budget) instead of lying about food.
 */
public final class ForageSensor {

    private ForageSensor() {}

    /**
     * Nearest sweet-berry-bush cell by squared distance.
     *
     * @param level  the live level; never null
     * @param center scan center (the body cell); never null
     * @param radius horizontal scan radius in blocks; positive
     * @return the nearest bush cell, or null when none is loaded
     */
    public static CellPos nearestBerryBush(ServerLevel level, CellPos center, int radius) {
        CellPos best = null;
        long bestDist = Long.MAX_VALUE;
        int cx = center.x();
        int cy = center.y();
        int cz = center.z();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = cy - 8; y <= cy + 8; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).is(Blocks.SWEET_BERRY_BUSH)) {
                        continue;
                    }
                    long dist = sq(x - cx) + sq(y - cy) + sq(z - cz);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new CellPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private static long sq(int v) {
        return (long) v * v;
    }
}
