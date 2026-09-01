package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Direction;
import com.mcbot.mcbotserver.api.types.Vec3;
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
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

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
// GodClass exempted: flat verb table for the 0013 world read/write
// surface - one body per verb, no shared mutable state, so TCC reads
// 0% while the class stays the single registration + parsing point
// per verb (one-shape-one-owner).
@SuppressWarnings("PMD.GodClass")
public final class WorldCommands {

    /**
     * Live perception surface of one spawned bot.
     *
     * @param view   the world read surface; never null
     * @param botPos the bot body's block position (entities center);
     *               never null
     * @param botId  the bot body's UUID string (self-flagging in
     *               entity listings); never null
     * @param interact the block-placement executor; never null
     * @param equip  hotbar selection writer (0..8); updates both the
     *               body's selectedSlot field and the inventory binding's
     *               mirror so place/drop and state disclosure agree;
     *               never null
     */
    public record Live(
            WorldView view,
            Supplier<CellPos> botPos,
            Supplier<String> botId,
            InteractBlockExecutor interact,
            IntConsumer equip,
            java.util.function.Consumer<Boolean> sneak,
            java.util.function.BooleanSupplier useItemAir,
            java.util.function.Function<com.mcbot.mcbotserver.api.types.CellPos, String> sleep) {}

