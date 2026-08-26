package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Boundary-D perception reads (issue 0013 slice 1): synchronous
 * read-only commands over the live {@link WorldView}. Read family,
 * same shape as the menu reads - one JSON object per answer with an
 * {@code ok} boolean, no CommandBus, no events; the reply IS the
 * read.
 *
 * <p>Volume reads are capped (4x4x4 = 64 cells, ~3KB) because Source
 * RCON chunks responses at 4096 bytes - the cap is a wire constraint,
 * not convenience (issue 0012's recipes pagination rule, same
 * rationale).
 *
 * <p>Threading evidence: DedicatedServer.runCommand dispatches RCON
 * through executeBlocking, so every executes lambda runs on the
 * server thread between ticks - exactly the LIVE ViewMode contract.
 */
// contract: see boundaries.md section D + issue 0013 R4 (perception
//            reads are synchronous WorldView calls on the server thread)
public final class WorldCommands {

    /**
     * Live perception surface of one spawned bot.
     *
     * @param view   the world read surface; never null
     * @param botPos the bot body's block position (entities center);
     *               never null
     * @param botId  the bot body's UUID string (self-flagging in
     *               entity listings); never null
     */
    public record Live(WorldView view, Supplier<CellPos> botPos,
                       Supplier<String> botId) {
    }

    /**
     * Register the block / blocks / entities subtrees.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       perception accessor returning null before the
     *                   first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<Live> live) {
        var block = Commands.literal("block")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> runBlock(ctx, live)))));
        var blocks = Commands.literal("blocks")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .then(volumeArg("dx", live,
                            volumeArg("dy", live, null))))));
        var entities = Commands.literal("entities")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> runEntities(live, ctx.getSource(), 8, 32))
            .then(Commands.argument("radius",
                        IntegerArgumentType.integer(1, 64))
                .executes(ctx -> runEntities(live, ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "radius"), 32))
                .then(Commands.argument("limit",
                            IntegerArgumentType.integer(1, 128))
                    .executes(ctx -> runEntities(live, ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"),
                        IntegerArgumentType.getInteger(ctx, "limit")))));
        dispatcher.register(block);
        dispatcher.register(blocks);
        dispatcher.register(entities);
    }

    /**
     * Builds one axis of the blocks-volume argument chain. The chain
     * nests x -> y -> z -> dx -> dy -> dz; each level passes its
     * accumulated builder down so the innermost executes lambda sees
     * every argument by name.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> volumeArg(
            String name, Supplier<Live> live,
            com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> next) {
        var arg = Commands.argument(name, IntegerArgumentType.integer(1, 4));
        if (next == null) {
            arg.executes(ctx -> runBlocks(ctx, live));
        } else {
            arg.then(next);
        }
        return arg;
    }

    private static int runBlock(CommandContext<CommandSourceStack> ctx,
                                Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        CellPos pos = new CellPos(
            IntegerArgumentType.getInteger(ctx, "x"),
            IntegerArgumentType.getInteger(ctx, "y"),
            IntegerArgumentType.getInteger(ctx, "z"));
        BlockSnapshot snap = l.view().getBlock(pos, ViewMode.LIVE);
        if (snap == null) {
            return answer(ctx.getSource(), err("chunk not loaded"));
        }
        JsonObject root = ok();
        JsonArray p = new JsonArray();
        p.add(pos.x());
        p.add(pos.y());
        p.add(pos.z());
        root.add("pos", p);
        root.addProperty("block", snap.blockId());
        return answer(ctx.getSource(), root);
    }

    private static int runBlocks(CommandContext<CommandSourceStack> ctx,
                                 Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        int dx = IntegerArgumentType.getInteger(ctx, "dx");
        int dy = IntegerArgumentType.getInteger(ctx, "dy");
        int dz = IntegerArgumentType.getInteger(ctx, "dz");
        JsonObject cells = new JsonObject();
        int unknown = 0;
        for (int iy = 0; iy < dy; iy++) {
            for (int iz = 0; iz < dz; iz++) {
                for (int ix = 0; ix < dx; ix++) {
                    CellPos pos = new CellPos(x + ix, y + iy, z + iz);
                    BlockSnapshot snap =
                        l.view().getBlock(pos, ViewMode.LIVE);
                    String id = snap == null ? BlockSnapshot.UNKNOWN
                        : snap.blockId();
                    if (BlockSnapshot.UNKNOWN.equals(id)) {
                        unknown++;
                    }
                    cells.addProperty(
                        pos.x() + "," + pos.y() + "," + pos.z(), id);
                }
            }
        }
        JsonObject root = ok();
        root.addProperty("cells", dx * dy * dz);
        root.addProperty("unknown", unknown);
        root.add("blocks", cells);
        return answer(ctx.getSource(), root);
    }

    private static int runEntities(Supplier<Live> live,
                                   CommandSourceStack src, int radius,
                                   int limit) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        CellPos center = l.botPos().get();
        String selfId = l.botId().get();
        List<EntitySnapshot> found = new ArrayList<>(
            l.view().getEntities(center, radius, ViewMode.LIVE));
        found.sort(Comparator.comparingDouble(
            e -> dist(center, e)));
        boolean truncated = found.size() > limit;
        JsonArray entities = new JsonArray();
        for (EntitySnapshot e : found) {
            if (entities.size() >= limit) {
                break;
            }
            JsonObject node = new JsonObject();
            node.addProperty("id", e.id());
            node.addProperty("type", e.type());
            JsonArray p = new JsonArray();
            p.add(e.pos().x());
            p.add(e.pos().y());
            p.add(e.pos().z());
            node.add("pos", p);
            node.addProperty("health", e.health());
            node.addProperty("maxHealth", e.maxHealth());
            node.addProperty("dist", dist(center, e));
            node.addProperty("self", e.id().equals(selfId));
            entities.add(node);
        }
        JsonObject root = ok();
        root.addProperty("count", found.size());
        root.addProperty("truncated", truncated);
        root.add("entities", entities);
        return answer(src, root);
    }

    private static double dist(CellPos center, EntitySnapshot e) {
        double dx = e.pos().x() - center.x();
        double dy = e.pos().y() - center.y();
        double dz = e.pos().z() - center.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

    private WorldCommands() {
    }
}
