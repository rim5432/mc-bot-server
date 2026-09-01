package com.mcbot.mcbotserver.core.combat;

import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Set;

/**
 * Combat loadout reads over the live inventory: whether a bow-and-
 * arrows loadout exists, where the bow sits, and whether any hotbar
 * weapon outranks the bow in melee. Shared by the behavior tier
 * (CombatBehavior's draw pacing) and the process tier (DefendProcess's
 * refuse-when-unarmed gate and its weapon-aware engagement decision) -
 * the checks used to live as a static on CombatBehavior, which made
 * DefendProcess reach across tiers for an inventory predicate.
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
    @Feature(
            id = "combat.bow_slot.loadout_detection",
            face = "combat.bow_slot",
            description =
                    "Bow loadout detection: bow in hotbar + arrows anywhere in inventory (normal/tipped/spectral)." +
                    "Returns the bow's hotbar slot, or -1 when the loadout is missing. Shared by behavior tier" +
                    "(draw pacing) and process tier (refuse-when-unarmed gate).",
            vanillaRef = "BowItem + ArrowItem registry (decompiled 1.20.1)")
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

    /**
     * Whether any non-bow hotbar item deals strictly more per-hit
     * damage than the bow. The bow carries no attack-damage modifier,
     * so "nothing beats it" means the bot is reduced to clubbing with
     * the bow inside melee reach - the one fact both engagement
     * decisions need: the behavior tier keeps the bow answering
     * point-blank instead of clubbing, and the process tier opens
     * melee-target fights at the standoff rim instead of charging.
     * Reads the hotbar only, matching the melee ranking's selection
     * surface. A catalog that ranks nothing
     * ({@link WeaponCatalog#none()}) answers false: a blind ranking
     * conservatively trusts the bow.
     *
     * @param world   read surface for the inventory; never null
     * @param weapons per-hit damage ranking; never null
     * @return true when a hotbar item outranks the bow in melee
     */
    @Feature(
            id = "combat.bow_slot.weapon_aware_routing",
            face = "combat.bow_slot",
            description =
                    "Weapon-aware routing: whether any non-bow hotbar item deals strictly more per-hit damage than" +
                    " the bow. The bow carries no ATTACK_DAMAGE modifier, so 'nothing beats it' means the bot is" +
                    " reduced to clubbing. Drives both point-blank bow fire (behavior tier) and standoff-rim" +
                    " engagement (process tier).",
            vanillaRef = "Item attribute modifiers (decompiled 1.20.1)",
            deviation =
                    "Bot-specific: the bow has no melee damage modifier, so any weapon ranking by ATTACK_DAMAGE" +
                    " would switch off the bow every tick. This predicate is the single source of truth for when" +
                    " the bow should keep firing vs. when melee is better.")
    public static boolean meleeWeaponBeatsBow(WorldView world, WeaponCatalog weapons) {
        InventoryView inventory = world.getInventory();
        float bowDamage = weapons.perHitDamage(BOW_ID);
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            var item = inventory.main().get(slot);
            if (item.isEmpty() || BOW_ID.equals(item.itemId())) {
                continue;
            }
            if (weapons.perHitDamage(item.itemId()) > bowDamage) {
                return true;
            }
        }
        return false;
    }
}
