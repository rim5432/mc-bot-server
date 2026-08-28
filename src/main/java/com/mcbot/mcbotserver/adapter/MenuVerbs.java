package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuTransactions;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.menu.MenuPlanner;
import com.mcbot.mcbotserver.core.menu.MenuViewJson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;

/**
 * Executor half of the menu verb surface: the imperative verbs that
 * mutate or read one bot's open menu (open/close, snapshot, counted
 * deposit/take, craft; armor wearing lives in {@link WearVerbs}).
 * Brigadier tree construction stays with
 * {@link MenuCommands}; this class holds only the wiring-neutral
 * behavior.
 */
final class MenuVerbs {

    private MenuVerbs() {}

    static int runOpen(CommandContext<CommandSourceStack> ctx, Supplier<MenuCommands.Live> live) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no active bot"));
        }
        MenuView view = l.tx().openMenu(new CellPos(
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z")));
        if (view == null) {
            return MenuReply.answer(
                    ctx.getSource(),
                    MenuReply.err("open rejected: out of reach, unsupported kind, " + "missing container, or blocked"));
        }
        JsonObject root = MenuReply.ok();
        root.add("menu", MenuViewJson.toJsonObject(view));
        return MenuReply.answer(ctx.getSource(), root);
    }

    static int runOpenInventory(Supplier<MenuCommands.Live> live, CommandSourceStack src) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(src, MenuReply.err("no active bot"));
        }
        JsonObject root = MenuReply.ok();
        root.add("menu", MenuViewJson.toJsonObject(l.tx().openInventoryMenu()));
        return MenuReply.answer(src, root);
    }

    static int runSnapshot(Supplier<MenuCommands.Live> live, CommandSourceStack src) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(src, MenuReply.err("no active bot"));
        }
        MenuView view = l.tx().menuSnapshot();
        if (view == null) {
            return MenuReply.answer(src, MenuReply.err("no menu open"));
        }
        JsonObject root = MenuReply.ok();
        root.add("menu", MenuViewJson.toJsonObject(view));
        return MenuReply.answer(src, root);
    }

    static int runClose(Supplier<MenuCommands.Live> live, CommandSourceStack src) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(src, MenuReply.err("no active bot"));
        }
        // Idempotent by contract: an at-least-once retry cannot
        // double-run the close.
        l.tx().closeMenu();
        return MenuReply.answer(src, MenuReply.ok());
    }

    static int runButton(CommandContext<CommandSourceStack> ctx, Supplier<MenuCommands.Live> live) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no active bot"));
        }
        int id = IntegerArgumentType.getInteger(ctx, "id");
        MenuView after = l.tx().menuButtonClick(id);
        if (after == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("menu does not support button clicks"));
        }
        JsonObject root = MenuReply.ok();
        root.add("menu", MenuViewJson.toJsonObject(after));
        return MenuReply.answer(ctx.getSource(), root);
    }

    static int runDeposit(CommandContext<CommandSourceStack> ctx, Supplier<MenuCommands.Live> live) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no active bot"));
        }
        String roleWord = StringArgumentType.getString(ctx, "slotRole");
        String item = StringArgumentType.getString(ctx, "item");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        SlotRole role = parseRole(roleWord);
        if (role == null) {
            return MenuReply.answer(
                    ctx.getSource(), MenuReply.err("unknown role: " + roleWord + " (INPUT, FUEL, OUTPUT, CONTAINER)"));
        }
        MenuView before = l.tx().menuSnapshot();
        if (before == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no menu open"));
        }
        SlotRole resolved = resolveRole(before, role);
        List<MenuPlanner.Step> steps;
        try {
            steps = MenuPlanner.planDepositCounted(before, resolved, item, count);
        } catch (RuntimeException e) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err(e.getMessage()));
        }
        MenuView after = executeSteps(l.tx(), steps, before);
        JsonObject root = MenuReply.ok();
        root.addProperty("placed", roleCount(after, resolved) - roleCount(before, resolved));
        root.add("menu", MenuViewJson.toJsonObject(after));
        return MenuReply.answer(ctx.getSource(), root);
    }

    static int runTake(CommandContext<CommandSourceStack> ctx, Supplier<MenuCommands.Live> live, int count) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no active bot"));
        }
        String roleWord = StringArgumentType.getString(ctx, "slotRole");
        SlotRole role = parseRole(roleWord);
        if (role == null) {
            return MenuReply.answer(
                    ctx.getSource(), MenuReply.err("unknown role: " + roleWord + " (INPUT, FUEL, OUTPUT, CONTAINER)"));
        }
        MenuView before = l.tx().menuSnapshot();
        if (before == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no menu open"));
        }
        SlotRole resolved = resolveRole(before, role);
        List<MenuPlanner.Step> steps;
        try {
            steps = MenuPlanner.planTakeRole(before, resolved, count);
        } catch (RuntimeException e) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err(e.getMessage()));
        }
        MenuView after = executeSteps(l.tx(), steps, before);
        JsonObject root = MenuReply.ok();
        root.addProperty("taken", roleCount(before, resolved) - roleCount(after, resolved));
        root.add("menu", MenuViewJson.toJsonObject(after));
        return MenuReply.answer(ctx.getSource(), root);
    }

    static int runCraft(CommandContext<CommandSourceStack> ctx, Supplier<MenuCommands.Live> live) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no active bot"));
        }
        // The batch chain (ledger 39): one or more comma-separated
        // recipe ids, executed IN THE GIVEN ORDER inside the one
        // open menu session. The harness still plans the chain -
        // the bot only batches the execution, so plan-then-fill
        // runs per recipe and the first failure stops the batch
        // with the partial results on the record.
        java.util.List<String> chain = com.google.common.base.Splitter.on(',')
                .trimResults()
                .splitToList(StringArgumentType.getString(ctx, "recipeId"));
        MenuView opened = l.tx().menuSnapshot();
        if (opened == null) {
            return MenuReply.answer(ctx.getSource(), MenuReply.err("no menu open - open a crafting surface first"));
        }
        JsonArray results = new JsonArray();
        try {
            for (String recipeId : chain) {
                if (recipeId.isEmpty()) {
                    throw new IllegalArgumentException("empty recipe id in chain");
                }
                var recipe = l.catalog().byId(recipeId);
                if (recipe.isEmpty()) {
                    throw new IllegalArgumentException("no such recipe: " + recipeId);
                }
                opened = craftOne(l, recipe.get(), opened);
                JsonObject row = new JsonObject();
                row.addProperty("recipe", recipeId);
                row.addProperty("result", recipe.get().resultItemId());
                row.addProperty("count", recipe.get().resultCount());
                results.add(row);
            }
        } catch (RuntimeException e) {
            // IllegalArgumentException = plan rejected (supply, table
            // need, wrong surface, unknown id); IllegalStateException
            // = result empty after fill. Both are sync errors: the
            // batch stops here with everything already crafted kept.
            JsonObject root = MenuReply.err(e.getMessage());
            root.add("crafted", results);
            root.addProperty("failedAfter", results.size());
            return MenuReply.answer(ctx.getSource(), root);
        }
        JsonObject root = MenuReply.ok();
        root.add("crafted", results);
        root.add("menu", MenuViewJson.toJsonObject(opened));
        return MenuReply.answer(ctx.getSource(), root);
    }

    /**
     * One recipe round: fill the grid from the player region, take
     * the resolved result. The result slot resolves synchronously
     * with the grid change; the post-click snapshot already carries
     * it.
     *
     * @param l      the live menu surface; never null
     * @param recipe the recipe to realize; never null
     * @param before the snapshot before this round; never null
     * @return the snapshot after the result was taken; never null
     */
    private static MenuView craftOne(
            MenuCommands.Live l, com.mcbot.mcbotserver.api.menu.RecipeView recipe, MenuView before) {
        CraftingView lens = CraftingView.of(before);
        MenuView filled = executeSteps(l.tx(), MenuPlanner.planRecipe(lens, recipe), before);
        return executeSteps(l.tx(), MenuPlanner.planTakeResult(CraftingView.of(filled)), filled);
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
            case "INPUT", "FUEL", "OUTPUT", "CONTAINER", "BOTTLE", "INGREDIENT", "ARMOR" -> SlotRole.valueOf(word);
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
     * @return the concrete role to plan against; never null
     */
    private static SlotRole resolveRole(MenuView view, SlotRole role) {
        String type = view.type();
        boolean furnaceFamily = type.equals("furnace") || type.equals("blast_furnace") || type.equals("smoker");
        boolean anvilFamily = type.equals("anvil");
        boolean brewingFamily = type.equals("brewing_stand");
        boolean grindstoneFamily = type.equals("grindstone");
        boolean stonecutterFamily = type.equals("stonecutter");
        boolean cartographyFamily = type.equals("cartography_table");
        boolean smithingFamily = type.equals("smithing_table");
        boolean loomFamily = type.equals("loom");
        boolean enchantingFamily = type.equals("enchanting_table");
        boolean beaconFamily = type.equals("beacon");
        boolean merchantFamily = type.equals("merchant");
        boolean horseFamily = type.equals("horse");
        if (furnaceFamily
                || anvilFamily
                || brewingFamily
                || grindstoneFamily
                || stonecutterFamily
                || cartographyFamily
                || smithingFamily
                || loomFamily
                || enchantingFamily
                || beaconFamily
                || merchantFamily
                || horseFamily) {
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

    /**
     * Execute planner steps in order; every click answers the
     * post-click snapshot, so the last answer is the final state.
     * Shared with {@link WearVerbs}.
     *
     * @param tx    the menu surface; never null
     * @param steps click sequence; may be empty (returns {@code last})
     * @param last  the snapshot the plan was computed from; never null
     * @return the post-execution snapshot; never null
     */
    static MenuView executeSteps(MenuTransactions tx, List<MenuPlanner.Step> steps, MenuView last) {
        MenuView view = last;
        for (MenuPlanner.Step step : steps) {
            view = tx.menuClick(step.slot(), step.button(), step.kind());
        }
        return view;
    }
}
