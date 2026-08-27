package com.mcbot.mcbotserver.core.event;

import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;

/**
 * Serializes an {@link EventBatch} into the single-line JSON dialect
 * the harness transport speaks. Field names are wire keys: they are
 * frozen from the first shipped transport onward, so renames here are
 * boundary-D breaks, not refactors.
 *
 * <p>The gap signal stays structural: a synthetic EVENT_GAP entry
 * arrives inside {@code events}, never as a boolean flag, matching
 * the queue's own reporting shape.
 *
 * <p>Implementation note: zero MC imports; runs offline in layer-1
 * tests.
 */
// contract: see boundaries.md Boundary D protocol (event stream shape)
public final class EventBatchJson {

    private EventBatchJson() {}

    /**
     * Render the batch as one compact JSON line.
     *
     * @param batch the batch to render; never null
     * @return single-line JSON; never null, no whitespace between
     *         tokens
     */
    public static String toJson(EventBatch batch) {
        return toJsonObject(batch).toString();
    }

    /**
     * Render the batch as a JSON object for embedding in larger
     * envelopes.
     *
     * @param batch the batch to render; never null
     * @return object with keys latest, resetAt, dropped, events;
     *         never null
     */
    public static JsonObject toJsonObject(EventBatch batch) {
        JsonObject root = new JsonObject();
        root.addProperty("latest", batch.latestEventId());
        root.addProperty("resetAt", batch.resetAt());
        root.addProperty("dropped", batch.droppedCount());
        var events = new com.google.gson.JsonArray();
        for (BotEvent event : batch.events()) {
            events.add(toJsonObject(event));
        }
        root.add("events", events);
        return root;
    }

    private static JsonObject toJsonObject(BotEvent event) {
        JsonObject node = new JsonObject();
        node.addProperty("kind", event.kind());
        node.addProperty("day", event.day());
        node.addProperty("t", event.t());
        node.addProperty("urgent", event.urgent());
        node.addProperty("text", event.text());
        JsonObject attrs = new JsonObject();
        event.attrs().forEach(attrs::addProperty);
        node.add("attrs", attrs);
        return node;
    }
}
