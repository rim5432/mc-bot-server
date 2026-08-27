package com.mcbot.mcbotserver.api.menu;

import javax.annotation.Nullable;

/**
 * Armor classification seam: which registry ids are wearable armor,
 * which own-inventory armor slot they fit, and a protection rank to
 * order candidates by. Game-content knowledge (vanilla ArmorItem)
 * stays adapter-side; this seam keeps the core wear planner
 * data-driven and offline-testable.
 *
 * <p>Contract: armor slots in the own-inventory menu view run flat
 * 5..8, head..feet (the survival layout MenuSlotLayouts mirrors);
 * {@code armorSlot} answers are in that index space.
 */
public interface ArmorCatalog {

    /** One wearable: where it fits and how good it is. */
    record ArmorPiece(int armorSlot, int protection) {}

    /**
     * Classify one registry id.
     *
     * @param itemId registry key, e.g. {@code minecraft:diamond_helmet};
     *               never null
     * @return the piece, or null when the id is not wearable armor
     */
    @Nullable
    ArmorPiece classify(String itemId);
}
