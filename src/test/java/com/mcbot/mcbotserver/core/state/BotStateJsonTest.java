package com.mcbot.mcbotserver.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wire-shape gate for the harness JSON dialect: the rendered keys,
 * their types, and empty-map handling must survive a parse round-trip,
 * because an external consumer depends on exactly this shape.
 */
// contract: see boundaries.md Boundary D protocol (state snapshot shape)
class BotStateJsonTest {

    @Test
    void snapshotFieldsRoundTrip() {
        BotState state = new BotState(
                new CellPos(10, 64, -3),
                45.5f,
                -10f,
                "overworld",
                Map.of("minecraft:stone", 32, "minecraft:torch", 2),
                4,
                Map.of("minecraft:speed", 1),
                "goto x=20 y=64 z=-3",
                20,
                5,
                17);

        JsonObject root = JsonParser.parseString(BotStateJson.toJson(state)).getAsJsonObject();

        assertEquals(10, root.getAsJsonArray("pos").get(0).getAsInt());
        assertEquals(64, root.getAsJsonArray("pos").get(1).getAsInt());
        assertEquals(-3, root.getAsJsonArray("pos").get(2).getAsInt());
        assertEquals(45.5f, root.get("yaw").getAsFloat(), 0.001f);
        assertEquals(-10f, root.get("pitch").getAsFloat(), 0.001f);
        assertEquals("overworld", root.get("dim").getAsString());
        assertEquals(32, root.getAsJsonObject("items").get("minecraft:stone").getAsInt());
        assertEquals(4, root.get("slot").getAsInt());
        assertEquals(1, root.getAsJsonObject("effects").get("minecraft:speed").getAsInt());
        assertEquals("goto x=20 y=64 z=-3", root.get("task").getAsString());
    }

    @Test
    void emptyMapsRenderAsObject() {
        BotState state =
                new BotState(new CellPos(0, 0, 0), 0f, 0f, "overworld", Map.of(), 0, Map.of(), "idle", 20, 0, 20);
        JsonObject root = JsonParser.parseString(BotStateJson.toJson(state)).getAsJsonObject();

        assertTrue(root.getAsJsonObject("items").isEmpty(), "empty items must render as {}, never null");
        assertTrue(root.getAsJsonObject("effects").isEmpty(), "empty effects must render as {}, never null");
    }
}
