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
        Map<String, FoodFacts> table = new HashMap<>();
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            Item item = entry.getValue();
            FoodProperties props = item.getFoodProperties();
            if (props != null) {
                table.put(
                        entry.getKey().toString(), new FoodFacts(props.getNutrition(), props.getSaturationModifier()));
            }
        }
        return table;
    }
}
