package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuTransactions;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.menu.MenuPlanner;
import com.mcbot.mcbotserver.core.menu.MenuViewJson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Boundary-D menu command batch (issue 0012 D1): synchronous
 * request-response transactions over the open menu - they do NOT go
 * through CommandBus (no taskId, no event-stream result reporting;
 * the result IS the RCON reply). Composites (deposit / take / craft)
 * are planned by the pure core {@link MenuPlanner} and executed click
 * by click through {@link MenuTransactions}; the plan runs entirely
 * before the first click, so an impossible request performs zero
 * clicks - no partial deposit.
 *
 * <p>Every answer is one JSON object with an {@code ok} boolean, same
 * convention as {@link BotCommands}. Snapshots serialize through
 * {@link MenuViewJson} (wire keys frozen from first transport).
 *
 * <p>Threading evidence: DedicatedServer.runCommand dispatches RCON
 * commands through executeBlocking, so every executes lambda runs on
 * the server thread between ticks - exactly the
 * {@link MenuTransactions} calling contract.
 */
// contract: see boundaries.md section D + issue 0012 D1 (menu command
//            batch: synchronous RPC, not CommandBus submissions)
public final class MenuCommands {

    /**
     * Live menu surface of one spawned bot.
     *
     * @param tx      the actor's menu transaction surface; never null
     * @param catalog recipe lookup over the server RecipeManager;
     *                never null
     * @param level   the bot's server level (scan source); never null
     * @param botPos  the bot body's block position (scan center);
     *                never null
     */
    public record Live(MenuTransactions tx, RecipeCatalog catalog, ServerLevel level, Supplier<BlockPos> botPos) {}

    /**
     * Register the menu / scan / recipes subtrees on the dispatcher.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       menu surface accessor returning null before
     *                   the first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Live> live) {
        var menu = Commands.literal("menu")
                .requires(src -> src.hasPermission(2))
                .then(openBranch(live))
                .then(Commands.literal("open-inventory").executes(ctx -> runOpenInventory(live, ctx.getSource())))
                .then(Commands.literal("snapshot").executes(ctx -> runSnapshot(live, ctx.getSource())))
                .then(Commands.literal("close").executes(ctx -> runClose(live, ctx.getSource())))
                .then(depositBranch(live))
                .then(takeBranch(live))
                .then(craftBranch(live));
        dispatcher.register(menu);
        dispatcher.register(scanBranch(live));
        dispatcher.register(Commands.literal("recipes")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ctx -> runRecipeList(live, ctx.getSource(), 0, 50))
                        .then(Commands.argument("offset", IntegerArgumentType.integer(0))
                                .executes(ctx -> runRecipeList(
                                        live, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "offset"), 50))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> runRecipeList(
                                                live,
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "offset"),
                                                IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.argument("itemId", StringArgumentType.string())
                        .executes(ctx ->
                                runRecipes(live, ctx.getSource(), StringArgumentType.getString(ctx, "itemId")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> openBranch(
            Supplier<Live> live) {
        return Commands.literal("open")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> runOpen(ctx, live)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> depositBranch(
            Supplier<Live> live) {
        return Commands.literal("deposit")
                .then(Commands.argument("slotRole", StringArgumentType.word())
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> runDeposit(ctx, live)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> takeBranch(
            Supplier<Live> live) {
        var role = Commands.argument("slotRole", StringArgumentType.word()).executes(ctx -> runTake(ctx, live, 0));
        role = role.then(Commands.argument("count", IntegerArgumentType.integer(1))
                .executes(ctx -> runTake(ctx, live, IntegerArgumentType.getInteger(ctx, "count"))));
        return Commands.literal("take").then(role);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> craftBranch(
            Supplier<Live> live) {
        return Commands.literal("craft")
                .then(Commands.argument("recipeId", StringArgumentType.string()).executes(ctx -> runCraft(ctx, live)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> scanBranch(
            Supplier<Live> live) {
        var scan = Commands.literal("scan")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> runScan(live, ctx.getSource(), 8, 32));
        var radius = Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                .executes(ctx -> runScan(live, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), 32));
        radius = radius.then(Commands.argument("limit", IntegerArgumentType.integer(1, 128))
                .executes(ctx -> runScan(
                        live,
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"),
                        IntegerArgumentType.getInteger(ctx, "limit"))));
        return scan.then(radius);
    }

    private static int runOpen(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        MenuView view = l.tx().openMenu(new CellPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z")));
        if (view == null) {
            return answer(
                    ctx.getSource(),
                    err("open rejected: out of reach, unsupported kind, " + "missing container, or blocked"));
        }
        JsonObject root = ok();
        root.add("menu", MenuViewJson.toJsonObject(view));
        return answer(ctx.getSource(), root);
    }

    private static int runOpenInventory(Supplier<Live> live, CommandSourceStack src) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        JsonObject root = ok();
        root.add("menu", MenuViewJson.toJsonObject(l.tx().openInventoryMenu()));
        return answer(src, root);
    }

    private static int runSnapshot(Supplier<Live> live, CommandSourceStack src) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        MenuView view = l.tx().menuSnapshot();
        if (view == null) {
            return answer(src, err("no menu open"));
        }
        JsonObject root = ok();
        root.add("menu", MenuViewJson.toJsonObject(view));
        return answer(src, root);
    }

    private static int runClose(Supplier<Live> live, CommandSourceStack src) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        // Idempotent by contract: an at-least-once retry cannot
        // double-run the close.
        l.tx().closeMenu();
        return answer(src, ok());
    }

    private static int runDeposit(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        String roleWord = StringArgumentType.getString(ctx, "slotRole");
        String item = StringArgumentType.getString(ctx, "item");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        SlotRole role = parseRole(roleWord);
        if (role == null) {
            return answer(ctx.getSource(), err("unknown role: " + roleWord + " (INPUT, FUEL, OUTPUT, CONTAINER)"));
        }
        MenuView before = l.tx().menuSnapshot();
        if (before == null) {
            return answer(ctx.getSource(), err("no menu open"));
        }
        SlotRole resolved = resolveRole(before, role, true);
        List<MenuPlanner.Step> steps;
        try {
            steps = MenuPlanner.planDepositCounted(before, resolved, item, count);
        } catch (RuntimeException e) {
            return answer(ctx.getSource(), err(e.getMessage()));
        }
        MenuView after = executeSteps(l.tx(), steps, before);
        JsonObject root = ok();
        root.addProperty("placed", roleCount(after, resolved) - roleCount(before, resolved));
        root.add("menu", MenuViewJson.toJsonObject(after));
        return answer(ctx.getSource(), root);
    }

    private static int runTake(CommandContext<CommandSourceStack> ctx, Supplier<Live> live, int count) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        String roleWord = StringArgumentType.getString(ctx, "slotRole");
        SlotRole role = parseRole(roleWord);
        if (role == null) {
            return answer(ctx.getSource(), err("unknown role: " + roleWord + " (INPUT, FUEL, OUTPUT, CONTAINER)"));
        }
        MenuView before = l.tx().menuSnapshot();
        if (before == null) {
            return answer(ctx.getSource(), err("no menu open"));
        }
        SlotRole resolved = resolveRole(before, role, false);
        List<MenuPlanner.Step> steps;
        try {
            steps = MenuPlanner.planTakeRole(before, resolved, count);
        } catch (RuntimeException e) {
            return answer(ctx.getSource(), err(e.getMessage()));
        }
        MenuView after = executeSteps(l.tx(), steps, before);
        JsonObject root = ok();
        root.addProperty("taken", roleCount(before, resolved) - roleCount(after, resolved));
        root.add("menu", MenuViewJson.toJsonObject(after));
        return answer(ctx.getSource(), root);
    }

    /**
     * Parse a wire role word into the enum vocabulary accepted by the
     * deposit/take verbs.
     *
     * @param word the brigadier argument; never null
     * @return the role, or null when the word is not in the vocabulary
     */
    private static SlotRole parseRole(String word) {
        return switch (word) {
            case "INPUT", "FUEL", "OUTPUT", "CONTAINER" -> SlotRole.valueOf(word);
            default -> null;
        };
    }

