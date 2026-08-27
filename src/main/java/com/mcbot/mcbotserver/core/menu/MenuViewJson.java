package com.mcbot.mcbotserver.core.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotView;
import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Serializes menu snapshots and recipes into the single-line JSON
 * dialect the harness transport speaks (issue 0012 D1 menu command
 * batch). Field names are wire keys: frozen from the first shipped
 * transport onward, renames are boundary-D breaks, not refactors.
 *
 * <p>Shape (L0 generic disclosure, no per-kind fields): every slot
 * carries its flat index, its {@link com.mcbot.mcbotserver.api.menu.SlotRole
 * role} name, and its item; roles are how harness-generic recipes
 * ("click every CONTAINER slot", "grid slots are GRID") work without
 * per-kind code.
 *
 * <p>Implementation note: zero MC imports; runs offline in layer-1
 * tests.
 */
// contract: see boundaries.md section D (wire serializer) + issue
//            0012 D1 (menu command batch, L0 disclosure shape)
public final class MenuViewJson {

    private MenuViewJson() {}

    /**
     * Render a menu snapshot for the wire.
     *
     * @param view the snapshot; never null
     * @return object with keys type, sourcePos, carried,
     *         containerSize, slots; never null
     */
    public static JsonObject toJsonObject(MenuView view) {
        JsonObject root = new JsonObject();
        root.addProperty("type", view.type());
        CellPos source = view.sourcePos();
        if (source != null) {
            JsonArray pos = new JsonArray();
            pos.add(source.x());
            pos.add(source.y());
            pos.add(source.z());
            root.add("sourcePos", pos);
        } else {
            root.addProperty("sourcePos", "");
        }
        root.add("carried", itemJson(view.carried()));
        root.addProperty("containerSize", view.containerSize());
        JsonArray slots = new JsonArray();
        for (SlotView slot : view.slots()) {
            slots.add(slotJson(slot));
        }
        root.add("slots", slots);
        return root;
    }

    /**
     * Render a recipe description for the wire.
     *
     * @param recipe the recipe; never null
     * @return object with keys recipeId, resultItemId, resultCount,
     *         patternWidth, shapeless, placements (pattern position
     *         to accepted item ids; ingredient indices when
     *         shapeless); never null
     */
    public static JsonObject toJsonObject(RecipeView recipe) {
        JsonObject root = new JsonObject();
        root.addProperty("recipeId", recipe.recipeId());
        root.addProperty("resultItemId", recipe.resultItemId());
        root.addProperty("resultCount", recipe.resultCount());
        root.addProperty("patternWidth", recipe.patternWidth());
        root.addProperty("shapeless", recipe.shapeless());
        JsonObject placements = new JsonObject();
        recipe.placements().forEach((pos, kinds) -> {
            JsonArray accepted = new JsonArray();
            kinds.forEach(accepted::add);
            placements.add(String.valueOf(pos), accepted);
        });
        root.add("placements", placements);
        return root;
    }

    private static JsonObject slotJson(SlotView slot) {
        JsonObject node = new JsonObject();
        node.addProperty("index", slot.index());
        node.addProperty("role", slot.role().name());
        node.add("item", itemJson(slot.item()));
        return node;
    }

    private static JsonObject itemJson(ItemView item) {
        JsonObject node = new JsonObject();
        node.addProperty("id", item.itemId());
        node.addProperty("count", item.count());
        if (item.nbtDigest() != null && !item.nbtDigest().isBlank()) {
            node.addProperty("nbt", item.nbtDigest());
        }
        return node;
    }
}
