package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.inventory.ItemIds;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.core.menu.MenuViewJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Read-only inspection verbs over the world and recipe catalog:
 * container scan and recipe lookup/listing. No menu state touched.
 */
final class MenuInspectOps {

    private MenuInspectOps() {}

    static int runScan(Supplier<MenuCommands.Live> live, CommandSourceStack src, int radius, int limit) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(src);
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
        JsonObject root = CommandResponse.ok();
        root.addProperty("count", found.size());
        root.addProperty("truncated", truncated);
        root.add("containers", containers);
        return CommandResponse.answer(src, root);
    }

    static int runRecipes(Supplier<MenuCommands.Live> live, CommandSourceStack src, String itemId) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(src);
        }
        String canonical = ItemIds.normalize(itemId);
        JsonArray recipes = new JsonArray();
        for (RecipeView recipe : l.catalog().byResult(canonical)) {
            recipes.add(MenuViewJson.toJsonObject(recipe));
        }
        JsonObject root = CommandResponse.ok();
        root.add("recipes", recipes);
        return CommandResponse.answer(src, root);
    }

    /**
     * Paginated full recipe listing. Backs {@code /menu recipes list
     * [offset] [limit]} - the RCON payload cap (~4 KB) makes a one-shot
     * dump impossible, so the caller pages with offset/limit.
     */
    static int runRecipeList(Supplier<MenuCommands.Live> live, CommandSourceStack src, int offset, int limit) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(src);
        }
        RecipeCatalog.RecipePage page = l.catalog().list(offset, limit);
        JsonArray recipes = new JsonArray();
        for (RecipeView recipe : page.recipes()) {
            recipes.add(MenuViewJson.toJsonObject(recipe));
        }
        JsonObject root = CommandResponse.ok();
        root.add("recipes", recipes);
        root.addProperty("total", page.total());
        root.addProperty("offset", page.offset());
        root.addProperty("limit", page.limit());
        root.addProperty("truncated", page.offset() + page.limit() < page.total());
        return CommandResponse.answer(src, root);
    }
}