    /**
     * Resolve an intent role against the open menu's kind (issue 0012
     * D1): furnace kinds carry real INPUT / FUEL / OUTPUT slots; every
     * other kind has one undifferentiated CONTAINER region, so
     * INPUT/FUEL deposits and OUTPUT takes land on CONTAINER. An
     * explicit CONTAINER passes through unchanged.
     *
     * @param view    the open menu snapshot; never null
     * @param role    the parsed intent role; never null
     * @param deposit true for deposit (INPUT/FUEL resolve), false for
     *                take (OUTPUT resolves)
     * @return the concrete role to plan against; never null
     */
    private static SlotRole resolveRole(MenuView view, SlotRole role, boolean deposit) {
        String type = view.type();
        boolean furnaceFamily = type.equals("furnace") || type.equals("blast_furnace") || type.equals("smoker");
        if (furnaceFamily) {
            return role;
        }
        return SlotRole.CONTAINER;
    }

    private static int roleCount(MenuView view, SlotRole role) {
        int total = 0;
        for (var slot : view.slots()) {
            if (slot.role() == role && !slot.isEmpty()) {
                total += slot.item().count();
            }
        }
        return total;
    }

    private static int runCraft(CommandContext<CommandSourceStack> ctx, Supplier<Live> live) {
        Live l = live.get();
        if (l == null) {
            return answer(ctx.getSource(), err("no active bot"));
        }
        String recipeId = StringArgumentType.getString(ctx, "recipeId");
        var recipe = l.catalog().byId(recipeId);
        if (recipe.isEmpty()) {
            return answer(ctx.getSource(), err("no such recipe: " + recipeId));
        }
        MenuView opened = l.tx().menuSnapshot();
        if (opened == null) {
            return answer(ctx.getSource(), err("no menu open - open a crafting surface first"));
        }
        try {
            CraftingView lens = CraftingView.of(opened);
            MenuView filled = executeSteps(l.tx(), MenuPlanner.planRecipe(lens, recipe.get()), opened);
            // The result slot resolves synchronously with the grid
            // change; the post-click snapshot already carries it.
            MenuView done = executeSteps(l.tx(), MenuPlanner.planTakeResult(CraftingView.of(filled)), filled);
            JsonObject root = ok();
            root.addProperty("result", recipe.get().resultItemId());
            root.addProperty("count", recipe.get().resultCount());
            root.add("menu", MenuViewJson.toJsonObject(done));
            return answer(ctx.getSource(), root);
        } catch (RuntimeException e) {
            // IllegalArgumentException = plan rejected (supply, table
            // need, wrong surface); IllegalStateException = result
            // empty after fill. Both are sync errors: nothing further
            // executed.
            return answer(ctx.getSource(), err(e.getMessage()));
        }
    }

