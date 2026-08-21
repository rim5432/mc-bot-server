package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read half of the entity binding: pure reads over one ServerLevel.
 *
 * <p>Contract: see boundaries.md section A and decisions 17/17a/17b.
 * Every method is a straight translation from engine types into the
 * api's data shapes; nothing here mutates the level. Under the
 * single-threaded Stage 1 implementation SNAPSHOT and LIVE are the same
 * read, per decision 17b.
 *
 * <p>Implementation note: server tick thread only.
 */
// contract: see boundaries.md section A (WorldView is read-only)
public final class BindingWorldView implements WorldView {

    private final ServerLevel level;

    /**
     * Creates a view over one level.
     *
     * @param level the world to read; never null
     */
    public BindingWorldView(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public BlockSnapshot getBlock(CellPos pos, ViewMode mode) {
        BlockPos mc = toMc(pos);
        if (!level.hasChunkAt(mc)) {
            return null;
        }
        var state = level.getBlockState(mc);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(
            state.getBlock());
        return new BlockSnapshot(pos,
            key != null ? key.toString() : BlockSnapshot.UNKNOWN);
    }

    @Override
    public List<EntitySnapshot> getEntities(CellPos center,
                                            double radius, ViewMode mode) {
        AABB box = new AABB(
            center.x() + 0.5 - radius, center.y() + 0.5 - radius,
            center.z() + 0.5 - radius,
            center.x() + 0.5 + radius, center.y() + 0.5 + radius,
            center.z() + 0.5 + radius);
        List<EntitySnapshot> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
            box)) {
            double dist = e.blockPosition().distSqr(toMc(center));
            if (dist > radius * radius) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(
                e.getType());
            out.add(new EntitySnapshot(
                e.getStringUUID(),
                key != null ? key.toString() : "unknown",
                new CellPos(e.getBlockX(), e.getBlockY(), e.getBlockZ()),
                e.getHealth(), e.getMaxHealth()));
        }
        return out;
    }

    @Override
    public boolean isLoaded(CellPos pos) {
        return level.hasChunkAt(toMc(pos));
    }

    private static BlockPos toMc(CellPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
