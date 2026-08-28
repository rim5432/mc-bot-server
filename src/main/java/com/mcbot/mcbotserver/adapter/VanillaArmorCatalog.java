package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.ArmorCatalog;
import com.mcbot.mcbotserver.api.menu.ArmorCatalog.ArmorPiece;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * ArmorCatalog over the frozen vanilla item registry: every
 * {@link ArmorItem} classified into the binding menu's armor slot
 * (flat 5..8, head..feet - the MenuSlotLayouts survival layout) with
 * its defense value as the protection rank.
 *
 * <p>Constructed per menu command, after the registries froze at
 * server start; the classification table never changes inside one
 * boot.
 */
public final class VanillaArmorCatalog implements ArmorCatalog {

    private final Map<String, ArmorPiece> table = build();

    /** Creates a catalog over the current item registry. */
    public VanillaArmorCatalog() {}

    @Override
    @Nullable
    public ArmorPiece classify(String itemId) {
        return table.get(itemId);
    }

    private static Map<String, ArmorPiece> build() {
        Map<String, ArmorPiece> table = new HashMap<>();
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            if (entry.getValue() instanceof ArmorItem armor) {
                EquipmentSlot slot = armor.getEquipmentSlot();
                Integer flat =
                        switch (slot) {
                            case HEAD -> 5;
                            case CHEST -> 6;
                            case LEGS -> 7;
                            case FEET -> 8;
                            default -> null;
                        };
                if (flat != null) {
                    table.put(entry.getKey().location().toString(), new ArmorPiece(flat, armor.getDefense()));
                }
            }
        }
        return table;
    }
}
