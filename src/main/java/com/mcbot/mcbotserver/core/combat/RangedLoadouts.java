package com.mcbot.mcbotserver.core.combat;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Set;

/**
 * Ranged-loadout reads over the live inventory: whether a bow-and-
 * arrows loadout exists, and where the bow sits. Shared by the
 * behavior tier (CombatBehavior's draw pacing) and the process tier
 * (DefendProcess's refuse-when-unarmed gate) - the check used to live
 * as a static on CombatBehavior, which made DefendProcess reach
 * across tiers for an inventory predicate.
 *
 * <p>Deliberately knows nothing about reach or bands - distance gates
 * are the caller's.
 *
 * <p>Implementation note: pure read over api surfaces; runs on the
 * server tick thread.
 */
public final class RangedLoadouts {

    private static final String BOW_ID = "minecraft:bow";

    private static final Set<String> ARROW_IDS =
            Set.of("minecraft:arrow", "minecraft:tipped_arrow", "minecraft:spectral_arrow");

    private RangedLoadouts() {}

    /**
     * The hotbar bow slot when a ranged loadout exists: a bow carried
     * in the hotbar and arrows anywhere in the inventory.
     *
     * @param world read surface for the inventory; never null
     * @return the bow's hotbar slot, or -1 when the loadout is missing
     */
    public static int hotbarBowSlot(WorldView world) {
        InventoryView inventory = world.getInventory();
        int bow = -1;
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (!item.isEmpty() && BOW_ID.equals(item.itemId())) {
                bow = slot;
                break;
            }
        }
        if (bow < 0) {
            return -1;
        }
        for (var item : inventory.main()) {
            if (!item.isEmpty() && ARROW_IDS.contains(item.itemId())) {
                return bow;
            }
        }
        return -1;
    }
}