    /**
     * Register the block / blocks / entities subtrees.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       perception accessor returning null before the
     *                   first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Live> live) {
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
                                        .then(volumeArg(
                                                "dx", live, volumeArg("dy", live, volumeArg("dz", live, null)))))));
        var entities = Commands.literal("entities")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> runEntities(live, ctx.getSource(), 8, 32))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(ctx ->
                                runEntities(live, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), 32))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 128))
                                .executes(ctx -> runEntities(
                                        live,
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                        IntegerArgumentType.getInteger(ctx, "limit")))));
        var place = Commands.literal("place")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument(
                                                        "face",
                                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> runPlace(ctx, live))))));
        var equip = Commands.literal("equip")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 8))
                        .executes(ctx -> runEquip(ctx, live)));
        var sleep = Commands.literal("sleep")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> runSleep(ctx, live)))));
        var useItem = Commands.literal("use-item")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> runUseItem(ctx, live));
        var bag = Commands.literal("bag").requires(src -> src.hasPermission(2)).executes(ctx -> runBag(ctx, live));
        var sneak = Commands.literal("sneak")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("state", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> runSneak(ctx, live)));
        var use = Commands.literal("use")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument(
                                                        "face",
                                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> runUse(ctx, live))))));
        dispatcher.register(block);
        dispatcher.register(blocks);
        dispatcher.register(entities);
        dispatcher.register(place);
        dispatcher.register(equip);
        dispatcher.register(use);
        dispatcher.register(sneak);
        dispatcher.register(useItem);
        dispatcher.register(sleep);
        dispatcher.register(bag);
    }

    /**
     * Synchronous one-shot place (issue 0013 R2): the executor is
     * rising-edge instant, so the command calls it directly between
     * ticks (MenuCommands precedent) and closes the executor's
     * silent-no-op gap with a post-state read - the reply says
     * placed or gives the distinguishable rejection reasons.
     */
    private static int runPlace(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        Direction face;
        try {
            face = faceArg(ctx);
        } catch (IllegalArgumentException e) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err(e.getMessage()));
        }
        CellPos target = posArg(ctx);
        CellPos at = face.relative(target);
        BlockSnapshot before = l.view().getBlock(at, ViewMode.LIVE);
        if (before == null) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("chunk not loaded"));
        }
        if (!before.isAir()) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("cell not air: " + before.blockId()));
        }
        // hitPos is contractually non-null but unused by the executor.
        l.interact()
                .place(new Intent.InteractBlock(
                        target, face, new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5)));
        BlockSnapshot after = l.view().getBlock(at, ViewMode.LIVE);
        JsonObject root = CommandResponse.ok();
        root.addProperty("placed", !after.isAir());
        root.add("at", posJson(at));
        root.addProperty("block", after.blockId());
        if (after.isAir()) {
            root.addProperty("ok", false);
            root.addProperty(
                    "reason", "rejected (no block item in the selected slot," + " cannot survive, or obstructed)");
            return CommandResponse.answer(ctx.getSource(), root);
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Synchronous one-shot use (issue 0007 Phase 2): right-click chain
     * against the target cell - block interaction first, then the held
     * item's use-on. Unlike place there is no reliable post-state read
     * (a pressed button springs back), so the executor's consumed
     * verdict IS the reply: {@code used:true} on a consumed action,
     * {@code used:false} plus a reason otherwise.
     */
    private static int runUse(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        Direction face;
        try {
            face = faceArg(ctx);
        } catch (IllegalArgumentException e) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err(e.getMessage()));
        }
        CellPos target = posArg(ctx);
        BlockSnapshot clicked = l.view().getBlock(target, ViewMode.LIVE);
        if (clicked == null) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("chunk not loaded"));
        }
        // hitPos is contractually non-null; the executor reads it as the
        // vanilla hit location.
        boolean used = l.interact()
                .use(new Intent.InteractBlock(
                        target, face, new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5)));
        JsonObject root = CommandResponse.ok();
        root.addProperty("used", used);
        root.addProperty("target", clicked.blockId());
        if (!used) {
            root.addProperty(
                    "reason",
                    "no action (out of reach, container block, block and item" + " both passed, or empty hand)");
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Per-slot read of the bot's own inventory (/player/bag): main
     * 0..35 with only non-empty slots materialized, armor, offhand,
     * and the current selection. The aggregate item map stays in
     * {@code /bot status} ({@code /player/inventory}); this answers
     * with slot-level truth, which drop/equip/wear orchestration
     * needs.
     */
    private static int runBag(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        var bag = l.view().getInventory();
        JsonObject root = CommandResponse.ok();
        root.addProperty("selected", bag.selectedSlot());
        JsonArray slots = new JsonArray();
        int free = 0;
        for (int i = 0; i < bag.main().size(); i++) {
            var item = bag.main().get(i);
            if (item.isEmpty()) {
                free++;
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("slot", i);
            row.addProperty("item", item.itemId());
            row.addProperty("count", item.count());
            slots.add(row);
        }
        root.add("main", slots);
        root.addProperty("free", free);
        JsonArray armor = new JsonArray();
        for (int i = 0; i < bag.armor().size(); i++) {
            var item = bag.armor().get(i);
            if (item.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("slot", i);
            row.addProperty("item", item.itemId());
            row.addProperty("count", item.count());
            armor.add(row);
        }
        root.add("armor", armor);
        if (!bag.offhand().isEmpty()) {
            JsonObject off = new JsonObject();
            off.addProperty("item", bag.offhand().itemId());
            off.addProperty("count", bag.offhand().count());
            root.add("offhand", off);
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Synchronous sleep (issue 0013, sleep row): verify-and-skip. The
     * refusal reasons are the reply's machine-readable contract; a
     * success carries the advanced day time.
     */
    private static int runSleep(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        CellPos target = posArg(ctx);
        String refusal = l.sleep().apply(target);
        JsonObject root = CommandResponse.ok();
        root.addProperty("slept", refusal == null);
        if (refusal != null) {
            root.addProperty("reason", refusal);
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Synchronous air-use (issue 0013, use-item row): vanilla Item.use
     * for the held item against a POV raycast - the bucket's fill/empty
     * point and the rod's cast are whatever the bot is looking at. The
     * consumed verdict is the reply; hold-type items and food answer
     * used:false with a reason (they ride the USE channel).
     */
    private static int runUseItem(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        boolean used = l.useItemAir().getAsBoolean();
        JsonObject root = CommandResponse.ok();
        root.addProperty("used", used);
        if (!used) {
            root.addProperty(
                    "reason",
                    "no air use (empty hand, hold-type item - bow/shield ride"
                            + " the USE channel, or food rides the EAT reflex)");
        }
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Synchronous body-state write (issue 0013, sneak row): latch the
     * sneak modifier across ticks. Persists through idle ticks until
     * cleared - the equip precedent for persistent state written
     * synchronously. The reply echoes the latched state so the caller
     * confirms the word parsed.
     */
    private static int runSneak(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        String word = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "state")
                .toLowerCase(java.util.Locale.ROOT);
        if (!"on".equals(word) && !"off".equals(word)) {
            return CommandResponse.answer(
                    ctx.getSource(), CommandResponse.err("unknown state: " + word + " (on, off)"));
        }
        boolean enable = "on".equals(word);
        l.sneak().accept(enable);
        JsonObject root = CommandResponse.ok();
        root.addProperty("sneak", enable);
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Synchronous hotbar selection (issue 0013 deferred: equip). Writes
     * the selected slot through the {@code equip} consumer, which updates
     * both the body's {@code selectedSlot} field (used by place/drop) and
     * the inventory binding's mirror (used by state disclosure). The
     * reply carries the newly selected slot and the item now in hand so
     * the caller can confirm the equip landed on the intended item.
     */
    private static int runEquip(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        l.equip().accept(slot);
        var inv = l.view().getInventory();
        var held = inv.main().get(slot);
        JsonObject root = CommandResponse.ok();
        root.addProperty("selectedSlot", slot);
        root.addProperty("item", held.isEmpty() ? "" : held.itemId());
        root.addProperty("count", held.count());
        return CommandResponse.answer(ctx.getSource(), root);
    }

    /**
     * Builds one axis of the blocks-volume argument chain. The chain
     * nests x -> y -> z -> dx -> dy -> dz; each level passes its
     * accumulated builder down so the innermost executes lambda sees
     * every argument by name.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> volumeArg(
            String name,
            Supplier<Live> live,
            com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> next) {
        var arg = Commands.argument(name, IntegerArgumentType.integer(1, 4));
        if (next == null) {
            arg.executes(ctx -> runBlocks(ctx, live));
        } else {
            arg.then(next);
        }
        return arg;
    }

    private static int runBlock(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
        }
        CellPos pos = posArg(ctx);
        BlockSnapshot snap = l.view().getBlock(pos, ViewMode.LIVE);
        if (snap == null) {
            return CommandResponse.answer(ctx.getSource(), CommandResponse.err("chunk not loaded"));
        }
        JsonObject root = CommandResponse.ok();
        root.add("pos", posJson(pos));
        root.addProperty("block", snap.blockId());
        return CommandResponse.answer(ctx.getSource(), root);
    }

    private static int runBlocks(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(ctx.getSource());
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
                    BlockSnapshot snap = l.view().getBlock(pos, ViewMode.LIVE);
                    String id = snap == null ? BlockSnapshot.UNKNOWN : snap.blockId();
                    if (BlockSnapshot.UNKNOWN.equals(id)) {
                        unknown++;
                    }
                    cells.addProperty(pos.x() + "," + pos.y() + "," + pos.z(), id);
                }
            }
        }
        JsonObject root = CommandResponse.ok();
        root.addProperty("cells", dx * dy * dz);
        root.addProperty("unknown", unknown);
        root.add("blocks", cells);
        return CommandResponse.answer(ctx.getSource(), root);
    }

    private static int runEntities(Supplier<Live> live, CommandSourceStack src, int radius, int limit) {
        Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(src);
        }
        CellPos center = l.botPos().get();
        String selfId = l.botId().get();
        List<EntitySnapshot> found = new ArrayList<>(l.view().getEntities(center, radius, ViewMode.LIVE));
        found.sort(Comparator.comparingDouble(e -> dist(center, e)));
        boolean truncated = found.size() > limit;
        JsonArray entities = new JsonArray();
        for (EntitySnapshot e : found) {
            if (entities.size() >= limit) {
                break;
            }
            JsonObject node = new JsonObject();
            node.addProperty("id", e.id());
            node.addProperty("type", e.type());
            node.add("pos", posJson(e.pos()));
            node.addProperty("health", e.health());
            node.addProperty("maxHealth", e.maxHealth());
            node.addProperty("dist", dist(center, e));
            node.addProperty("self", e.id().equals(selfId));
            entities.add(node);
        }
        JsonObject root = CommandResponse.ok();
        root.addProperty("count", found.size());
        root.addProperty("truncated", truncated);
        root.add("entities", entities);
        return CommandResponse.answer(src, root);
    }

    /**
     * The x y z brigadier integer args as one cell.
     *
     * @param ctx the command context carrying the args; never null
     * @return the parsed cell; never null
     */
    private static CellPos posArg(CommandContext<CommandSourceStack> ctx) {
        return new CellPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    /**
     * The face word arg as a Direction.
     *
     * @param ctx the command context carrying the face arg; never null
     * @return the parsed direction; never null
     * @throws IllegalArgumentException with the wire error text on an
     *         unknown face word
     */
    private static Direction faceArg(CommandContext<CommandSourceStack> ctx) {
        String faceWord = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "face")
                .toUpperCase(java.util.Locale.ROOT);
        try {
            return Direction.valueOf(faceWord);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown face: " + faceWord + " (up, down, north, south, east, west)");
        }
    }

    /**
     * One cell as the x/y/z JSON array the harness reads.
     *
     * @param pos the cell; never null
     * @return the [x, y, z] array; never null
     */
    private static JsonArray posJson(CellPos pos) {
        JsonArray p = new JsonArray();
        p.add(pos.x());
        p.add(pos.y());
        p.add(pos.z());
        return p;
    }

    private static double dist(CellPos center, EntitySnapshot e) {
        double dx = e.pos().x() - center.x();
        double dy = e.pos().y() - center.y();
        double dz = e.pos().z() - center.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private WorldCommands() {}
}
