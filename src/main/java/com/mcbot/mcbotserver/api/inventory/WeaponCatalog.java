package com.mcbot.mcbotserver.api.inventory;

/**
 * Weapon classification seam: the per-hit melee damage a registry id
 * contributes on the mob attack path. Mob melee has no attack-speed
 * scaling, crits, or sweeps - those live in {@code Player.attack},
 * which a PathfinderMob carrier never runs - so the MAINHAND
 * ATTACK_DAMAGE modifier is the whole ranking and per-hit damage IS
 * the weapon metric (an axe outranks a same-tier sword).
 *
 * <p>Registry-level only: enchantment-aware ranking needs stack-level
 * disclosure {@link ItemView} does not carry yet; the damage path
 * itself reads the live stack and stays enchant-aware adapter-side.
 */
public interface WeaponCatalog {

    /**
     * The no-op catalog: nothing counts as a weapon.
     *
     * @return a catalog answering 0 for every id; never null
     */
    static WeaponCatalog none() {
        return id -> 0f;
    }

    /**
     * Per-hit melee damage contributed by the item.
     *
     * @param itemId registry key, e.g. {@code minecraft:diamond_axe};
     *               never null
     * @return the mainhand ATTACK_DAMAGE modifier sum; 0 when the id
     *         is not a weapon
     */
    float perHitDamage(String itemId);
}
