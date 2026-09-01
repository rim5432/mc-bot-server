package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import com.mcbot.mcbotserver.api.world.BobberSnapshot;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ProjectileSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.phys.AABB;

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
    private final BlockTraitsRegistry traits;
    private final Supplier<InventoryView> inventorySupplier;

    /**
     * Creates a view over one level with a trait registry and an
     * inventory supplier. The supplier is called once per perception
     * tick from {@link #getInventory()}; it must return a fresh
     * immutable snapshot (the adapter binding's
     * {@code BindingInventory.snapshot()} is the canonical source).
     *
     * @param level            the world to read; never null
     * @param traits           sealed trait registry; never null
     * @param inventorySupplier produces the current inventory snapshot;
     *                          never null
     */
    public BindingWorldView(ServerLevel level, BlockTraitsRegistry traits, Supplier<InventoryView> inventorySupplier) {
        this.level = Objects.requireNonNull(level, "level");
        this.traits = Objects.requireNonNull(traits, "traits");
        this.inventorySupplier = Objects.requireNonNull(inventorySupplier, "inventorySupplier");
    }

    @Override
    public BlockSnapshot getBlock(CellPos pos, ViewMode mode) {
        BlockPos mc = toMc(pos);
        if (!level.hasChunkAt(mc)) {
            return null;
        }
        var state = level.getBlockState(mc);
        // Canonical air id: the registry key would be "minecraft:air",
        // which fails BlockSnapshot.isAir() and makes every open cell
        // look solid to the move graph.
        if (state.isAir()) {
            return new BlockSnapshot(pos, BlockSnapshot.AIR);
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new BlockSnapshot(pos, key != null ? key.toString() : BlockSnapshot.UNKNOWN);
    }

    /**
     * Engine collision geometry, cell-local: empty for non-solid
     * blocks, the block's own voxel bounds otherwise. Unloaded chunks
     * answer solid - unknown ground must read as impassable, never as
     * walkable (decision 17a).
     *
     * @param pos  the cell to read; must not be null
     * @param mode consistency requested, per decision 17b
     * @return the cell-local shape; never null
     */
    @Override
    public CollisionShape getCollisionShape(CellPos pos, ViewMode mode) {
        BlockPos mc = toMc(pos);
        if (!level.hasChunkAt(mc)) {
            return CollisionShape.fullCube();
        }
        var state = level.getBlockState(mc);
        var voxels = state.getCollisionShape(level, mc);
        if (voxels.isEmpty()) {
            return CollisionShape.empty();
        }
        // Empirically verified: the returned shape is ALREADY cell-
        // local ([0,1] per axis); subtracting the block origin again
        // produced inverted boxes ("box min must be <= max").
        double minX = voxels.min(net.minecraft.core.Direction.Axis.X);
        double minY = voxels.min(net.minecraft.core.Direction.Axis.Y);
        double minZ = voxels.min(net.minecraft.core.Direction.Axis.Z);
        double maxX = voxels.max(net.minecraft.core.Direction.Axis.X);
        double maxY = voxels.max(net.minecraft.core.Direction.Axis.Y);
        double maxZ = voxels.max(net.minecraft.core.Direction.Axis.Z);
        boolean full = minX <= 0 && minY <= 0 && minZ <= 0 && maxX >= 1 && maxY >= 1 && maxZ >= 1;
        return full
                ? CollisionShape.fullCube()
                : CollisionShape.partial(new CollisionShape.Box(
                        Math.max(0, Math.min(1, minX)),
                        Math.max(0, Math.min(1, minY)),
                        Math.max(0, Math.min(1, minZ)),
                        Math.max(0, Math.min(1, maxX)),
                        Math.max(0, Math.min(1, maxY)),
                        Math.max(0, Math.min(1, maxZ))));
    }

    @Override
    public List<EntitySnapshot> getEntities(CellPos center, double radius, ViewMode mode) {
        AABB box = new AABB(
                center.x() + 0.5 - radius,
                center.y() + 0.5 - radius,
                center.z() + 0.5 - radius,
                center.x() + 0.5 + radius,
                center.y() + 0.5 + radius,
                center.z() + 0.5 + radius);
        List<EntitySnapshot> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double dist = e.blockPosition().distSqr(toMc(center));
            if (dist > radius * radius) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            out.add(new EntitySnapshot(
                    e.getStringUUID(),
                    key != null ? key.toString() : "unknown",
                    new CellPos(e.getBlockX(), e.getBlockY(), e.getBlockZ()),
                    e.getHealth(),
                    e.getMaxHealth(),
                    e instanceof TamableAnimal tamable && tamable.isTame(),
                    e instanceof Wolf wolf && wolf.isAngry()));
        }
        return out;
    }

    @Override
    public List<BobberSnapshot> getBobbers(CellPos center, double radius, ViewMode mode) {
        AABB box = new AABB(
                center.x() + 0.5 - radius,
                center.y() + 0.5 - radius,
                center.z() + 0.5 - radius,
                center.x() + 0.5 + radius,
                center.y() + 0.5 + radius,
                center.z() + 0.5 + radius);
        List<BobberSnapshot> out = new ArrayList<>();
        for (net.minecraft.world.entity.projectile.FishingHook hook :
                level.getEntitiesOfClass(net.minecraft.world.entity.projectile.FishingHook.class, box)) {
            double dist = hook.blockPosition().distSqr(toMc(center));
            if (dist > radius * radius) {
                continue;
            }
            out.add(new BobberSnapshot(
                    new com.mcbot.mcbotserver.api.types.Vec3(hook.getX(), hook.getY(), hook.getZ()),
                    hook.getHookedIn() != null));
        }
        return out;
    }

    @Override
    public List<ProjectileSnapshot> getProjectiles(CellPos center, double radius, ViewMode mode) {
        AABB box = new AABB(
                center.x() + 0.5 - radius,
                center.y() + 0.5 - radius,
                center.z() + 0.5 - radius,
                center.x() + 0.5 + radius,
                center.y() + 0.5 + radius,
                center.z() + 0.5 + radius);
        List<ProjectileSnapshot> out = new ArrayList<>();
        for (net.minecraft.world.entity.projectile.Projectile p :
                level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, box)) {
            if (p instanceof net.minecraft.world.entity.projectile.FishingHook) {
                // Bobbers ride their own query; bite watching owns them.
                continue;
            }
            // Exact-position spherical filter: the AABB pre-filter
            // bounds the candidate set, and blockPosition() truncation
            // would blur the edge by up to sqrt(3)/2 blocks - the
            // snapshot carries sub-block precision, so the gate matches.
            double cx = center.x() + 0.5;
            double cy = center.y() + 0.5;
            double cz = center.z() + 0.5;
            double dx = p.getX() - cx;
            double dy = p.getY() - cy;
            double dz = p.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz > radius * radius) {
                continue;
            }
            var motion = p.getDeltaMovement();
            out.add(new ProjectileSnapshot(
                    new com.mcbot.mcbotserver.api.types.Vec3(p.getX(), p.getY(), p.getZ()),
                    new com.mcbot.mcbotserver.api.types.Vec3(motion.x, motion.y, motion.z)));
        }
        return out;
    }

    /**
     * Registry traits for the cell's block id, bare-id form (the
     * state suffix is dropped; the registry's exact-then-fallback
     * matching covers both keyings). Unknown ids answer
     * {@link BlockTraits#defaults()} per the registry contract.
     *
     * @param pos  the cell to read; must not be null
     * @param mode consistency requested, per decision 17b
     * @return the cell's traits; never null
     */
    @Override
    public BlockTraits getBlockTraits(CellPos pos, ViewMode mode) {
        BlockPos mc = toMc(pos);
        if (!level.hasChunkAt(mc)) {
            return BlockTraits.defaults();
        }
        var state = level.getBlockState(mc);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return traits.traitsFor(key != null ? key.toString() : BlockSnapshot.UNKNOWN);
    }

    @Override
    public boolean isLoaded(CellPos pos) {
        return level.hasChunkAt(toMc(pos));
    }

    /**
     * Inventory snapshot from the body's binding. The supplier is
     * called fresh each invocation — the adapter's
     * {@code BindingInventory.snapshot()} produces an immutable copy,
     * so off-thread consumers read without locks.
     *
     * @return the current inventory snapshot; never null
     */
    @Override
    public InventoryView getInventory() {
        return inventorySupplier.get();
    }

    private static BlockPos toMc(CellPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
