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
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Boundary-D command surface over the server console: six verbs that
 * map one-to-one onto the in-JVM channels and answer in single-line
 * JSON, so an external harness can drive the bot through RCON without
 * the bot knowing anything about the transport.
 *
 * <p>The verb table is frozen contract surface (issue 0011 section
 * 2): status / goto / cancel / stop / events / reset. Every answer is
 * one JSON object with an {@code ok} boolean; failures carry a
 * machine-readable {@code reason}; output text is wire surface, same
 * status as the serializers' wire keys. New process kinds grow the
 * table by one row in the same shape, decided in the issue that ships
 * the process - never as an ad-hoc brigadier branch.
 *
 * <p>Threading evidence: DedicatedServer.runCommand dispatches every
 * RCON command through executeBlocking, so each executes lambda below
 * runs on the server tick thread - exactly the thread policy every
 * channel documents - and the answer travels back synchronously.
 */
// contract: see boundaries.md Boundary D protocol (sync error path)
//            + decision 28 (the console verb table is a contract)
public final class BotCommands {

    /**
     * Live channel triple of one spawned bot, plus the stop lever and
     * the crash-latch reset.
     *
     * @param events the boundary-D event stream to poll; never null
     * @param bus    the command channel to submit through; never null
     * @param state  the state channel backing /bot status; never null
     * @param stopAllTasks cancels every live task and returns how
     *                     many; backs /bot stop; never null
     * @param resetCrashLatch clears the crash latch and counter
     *                     (ADR-0005 5a) and returns whether a latch
     *                     was actually set; backs /bot reset;
     *                     never null
     */
    public record Channels(
            EventQueue events,
            CommandBus bus,
            StateChannel state,
            IntSupplier stopAllTasks,
            BooleanSupplier resetCrashLatch) {}

