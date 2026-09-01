package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * WeaponCatalog over the frozen vanilla item registry: every item's
 * default MAINHAND ATTACK_DAMAGE modifier sum - exactly the value the
 * LivingEntity equipment machinery adds as a transient modifier when
 * the item is held. Generic on purpose: swords, axes, pickaxes, and
 * tridents all register the same way, so there is no hardcoded item
 * list to rot; hoes net +0 and drop out by the positive filter.
 *
 * <p>Constructed at assembly time, after the registries froze at
 * server start; the table never changes inside one boot.
 */
public final class VanillaWeaponCatalog implements WeaponCatalog {

    private final Map<String, Float> table = build();

    /** Creates a catalog over the current item registry. */
    public VanillaWeaponCatalog() {}

    @Override
    public float perHitDamage(String itemId) {
        return table.getOrDefault(itemId, 0f);
    }

    @Feature(
            id = "combat.melee.vanilla_weapon_catalog",
            face = "combat.melee",
            description = "Weapon damage catalog built from the frozen vanilla item registry: every item's default"
                    + " MAINHAND ATTACK_DAMAGE ADDITION modifier sum. Generic — swords, axes, pickaxes, tridents all"
                    + " register the same way, no hardcoded item list. Hoes net +0 and drop out by the positive"
                    + " filter. Constructed once at assembly after registries freeze.",
            vanillaRef = "Item.getDefaultAttributeModifiers + Attributes.ATTACK_DAMAGE (decompiled 1.20.1)",
            deviation = "Bot-specific: the catalog is the single source of truth for per-hit damage ranking; without"
                    + " it the behavior tier cannot tell a sword from a rock and must not hijack the swing rim.")
    private static Map<String, Float> build() {
        Map<String, Float> table = new HashMap<>();
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            Item item = entry.getValue();
            float damage = 0f;
            for (AttributeModifier modifier :
                    item.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                    damage += (float) modifier.getAmount();
                }
            }
            if (damage > 0f) {
                table.put(entry.getKey().toString(), damage);
            }
        }
        return table;
    }
}
