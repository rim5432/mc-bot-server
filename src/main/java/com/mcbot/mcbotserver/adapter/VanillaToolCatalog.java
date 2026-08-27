package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.actor.ToolCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * ToolCatalog over the frozen vanilla registries: resolves the id
 * pair per call and answers {@link ItemStack#getDestroySpeed} on the
 * block's default state - exactly the value the destroy-progress
 * arithmetic weighs, so the ranking cannot drift from the dig path.
 * Unknown or air ids degrade to the 1.0 bare-hand baseline, matching
 * vanilla's own wrong-tool answer ({@code DiggerItem} returns
 * {@code speed} only inside its mineable tag).
 *
 * <p>Built at assembly time after the registries froze; resolution
 * is a registry lookup per call (a handful per dig tick), not a
 * table - the item-by-block pair space is too large to precompute
 * and the lookup is a hash away.
 */
public final class VanillaToolCatalog implements ToolCatalog {

    /** The bare-hand baseline every vanilla wrong-tool answer equals. */
    private static final float BASELINE = 1.0f;

    /** Creates a catalog over the current registries. */
    public VanillaToolCatalog() {}

    @Override
    public float destroySpeed(String itemId, String blockId) {
        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
        if (item == null || item == Items.AIR || block == null || block == Blocks.AIR) {
            return BASELINE;
        }
        return new ItemStack(item).getDestroySpeed(block.defaultBlockState());
    }
}
