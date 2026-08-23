package com.mcbot.mcbotserver.core.event;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-shape gate for the harness JSON dialect: the rendered keys,
 * their types, and the escaping of hostile text must survive a parse
 * round-trip, because an external consumer depends on exactly this
 * shape.
 */
// contract: see boundaries.md Boundary D protocol (event stream shape)
class EventBatchJsonTest {

    @Test
    void batchFieldsRoundTrip() {
        BotEvent event = new BotEvent("TASK_FAILED", 3, 12000, true,
            Map.of("taskId", "task-7", "reason", "NO_PATH"),
            "task-7 failed");
        EventBatch batch = new EventBatch(List.of(event), 2, 41L, 5L);

        JsonObject root = JsonParser.parseString(
            EventBatchJson.toJson(batch)).getAsJsonObject();

        assertEquals(41L, root.get("latest").getAsLong());
        assertEquals(5L, root.get("resetAt").getAsLong());
        assertEquals(2, root.get("dropped").getAsInt());
        assertEquals(1, root.getAsJsonArray("events").size());
        JsonObject node = root.getAsJsonArray("events").get(0)
            .getAsJsonObject();
        assertEquals("TASK_FAILED", node.get("kind").getAsString());
        assertEquals(3, node.get("day").getAsLong());
        assertEquals(12000, node.get("t").getAsLong());
        assertTrue(node.get("urgent").getAsBoolean());
        assertEquals("task-7 failed", node.get("text").getAsString());
        assertEquals("NO_PATH",
            node.getAsJsonObject("attrs").get("reason").getAsString());
        assertEquals("task-7",
            node.getAsJsonObject("attrs").get("taskId").getAsString());
    }

    @Test
    void emptyBatchRendersEmptyEventsArray() {
        EventBatch batch = new EventBatch(List.of(), 0, 0L, 1L);
        JsonObject root = JsonParser.parseString(
            EventBatchJson.toJson(batch)).getAsJsonObject();
        assertTrue(root.getAsJsonArray("events").isEmpty(),
            "no events must render as [], never null or missing");
    }

    @Test
    void hostileTextSurvivesSingleLineRender() {
        BotEvent event = new BotEvent("KEEPALIVE", 1, 100, false,
            Map.of(), "quote \" backslash \\ newline-in-value not ok");
        String line = EventBatchJson.toJson(new EventBatch(
            List.of(event), 0, 9L, 1L));

        assertFalse(line.contains("\n"),
            "transport dialect is one line per response");
        JsonObject parsed = JsonParser.parseString(line)
            .getAsJsonObject();
        assertEquals(
            "quote \" backslash \\ newline-in-value not ok",
            parsed.getAsJsonArray("events").get(0).getAsJsonObject()
                .get("text").getAsString(),
            "escaping must preserve the exact payload text");
    }
}
