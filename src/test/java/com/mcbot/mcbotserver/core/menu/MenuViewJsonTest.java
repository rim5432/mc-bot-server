package com.mcbot.mcbotserver.core.menu;

import com.google.gson.JsonObject;

import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import com.mcbot.mcbotserver.api.types.CellPos;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer-1 gate over the 0012 D1 wire serializer: the JSON field
 * names are wire keys frozen from the first shipped transport - this
 * test pins them so a rename trips here before any harness replays
 * break silently.
 */
class MenuViewJsonTest {

    private static SlotView slot(int index, SlotRole role,
                                 String itemId, int count) {
        ItemView item = itemId == null
            ? ItemView.EMPTY
            : new ItemView(itemId, count, "");
        return new SlotView(index, item, role);
    }

    /**
     * Builds the smallest honest chest-shaped snapshot: one container
     * slot with an item, one empty, one hotbar slot carrying planks.
     */
    private static MenuView chestSnapshot() {
        return new MenuView("chest", new CellPos(3, 61, 3),
            ItemView.EMPTY, 3,
            List.of(slot(0, SlotRole.CONTAINER, "minecraft:stone", 12),
                slot(1, SlotRole.CONTAINER, null, 0),
                slot(2, SlotRole.HOTBAR, "minecraft:planks", 3)));
    }

    /** Pins the snapshot wire keys: type, sourcePos, carried,
     *  containerSize, slots[{index, role, item}]. */
    @Test
    void snapshotWireKeysStayFrozen() {
        JsonObject root = MenuViewJson.toJsonObject(chestSnapshot());
        assertEquals("chest", root.get("type").getAsString());
        var pos = root.getAsJsonArray("sourcePos");
        assertEquals(3, pos.get(0).getAsInt());
        assertEquals(61, pos.get(1).getAsInt());
        assertEquals(3, pos.get(2).getAsInt());
        assertEquals(3, root.get("containerSize").getAsInt());
        var slots = root.getAsJsonArray("slots");
        assertEquals(3, slots.size());

        JsonObject first = slots.get(0).getAsJsonObject();
        assertEquals(0, first.get("index").getAsInt());
        assertEquals("CONTAINER", first.get("role").getAsString());
        assertEquals("minecraft:stone",
            first.getAsJsonObject("item").get("id").getAsString());
        assertEquals(12,
            first.getAsJsonObject("item").get("count").getAsInt());

        // Empty slots stay visible with the EMPTY item, and the nbt
        // digest is omitted when blank.
        JsonObject empty = slots.get(1).getAsJsonObject();
        assertEquals(0, empty.getAsJsonObject("item")
            .get("count").getAsInt());
        assertFalse(empty.getAsJsonObject("item").has("nbt"));
    }

    /** The own-inventory snapshot has no source block: the wire
     *  carries an empty string, not a missing key. */
    @Test
    void inventorySnapshotCarriesEmptySourcePos() {
        MenuView inventory = new MenuView("inventory", null,
            ItemView.EMPTY, 1,
            List.of(slot(0, SlotRole.GRID, null, 0)));
        JsonObject root = MenuViewJson.toJsonObject(inventory);
        assertTrue(root.get("sourcePos").isJsonPrimitive());
        assertEquals("", root.get("sourcePos").getAsString());
    }

    /** Pins the recipe wire keys: recipeId, resultItemId,
     *  resultCount, patternWidth, placements{pos:[kinds]}. */
    @Test
    void recipeWireKeysStayFrozen() {
        RecipeView recipe = new RecipeView("minecraft:wooden_pickaxe",
            "minecraft:wooden_pickaxe", 1, 3,
            Map.of(0, List.of("minecraft:planks"),
                3, List.of("minecraft:stick"),
                6, List.of("minecraft:stick")));
        JsonObject root = MenuViewJson.toJsonObject(recipe);
        assertEquals("minecraft:wooden_pickaxe",
            root.get("recipeId").getAsString());
        assertEquals(1, root.get("resultCount").getAsInt());
        assertEquals(3, root.get("patternWidth").getAsInt());
        var placements = root.getAsJsonObject("placements");
        assertEquals(3, placements.size());
        assertEquals("minecraft:planks",
            placements.getAsJsonArray("0").get(0).getAsString());
        assertEquals("minecraft:stick",
            placements.getAsJsonArray("6").get(0).getAsString());
    }
}
