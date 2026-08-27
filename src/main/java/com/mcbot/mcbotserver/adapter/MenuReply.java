package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Shared RCON reply plumbing for the menu command surface: the
 * uniform {@code ok/reason} envelope and the single send path.
 */
final class MenuReply {

    private MenuReply() {}

    static JsonObject ok() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        return root;
    }

    static JsonObject err(String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.addProperty("reason", reason);
        return root;
    }

    /** Uniform success/failure exit: 1 sent as success, else failure echo. */
    static int answer(CommandSourceStack src, JsonObject json) {
        String line = json.toString();
        boolean good = json.has("ok") && json.get("ok").getAsBoolean();
        if (good) {
            src.sendSuccess(() -> Component.literal(line), false);
            return 1;
        }
        src.sendFailure(Component.literal(line));
        return 0;
    }
}
