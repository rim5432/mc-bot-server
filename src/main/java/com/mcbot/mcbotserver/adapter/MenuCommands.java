package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.ArmorCatalog;
import com.mcbot.mcbotserver.api.menu.MenuTransactions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Brigadier registration for the {@code /menu}, {@code /scan} and
 * {@code /recipes} subtrees. Wiring only: verb behavior lives in
 * {@link MenuVerbs} (menu mutation/read verbs) and
 * {@link MenuInspectOps} (world and catalog inspection); reply
 * plumbing in {@link MenuReply}.
 */
public final class MenuCommands {

    /**
     * Live menu surface of one spawned bot.
     *
     * @param tx      the actor's menu transaction surface; never null
     * @param catalog recipe lookup over the server RecipeManager;
     *                never null
     * @param armor   armor classification over the item registry;
     *                never null
     * @param level   the bot's server level (scan source); never null
     * @param botPos  the bot body's block position (scan center);
     *                never null
     */
    public record Live(
            MenuTransactions tx,
            RecipeCatalog catalog,
            ArmorCatalog armor,
            ServerLevel level,
            Supplier<BlockPos> botPos) {}

    /**
     * Register the menu / scan / recipes subtrees on the dispatcher.
     *
     * @param dispatcher the server command dispatcher; never null
     * @param live       menu surface accessor returning null before
     *                   the first /botspawn; never null
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Live> live) {
        dispatcher.register(menuTree(live));
        dispatcher.register(scanTree(live));
        dispatcher.register(recipesTree(live));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> menuTree(Supplier<Live> live) {
        return Commands.literal("menu")
                .requires(src -> src.hasPermission(2))
                .then(openBranch(live))
                .then(Commands.literal("open-inventory")
                        .executes(ctx -> MenuVerbs.runOpenInventory(live, ctx.getSource())))
                .then(Commands.literal("wear").executes(ctx -> WearVerbs.runWear(live, ctx.getSource())))
                .then(Commands.literal("snapshot").executes(ctx -> MenuVerbs.runSnapshot(live, ctx.getSource())))
                .then(Commands.literal("close").executes(ctx -> MenuVerbs.runClose(live, ctx.getSource())))
                .then(depositBranch(live))
                .then(takeBranch(live))
                .then(craftBranch(live))
                .then(buttonBranch(live));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> openBranch(Supplier<Live> live) {
        return Commands.literal("open")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> MenuVerbs.runOpen(ctx, live)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> depositBranch(Supplier<Live> live) {
        return Commands.literal("deposit")
                .then(Commands.argument("slotRole", StringArgumentType.word())
                        .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> MenuVerbs.runDeposit(ctx, live)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> takeBranch(Supplier<Live> live) {
        var role = Commands.argument("slotRole", StringArgumentType.word())
                .executes(ctx -> MenuVerbs.runTake(ctx, live, 0));
        role = role.then(Commands.argument("count", IntegerArgumentType.integer(1))
                .executes(ctx -> MenuVerbs.runTake(ctx, live, IntegerArgumentType.getInteger(ctx, "count"))));
        return Commands.literal("take").then(role);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> craftBranch(Supplier<Live> live) {
        return Commands.literal("craft")
                .then(Commands.argument("recipeId", StringArgumentType.string())
                        .executes(ctx -> MenuVerbs.runCraft(ctx, live)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buttonBranch(Supplier<Live> live) {
        return Commands.literal("button")
                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                        .executes(ctx -> MenuVerbs.runButton(ctx, live)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> scanTree(Supplier<Live> live) {
        var scan = Commands.literal("scan")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> MenuInspectOps.runScan(live, ctx.getSource(), 8, 32));
        var radius = Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                .executes(ctx -> MenuInspectOps.runScan(
                        live, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), 32));
        radius = radius.then(Commands.argument("limit", IntegerArgumentType.integer(1, 128))
                .executes(ctx -> MenuInspectOps.runScan(
                        live,
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"),
                        IntegerArgumentType.getInteger(ctx, "limit"))));
        return scan.then(radius);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> recipesTree(Supplier<Live> live) {
        return Commands.literal("recipes")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(ctx -> MenuInspectOps.runRecipeList(live, ctx.getSource(), 0, 50))
                        .then(Commands.argument("offset", IntegerArgumentType.integer(0))
                                .executes(ctx -> MenuInspectOps.runRecipeList(
                                        live, ctx.getSource(), IntegerArgumentType.getInteger(ctx, "offset"), 50))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> MenuInspectOps.runRecipeList(
                                                live,
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "offset"),
                                                IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.argument("itemId", StringArgumentType.string())
                        .executes(ctx -> MenuInspectOps.runRecipes(
                                live, ctx.getSource(), StringArgumentType.getString(ctx, "itemId"))));
    }

    private MenuCommands() {}
}