    /**
     * Register the /bot subtree on the dispatcher.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       channel accessor returning null before the
     *                   first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Channels> live) {
        dispatcher.register(Commands.literal("bot")
                .requires(src -> src.hasPermission(2))
                .then(statusBranch(live))
                .then(gotoBranch(live))
                .then(digBranch(live))
                .then(mineBranch(live))
                .then(cancelBranch(live))
                .then(stopBranch(live))
                .then(eventsBranch(live))
                .then(resetBranch(live)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> statusBranch(
            Supplier<Channels> live) {
        return Commands.literal("status").executes(ctx -> {
            Channels ch = live.get();
            if (ch == null) {
                return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
            }
            var snap = ch.state().current();
            // Human line first - the JSON block is contract surface
            // for harnesses, not something a person should have to
            // read in chat.
            ctx.getSource()
                    .sendSuccess(
                            () -> Component.literal("Bot at [" + snap.pos().x() + ", "
                                    + snap.pos().y()
                                    + ", " + snap.pos().z() + "] in "
                                    + snap.dimension() + "; task: "
                                    + snap.currentTaskSummary()),
                            false);
            JsonObject root = CommandResponse.ok();
            root.add("state", BotStateJson.toJsonObject(snap));
            return CommandResponse.answer(ctx.getSource(), root);
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> gotoBranch(
            Supplier<Channels> live) {
        return Commands.literal("goto")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("tolerance", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("timeoutTicks", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> runGoto(ctx, live, null))
                                                        .then(Commands.argument("key", StringArgumentType.string())
                                                                .executes(ctx -> runGoto(
                                                                        ctx,
                                                                        live,
                                                                        StringArgumentType.getString(
                                                                                ctx, "key")))))))));
    }

    private static int runGoto(CommandContext<CommandSourceStack> ctx, Supplier<Channels> live, String key) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("x", String.valueOf(IntegerArgumentType.getInteger(ctx, "x")));
        args.put("y", String.valueOf(IntegerArgumentType.getInteger(ctx, "y")));
        args.put("z", String.valueOf(IntegerArgumentType.getInteger(ctx, "z")));
        args.put("tolerance", String.valueOf(IntegerArgumentType.getInteger(ctx, "tolerance")));
        args.put("timeoutTicks", String.valueOf(IntegerArgumentType.getInteger(ctx, "timeoutTicks")));
        return submitCommand(ctx, live, "goto", args, key);
    }

    /**
     * /bot dig x y z [timeoutTicks] - one-row table growth per
     * decision 28, sanctioned by issue 0013 R1 (dig is a task: the
     * executor is multi-tick and the INTERACT claim expires every
     * tick, so it rides CommandBus exactly like goto).
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> digBranch(
            Supplier<Channels> live) {
        var pos = Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> runDig(ctx, live, 1200L))
                                .then(Commands.argument("timeoutTicks", IntegerArgumentType.integer(1))
                                        .executes(ctx -> runDig(ctx, live, (long)
                                                IntegerArgumentType.getInteger(ctx, "timeoutTicks"))))));
        return Commands.literal("dig").then(pos);
    }

    private static int runDig(CommandContext<CommandSourceStack> ctx, Supplier<Channels> live, long timeoutTicks) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("x", String.valueOf(IntegerArgumentType.getInteger(ctx, "x")));
        args.put("y", String.valueOf(IntegerArgumentType.getInteger(ctx, "y")));
        args.put("z", String.valueOf(IntegerArgumentType.getInteger(ctx, "z")));
        args.put("timeoutTicks", String.valueOf(timeoutTicks));
        return submitCommand(ctx, live, "dig", args, null);
    }

    /**
     * /bot mine blockType count [timeoutTicks] - composite mining task
     * (issue 0014): search for nearby blocks of the given type, mine
     * them, collect drops, repeat until count is met. Rides CommandBus
     * like goto/dig; the MineProcess manages the search-mine-collect
     * loop internally.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> mineBranch(
            Supplier<Channels> live) {
        // string(), not word(): registry ids carry a colon and word()
        // cannot parse one - the caller must quote the id (same rule
        // as the menu verbs' item ids).
        var args = Commands.argument("blockType", StringArgumentType.string())
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> runMine(ctx, live, 2400L))
                        .then(Commands.argument("timeoutTicks", IntegerArgumentType.integer(1))
                                .executes(ctx -> runMine(
                                        ctx, live, (long) IntegerArgumentType.getInteger(ctx, "timeoutTicks")))));
        return Commands.literal("mine").then(args);
    }

    private static int runMine(CommandContext<CommandSourceStack> ctx, Supplier<Channels> live, long timeoutTicks) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("blockType", StringArgumentType.getString(ctx, "blockType"));
        args.put("count", String.valueOf(IntegerArgumentType.getInteger(ctx, "count")));
        args.put("timeoutTicks", String.valueOf(timeoutTicks));
        return submitCommand(ctx, live, "mine", args, null);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cancelBranch(
            Supplier<Channels> live) {
        return Commands.literal("cancel")
                .then(Commands.argument("taskId", StringArgumentType.word()).executes(ctx -> {
                    Channels ch = live.get();
                    if (ch == null) {
                        return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
                    }
                    String taskId = StringArgumentType.getString(ctx, "taskId");
                    boolean removed = ch.bus().cancel(taskId);
                    return CommandResponse.answer(
                            ctx.getSource(),
                            removed ? CommandResponse.ok() : CommandResponse.err("no such task: " + taskId));
                }));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> stopBranch(
            Supplier<Channels> live) {
        return Commands.literal("stop").executes(ctx -> {
            Channels ch = live.get();
            if (ch == null) {
                return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
            }
            int cancelled = ch.stopAllTasks().getAsInt();
            ctx.getSource()
                    .sendSuccess(
                            () -> Component.literal("stopped " + cancelled + " task" + (cancelled == 1 ? "" : "s")),
                            false);
            JsonObject root = CommandResponse.ok();
            root.addProperty("cancelled", cancelled);
            return CommandResponse.answer(ctx.getSource(), root);
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eventsBranch(
            Supplier<Channels> live) {
        // `only` is a kind PREFIX (TASK_, BOT_, STATE_...), not an
        // exact kind - the named use case is "task lifecycle only".
        // Unquoted single word by brigadier rules; no commas. The
        // narrowing keeps EVENT_GAP / EVENT_DROPPED and the true
        // cursor fields - see EventBatch.narrowedToKindPrefix.
        return Commands.literal("events")
                .executes(ctx -> runEvents(ctx, live, 0L, ""))
                .then(Commands.argument("since", IntegerArgumentType.integer(0))
                        .executes(ctx -> runEvents(ctx, live, IntegerArgumentType.getInteger(ctx, "since"), ""))
                        .then(Commands.argument("only", StringArgumentType.word())
                                .executes(ctx -> runEvents(
                                        ctx,
                                        live,
                                        IntegerArgumentType.getInteger(ctx, "since"),
                                        StringArgumentType.getString(ctx, "only")))));
    }

    private static int runEvents(
            CommandContext<CommandSourceStack> ctx, Supplier<Channels> live, long since, String onlyPrefix) {
        Channels ch = live.get();
        if (ch == null) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
        }
        JsonObject root = CommandResponse.ok();
        root.add(
                "batch",
                EventBatchJson.toJsonObject(ch.events().statusSnapshot(since).narrowedToKindPrefix(onlyPrefix)));
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * The crash-latch reset ADR-0005 5a always promised the harness:
     * clears latch and counter, next tick runs the full pipeline
     * again. {@code crashed} echoes whether a latch was actually set,
     * so a harness resetting a healthy bot learns it did nothing.
     */
    // contract: see ADR-0005 5a (reset clears latch + counter)
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> resetBranch(
            Supplier<Channels> live) {
        return Commands.literal("reset").executes(ctx -> {
            Channels ch = live.get();
            if (ch == null) {
                return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
            }
            boolean wasCrashed = ch.resetCrashLatch().getAsBoolean();
            JsonObject root = CommandResponse.ok();
            root.addProperty("crashed", wasCrashed);
            return CommandResponse.answer(ctx.getSource(), root);
        });
    }

    /**
     * Submits a command through the bus and renders the standard
     * ok/rejected JSON response. Extracted from runGoto/runDig/runMine
     * to eliminate CPD duplication (all three shared the same null-check
     * + submit + response-render shape).
     *
     * @param ctx     brigadier context for the answer target
     * @param live    live channel supplier
     * @param command verb name, e.g. "goto"
     * @param args    argument map; never null
     * @param key     idempotency key, or null for none
     * @return brigadier command return code (1 on ok, 0 on failure)
     */
    private static int submitCommand(
            CommandContext<CommandSourceStack> ctx,
            Supplier<Channels> live,
            String command,
            Map<String, String> args,
            String key) {
        Channels ch = live.get();
        if (ch == null) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("no active bot"));
        }
        SubmitResult result = ch.bus().submit(new BotCommand(command, args), key);
        JsonObject root = new JsonObject();
        if (result instanceof SubmitResult.Ok accepted) {
            root.addProperty("ok", true);
            root.addProperty("task", accepted.taskId());
            root.addProperty("replay", accepted.idempotencyReplay());
        } else {
            root.addProperty("ok", false);
            root.addProperty("reason", ((SubmitResult.Rejected) result).reason());
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }
}
