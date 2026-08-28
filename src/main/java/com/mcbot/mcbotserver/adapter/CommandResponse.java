package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Shared JSON response helpers for console command surfaces. Extracted
 * from BotCommands and WorldCommands (2026-08-27 CPD dedup): both
 * classes answered every command in the same single-line JSON shape,
 * and the three helpers were byte-identical.
 *
 * <p>Every command answer is a JSON object with at minimum an "ok"
 * boolean; the harness parses this line over RCON.
 */
public final class CommandResponse {

    private CommandResponse() {}

    /**
     * Builds the success envelope.
     *
     * @return a JSON object with {@code ok: true}
     */
    public static JsonObject ok() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        return root;
    }

    /**
     * Builds the failure envelope with a machine-readable reason.
     *
     * @param reason structural or world cause; never null
     * @return a JSON object with {@code ok: false} and the reason
     */
    public static JsonObject err(String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.addProperty("reason", reason);
        return root;
    }

    /**
     * The standard refusal for every command executed before
     * {@code /botspawn} (or after despawn): the uniform envelope with
     * the machine-readable "no active bot" reason. The literal is
     * wire vocabulary - the harness keys its retry pacing on it.
     *
     * @param src command source to answer; never null
     * @return 0 (brigadier failure code)
     */
    public static int noActiveBot(CommandSourceStack src) {
        return answer(src, err("no active bot"));
    }

    /**
     * Sends the JSON line to the command source: success path uses
     * sendSuccess, failure path uses sendFailure. Return code matches
     * the ok flag (1 on success, 0 on failure) so brigadier sees the
     * right outcome.
     *
     * @param src  command source to answer; never null
     * @param json response object; must carry an "ok" boolean
     * @return 1 when ok is true, 0 otherwise
     */
    public static int answer(CommandSourceStack src, JsonObject json) {
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
