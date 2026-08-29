package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.inventory.FoodCatalog;
import com.mcbot.mcbotserver.api.inventory.FoodCatalog.FoodFacts;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * FoodCatalog over the frozen vanilla item registry: every item with
 * a FoodProperties becomes its nutrition/saturation/canAlwaysEat
 * triple. Eating effects (rotten flesh's hunger chance, golden apple
 * buffs) are NOT classified here - they ride the vanilla
 * finishUsingItem path the body calls, not any ranking.
 *
 * <p>Constructed at assembly time, after the registries froze; the
 * table never changes inside one boot.
 */
public final class VanillaFoodCatalog implements FoodCatalog {

    private final Map<String, FoodFacts> table = build();

    /** Creates a catalog over the current item registry. */
    public VanillaFoodCatalog() {}

    @Override
    @Nullable
    public FoodFacts facts(String itemId) {
        return table.get(itemId);
    }

    private static Map<String, FoodFacts> build() {
        // Key-driven enumeration (getKeys + getValue), NOT getEntries():
        // the entry-set iteration resolved items whose food properties
        // read null at build time while direct lookups of the SAME ids
        // returned the live item (engine evidence 2026-08-29: table of
        // 40 via entries, 1255 registry entries, bread missing from the
        // table yet edible via getValue). Building through the same
        // path the scan-time lookups use keeps the table consistent
        // with what facts() will be asked for.
        Map<String, FoodFacts> table = new HashMap<>();
        for (var key : ForgeRegistries.ITEMS.getKeys()) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            FoodProperties props = item.getFoodProperties();
            if (props != null) {
                table.put(key.toString(), new FoodFacts(props.getNutrition(), props.getSaturationModifier()));
            }
        }
        return table;
    }
}
