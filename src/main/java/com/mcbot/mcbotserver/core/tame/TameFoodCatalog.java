package com.mcbot.mcbotserver.core.tame;

import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import java.util.Map;
import java.util.Set;

/**
 * The tameable-species vocabulary and its per-species tame-item map,
 * shared by the process tier (the typed NO_TAME_ITEM verdict) and the
 * behavior tier (hotbar equip) - the same tier-crossing read shape
 * {@code RangedLoadouts} owns for combat. Vanilla 1.20.1 tame
 * interactions: the wolf takes a bone, the cat a raw cod or salmon,
 * the parrot any of six seed kinds - each press consumes the item and
 * rolls the species' chance (wolf and cat 1/3, parrot 1/10). Horses
 * are deliberately absent: their tame path is the ride-bucking temper
 * loop, not an item press, and riding is not a device capability.
 *
 * <p>Implementation note: pure string data over api surfaces; runs on
 * the server tick thread.
 */
public final class TameFoodCatalog {

    private static final Map<String, Set<String>> TAME_ITEMS = Map.of(
            "minecraft:wolf",
            Set.of("minecraft:bone"),
            "minecraft:cat",
            Set.of("minecraft:cod", "minecraft:salmon"),
            "minecraft:parrot",
            Set.of(
                    "minecraft:wheat_seeds",
                    "minecraft:melon_seeds",
                    "minecraft:pumpkin_seeds",
                    "minecraft:beetroot_seeds",
                    "minecraft:torchflower_seeds",
                    "minecraft:pitcher_pod"));

    private TameFoodCatalog() {}

    /**
     * Whether the entity type can be tamed by an item press at all.
     *
     * @param typeKey entity type key from {@code EntitySnapshot.type()};
     *                never null
     * @return true when a tame-item press can tame the species
     */
    public static boolean isTameable(String typeKey) {
        return TAME_ITEMS.containsKey(typeKey);
    }

    /**
     * The item kinds that tame the species.
     *
     * @param typeKey entity type key from {@code EntitySnapshot.type()};
     *                never null
     * @return the accepted item ids; empty when the type is not
     *         tameable
     */
    public static Set<String> tameItemsFor(String typeKey) {
        return TAME_ITEMS.getOrDefault(typeKey, Set.of());
    }

    /**
     * The first hotbar slot holding an item that tames the species.
     * Hotbar-only on purpose: the SLOT channel's reach is the hotbar,
     * so a tame item buried in the backpack is as good as absent for
     * this task.
     *
     * @param inventory the live inventory snapshot; never null
     * @param typeKey   entity type key from {@code EntitySnapshot.type()};
     *                  never null
     * @return the hotbar slot, or -1 when no tame item is equipped
     */
    @Feature(
            id = "taming.chain.item_selection",
            face = "taming.chain",
            description = "Tame-item selection: first hotbar slot holding an item the species accepts (wolf bone,"
                    + " cat cod/salmon, parrot any of six seeds). Hotbar-only - the SLOT channel's reach"
                    + " is the hotbar. Shared by the process tier (NO_TAME_ITEM verdict) and the behavior"
                    + " tier (equip before pressing).",
            vanillaRef = "Wolf.mobInteract / Cat.mobInteract / Parrot.TAME_FOOD (decompiled 1.20.1)")
    public static int hotbarTameItemSlot(InventoryView inventory, String typeKey) {
        Set<String> accepted = tameItemsFor(typeKey);
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (!item.isEmpty() && accepted.contains(item.itemId())) {
                return slot;
            }
        }
        return -1;
    }
}
