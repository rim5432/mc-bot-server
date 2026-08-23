package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.state.StateChannel;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.event.EventBatchJson;
import com.mcbot.mcbotserver.core.state.BotStateJson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Boundary-D command surface over the server console: four verbs that
 * map one-to-one onto the in-JVM channels and answer in single-line
 * JSON, so an external harness can drive the bot through RCON without
 * the bot knowing anything about the transport.
 *
 * <p>Threading evidence: DedicatedServer.runCommand dispatches every
 * RCON command through executeBlocking, so each executes lambda below
 * runs on the server tick thread - exactly the thread policy every
 * channel documents - and the answer travels back synchronously.
 *
 * <p>Every response is one JSON object with an {@code ok} boolean;
 * failures carry a machine-readable {@code reason}. Output text is
 * contract surface, same status as the serializers' wire keys.
 */
// contract: see boundaries.md Boundary D protocol (sync error path)
public final class BotCommands {

    /**
     * Live channel triple of one spawned bot.
     *
     * @param events the boundary-D event stream to poll; never null
     * @param bus    the command channel to submit through; never null
     * @param state  the state channel backing /bot status; never null
     */
    public record Channels(EventQueue events, CommandBus bus,
                           StateChannel state) {
    }

    /**
     * Register the /bot subtree on the dispatcher.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       channel accessor returning null before the
     *                   first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<Channels> live) {
        dispatcher.register(Commands.literal("bot")
            .requires(src -> src.hasPermission(2))
            .then(statusBranch(live))
            .then(gotoBranch(live))
            .then(cancelBranch(live))
            .then(eventsBranch(live)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> statusBranch(
            Supplier<Channels> live) {
        return Commands.literal("status").executes(ctx -> {
            Channels ch = live.get();
            if (ch == null) {
                return answer(ctx.getSource(), err("no active bot"));
            }
            JsonObject root = ok();
            root.add("state",
                BotStateJson.toJsonObject(ch.state().current()));
            return answer(ctx.getSource(), root);
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gotoBranch(
            Supplier<Channels> live) {
        return Commands.literal("goto")
            .then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .then(Commands.argument("tolerance",
                                    IntegerArgumentType.integer(0))
                            .then(Commands.argument("timeoutTicks",
                                        IntegerArgumentType.integer(1))
                                .executes(ctx ->
                                    runGoto(ctx, live, null))
                                .then(Commands.argument("key",
                                            StringArgumentType.string())
                                    .executes(ctx -> runGoto(ctx, live,
                                        StringArgumentType.getString(ctx,
                                            "key")))))))));
    }

    private static int runGoto(CommandContext<CommandSourceStack> ctx,
                               Supplier<Channels> live, String key) {
        Channels ch = live.get();
        if (ch == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        Map<String, String> args = new LinkedHashMap<>();
        args.put("x", String.valueOf(
            IntegerArgumentType.getInteger(ctx, "x")));
        args.put("y", String.valueOf(
            IntegerArgumentType.getInteger(ctx, "y")));
        args.put("z", String.valueOf(
            IntegerArgumentType.getInteger(ctx, "z")));
        args.put("tolerance", String.valueOf(
            IntegerArgumentType.getInteger(ctx, "tolerance")));
        args.put("timeoutTicks", String.valueOf(
            IntegerArgumentType.getInteger(ctx, "timeoutTicks")));
        SubmitResult result = ch.bus().submit(
            new BotCommand("goto", args), key);
        JsonObject root = new JsonObject();
        if (result instanceof SubmitResult.Ok accepted) {
            root.addProperty("ok", true);
            root.addProperty("task", accepted.taskId());
            root.addProperty("replay", accepted.idempotencyReplay());
        } else {
            root.addProperty("ok", false);
            root.addProperty("reason",
                ((SubmitResult.Rejected) result).reason());
        }
        return answer(ctx.getSource(), root);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cancelBranch(
            Supplier<Channels> live) {
        return Commands.literal("cancel")
            .then(Commands.argument("taskId",
                    StringArgumentType.word())
                .executes(ctx -> {
                    Channels ch = live.get();
                    if (ch == null) {
                        return answer(ctx.getSource(),
                            err("no active bot"));
                    }
                    String taskId =
                        StringArgumentType.getString(ctx, "taskId");
                    boolean removed = ch.bus().cancel(taskId);
                    return answer(ctx.getSource(), removed
                        ? ok()
                        : err("no such task: " + taskId));
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eventsBranch(
            Supplier<Channels> live) {
        return Commands.literal("events")
            .executes(ctx -> runEvents(ctx, live, 0L))
            .then(Commands.argument("since",
                        IntegerArgumentType.integer(0))
                .executes(ctx -> runEvents(ctx, live,
                    IntegerArgumentType.getInteger(ctx, "since"))));
    }

    private static int runEvents(CommandContext<CommandSourceStack> ctx,
                                 Supplier<Channels> live, long since) {
        Channels ch = live.get();
        if (ch == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        JsonObject root = ok();
        root.add("batch",
            EventBatchJson.toJsonObject(ch.events().statusSnapshot(since)));
        return answer(ctx.getSource(), root);
    }

    private static JsonObject ok() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        return root;
    }

    private static JsonObject err(String reason) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        root.addProperty("reason", reason);
        return root;
    }

    private static int answer(CommandSourceStack src, JsonObject json) {
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
