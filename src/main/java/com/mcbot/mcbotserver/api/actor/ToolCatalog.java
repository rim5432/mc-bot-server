package com.mcbot.mcbotserver.api.actor;

/**
 * Tool classification seam: the dig speed an item contributes against
 * one block. Vanilla answers 1.0 for both the empty hand and a tool
 * on a block outside its mineable tag ({@code DiggerItem} returns
 * {@code speed} only on a tagged block), so 1.0 is the no-benefit
 * baseline a selector can compare against - anything at or below it
 * never justifies a slot switch.
 *
 * <p>Registry-level only: enchantment-aware speeds (Efficiency) need
 * stack-level disclosure this catalog does not carry; the dig path
 * itself reads the live stack and stays enchant-aware adapter-side.
 */
public interface ToolCatalog {

    /**
     * The no-op catalog: nothing counts as a faster digger.
     *
     * @return a catalog answering 0 for every pair; never null
     */
    static ToolCatalog none() {
        return (itemId, blockId) -> 0f;
    }

    /**
     * Dig speed the item contributes against the block's default
     * state, on the vanilla scale where the bare hand is 1.0.
     *
     * @param itemId  registry key, e.g. {@code minecraft:iron_pickaxe};
     *                never null
     * @param blockId registry key, e.g. {@code minecraft:stone}; never
     *                null
     * @return the vanilla destroy speed; 1.0 when the pair is unknown
     *         or the item has no tool advantage
     */
    float destroySpeed(String itemId, String blockId);
}
