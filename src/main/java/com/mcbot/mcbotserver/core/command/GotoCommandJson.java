package com.mcbot.mcbotserver.core.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.core.JsonFields;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON wire form for boundary-D commands: {@code {"verb":"goto",
 * "args":{"x":"10","y":"64","z":"-3"}}}. Argument values are strings
 * on the wire regardless of their natural type — the verb's validator
 * owns parsing, so the codec stays vocabulary-blind.
 *
 * <p>Contract: see boundaries.md decision 18 (vocabulary may grow;
 * seam semantics frozen). Gson is a plain JVM library here, not an MC
 * import — the zero-MC gate stays green.
 */
// contract: see boundaries.md decision 18 (first boundary-D semantics)
public final class GotoCommandJson {

    private GotoCommandJson() {}

    /**
     * Parse one wire command.
     *
     * @param json the wire payload; must be a JSON object with a
     *             string "verb" and optional object "args"
     * @return the decoded command envelope; never null
     * @throws com.google.gson.JsonParseException when the payload is
     *         not valid JSON or lacks the required shape
     */
    public static BotCommand fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String verb = JsonFields.require(root, "verb").getAsString();
        Map<String, String> args = new HashMap<>();
        if (root.has("args")) {
            var argsEl = JsonFields.require(root, "args");
            if (argsEl.isJsonObject()) {
                JsonObject raw = argsEl.getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> e : raw.entrySet()) {
                    args.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return new BotCommand(verb, args);
    }

    /**
     * Render one command back to the wire form.
     *
     * @param command the envelope to encode; never null
     * @return compact JSON; never null
     */
    public static String toJson(BotCommand command) {
        JsonObject root = new JsonObject();
        root.addProperty("verb", command.verb());
        JsonObject args = new JsonObject();
        command.args().forEach(args::addProperty);
        root.add("args", args);
        return root.toString();
    }
}