    private static int runScan(Supplier<Live> live, CommandSourceStack src, int radius, int limit) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        BlockPos center = l.botPos().get();
        List<JsonObject> found = new ArrayList<>();
        int cx0 = (center.getX() - radius) >> 4;
        int cx1 = (center.getX() + radius) >> 4;
        int cz0 = (center.getZ() - radius) >> 4;
        int cz1 = (center.getZ() + radius) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                // hasChunk guard: scan reads only loaded chunks and
                // never force-loads terrain as a side effect.
                if (!l.level().hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = l.level().getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof Container)) {
                        continue;
                    }
                    double dist = Math.sqrt(be.getBlockPos().distSqr(center));
                    if (dist > radius) {
                        continue;
                    }
                    JsonObject node = new JsonObject();
                    node.addProperty("type", String.valueOf(ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType())));
                    node.addProperty("x", be.getBlockPos().getX());
                    node.addProperty("y", be.getBlockPos().getY());
                    node.addProperty("z", be.getBlockPos().getZ());
                    node.addProperty("dist", Math.round(dist * 10) / 10.0);
                    found.add(node);
                }
            }
        }
        found.sort(Comparator.comparingDouble(n -> n.get("dist").getAsDouble()));
        boolean truncated = found.size() > limit;
        JsonArray containers = new JsonArray();
        found.stream().limit(limit).forEach(containers::add);
        JsonObject root = ok();
        root.addProperty("count", found.size());
        root.addProperty("truncated", truncated);
        root.add("containers", containers);
        return answer(src, root);
    }

    private static int runRecipes(Supplier<Live> live, CommandSourceStack src, String itemId) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        JsonArray recipes = new JsonArray();
        for (RecipeView recipe : l.catalog().byResult(itemId)) {
            recipes.add(MenuViewJson.toJsonObject(recipe));
        }
        JsonObject root = ok();
        root.add("recipes", recipes);
        return answer(src, root);
    }

    /**
     * Paginated full recipe listing. Backs {@code /bot recipes list
     * [offset] [limit]} and the harness {@code dump-recipes} admin verb.
     *
     * <p>RCON payload cap (Source RCON ~4 KB) makes a full dump in one
     * call impossible; the consumer pages through with offset/limit. The
     * reply carries {@code total} and {@code truncated} so the caller
     * knows when to stop.
     *
     * @param live   menu surface accessor; never null
     * @param src    command source; never null
     * @param offset zero-based start index
     * @param limit  max recipes per page
     * @return command success code
     */
    private static int runRecipeList(Supplier<Live> live, CommandSourceStack src, int offset, int limit) {
        Live l = live.get();
        if (l == null) {
            return answer(src, err("no active bot"));
        }
        RecipeCatalog.RecipePage page = l.catalog().list(offset, limit);
        JsonArray recipes = new JsonArray();
        for (RecipeView recipe : page.recipes()) {
            recipes.add(MenuViewJson.toJsonObject(recipe));
        }
        JsonObject root = ok();
        root.add("recipes", recipes);
        root.addProperty("total", page.total());
        root.addProperty("offset", page.offset());
        root.addProperty("limit", page.limit());
        root.addProperty("truncated", page.offset() + page.limit() < page.total());
        return answer(src, root);
    }

    /**
     * Execute planner steps in order; every click answers the
     * post-click snapshot, so the last answer is the final state.
     *
     * @param tx    the menu surface; never null
     * @param steps click sequence; may be empty (returns {@code last})
     * @param last  the snapshot the plan was computed from; never null
     * @return the post-execution snapshot; never null
     */
    private static MenuView executeSteps(MenuTransactions tx, List<MenuPlanner.Step> steps, MenuView last) {
        MenuView view = last;
        for (MenuPlanner.Step step : steps) {
            view = tx.menuClick(step.slot(), step.button(), step.kind());
        }
        return view;
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

    private MenuCommands() {}
}
