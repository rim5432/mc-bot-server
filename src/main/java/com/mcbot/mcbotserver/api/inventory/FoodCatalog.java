package com.mcbot.mcbotserver.api.inventory;

import javax.annotation.Nullable;

/**
 * Food classification seam: which registry ids are edible and their
 * nutrition facts. Game-content knowledge (vanilla FoodProperties)
 * stays adapter-side; the core eat reflex and any future acquisition
 * planner rank foods through this seam and stay offline-testable.
 */
public interface FoodCatalog {

    /**
     * Nutrition facts of one edible, vanilla FoodData semantics. The
     * always-eat flag (golden apples) is not classified: 1.20.1's
     * FoodProperties exposes no getter for it, and the eat reflex
     * only fires while hungry anyway.
     */
    record FoodFacts(int nutrition, float saturationModifier) {}

    /**
     * Classify one registry id.
     *
     * @param itemId registry key, e.g. {@code minecraft:cooked_beef};
     *               never null
     * @return the facts, or null when the id is not edible
     */
    @Nullable
    FoodFacts facts(String itemId);
}
