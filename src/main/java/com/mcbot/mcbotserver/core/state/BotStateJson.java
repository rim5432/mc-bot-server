package com.mcbot.mcbotserver.core.state;

import com.mcbot.mcbotserver.api.state.BotState;
import com.google.gson.JsonObject;

/**
 * Serializes a {@link BotState} snapshot into the single-line JSON
 * dialect the harness transport speaks. Field names are wire keys:
 * frozen from the first shipped transport onward, so renames here are
 * boundary-D breaks, not refactors.
 *
 * <p>Implementation note: zero MC imports; runs offline in layer-1
 * tests.
 */
// contract: see boundaries.md Boundary D protocol (state snapshot shape)
public final class BotStateJson {

    private BotStateJson() {
    }

    /**
     * Render the snapshot as one compact JSON line.
     *
     * @param state the snapshot to render; never null
     * @return single-line JSON; never null, no whitespace between
     *         tokens
     */
    public static String toJson(BotState state) {
        return toJsonObject(state).toString();
    }

    /**
     * Render the snapshot as a JSON object for embedding in larger
     * envelopes.
     *
     * @param state the snapshot to render; never null
     * @return object with keys pos, yaw, pitch, dim, items, slot,
     *         effects, task; never null
     */
    public static JsonObject toJsonObject(BotState state) {
        JsonObject root = new JsonObject();
        var pos = new com.google.gson.JsonArray();
        pos.add(state.pos().x());
        pos.add(state.pos().y());
        pos.add(state.pos().z());
        root.add("pos", pos);
        root.addProperty("yaw", state.yaw());
        root.addProperty("pitch", state.pitch());
        root.addProperty("dim", state.dimension());
        JsonObject items = new JsonObject();
        state.itemCounts().forEach(items::addProperty);
        root.add("items", items);
        root.addProperty("slot", state.selectedHotbarSlot());
        JsonObject effects = new JsonObject();
        state.effectAmplifiers().forEach(effects::addProperty);
        root.add("effects", effects);
        root.addProperty("task", state.currentTaskSummary());
        root.addProperty("healthHearts", state.healthHearts());
        root.addProperty("freeSlots", state.freeSlots());
        return root;
    }
}
