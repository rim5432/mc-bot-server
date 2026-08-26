package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.RecipeView;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Queries the server {@code RecipeManager} and translates shaped
 * crafting recipes into pure {@link RecipeView} descriptions — the
 * only place engine recipe types exist, so the core planner and any
 * harness stay MC-clean (workplan Phase 3: "recipe query service over
 * RecipeManager, adapter-side only").
 *
 * <p>Translation rules: shaped recipes only — shapeless recipes have
 * no fixed grid cells and are not representable as a
 * {@code RecipeView}, so they resolve to empty like unknown ids.
 * Ingredients arrive tag-expanded ({@code Ingredient.getItems()}
 * already enumerates every member of a tag), which becomes the
 * accepted-id list per cell. Empty cells are omitted from the
 * placement map entirely. Result count comes from the recipe's output
 * stack.
 *
 * <p>Thread safety: server tick thread only. The manager's recipe map
 * is rebuilt on datapack reloads on that same thread; callers hold no
 * snapshot — query at use time.
 */
// contract: see workplan Phase 3 (adapter-side recipe translation;
// core stays MC-clean)
public final class RecipeCatalog {

    /** The level providing registry access for result stacks. */
    private final Level level;

    /** The server recipe manager backing every lookup. */
    private final RecipeManager recipes;

    /**
     * Creates a catalog over the given world's server recipe manager.
     *
     * @param level the server level; never null
     */
    public RecipeCatalog(Level level) {
        this.level = level;
        this.recipes = level.getServer().getRecipeManager();
    }

    /**
     * Look up one recipe by its registry id.
     *
     * @param recipeId registry key, e.g. {@code minecraft:sticks};
     *                 never null
     * @return the translated description, or empty when the id is
     *         malformed, unknown, or names a recipe this catalog does
     *         not represent (shapeless)
     */
    public Optional<RecipeView> byId(String recipeId) {
        ResourceLocation key = ResourceLocation.tryParse(recipeId);
        if (key == null) {
            return Optional.empty();
        }
        return recipes.byKey(key).flatMap(recipe -> translate(key,
            recipe));
    }

    /**
     * All crafting recipes producing the given item kind.
     *
     * @param resultItemId registry key of the product, e.g.
     *                     {@code minecraft:stick}; never null
     * @return matching descriptions sorted by recipe id; never null,
     *         possibly empty
     */
    public List<RecipeView> byResult(String resultItemId) {
        List<RecipeView> matches = new ArrayList<>();
        for (ResourceLocation key : recipes.getRecipeIds().toList()) {
            recipes.byKey(key)
                .flatMap(recipe -> translate(key, recipe))
                .filter(view -> view.resultItemId()
                    .equals(resultItemId))
                .ifPresent(matches::add);
        }
        matches.sort((a, b) -> a.recipeId()
            .compareTo(b.recipeId()));
        return List.copyOf(matches);
    }

    /**
     * Paginated list of every shaped recipe the catalog can translate.
     *
     * <p>Used by the {@code recipes list} wire command and the harness
     * {@code dump-recipes} admin verb: the full set is too large for one
     * RCON payload (Source RCON caps around 4 KB; vanilla has hundreds
     * of shaped recipes), so the consumer pages through with
     * offset/limit. Shapeless recipes are silently skipped — same rule
     * as {@link #byId}.
     *
     * @param offset zero-based start index; clamped to [0, total]
     * @param limit  maximum recipes per page; clamped to [1, 200]
     * @return a page with the recipe views, total count, and effective
     *         offset/limit; never null
     */
    public RecipePage list(int offset, int limit) {
        List<RecipeView> all = new ArrayList<>();
        for (ResourceLocation key : recipes.getRecipeIds().toList()) {
            recipes.byKey(key)
                .flatMap(recipe -> translate(key, recipe))
                .ifPresent(all::add);
        }
        all.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));
        int total = all.size();
        int from = Math.max(0, Math.min(offset, total));
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int to = Math.min(from + safeLimit, total);
        return new RecipePage(
            List.copyOf(all.subList(from, to)), total, from, to - from);
    }

    /**
     * One page of the full recipe listing.
     *
     * @param recipes the recipes in this page; never null, immutable
     * @param total   total shaped recipes across all pages
     * @param offset  effective zero-based start of this page
     * @param limit   effective number of recipes in this page
     */
    public record RecipePage(List<RecipeView> recipes, int total,
                             int offset, int limit) {
    }

    /**
     * Translate one engine recipe into the pure description.
     *
     * @param key    the recipe's registry key; never null
     * @param recipe the engine recipe; never null
     * @return the description, or empty when it is not a representable
     *         shaped recipe (shapeless, or an empty/degenerate result)
     */
    private Optional<RecipeView> translate(ResourceLocation key,
                                           Recipe<?> recipe) {
        if (!(recipe instanceof ShapedRecipe shaped)) {
            return Optional.empty();
        }
        ItemStack result =
            shaped.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        TreeMap<Integer, List<String>> placements = new TreeMap<>();
        NonNullList<Ingredient> ingredients = shaped.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack[] options = ingredients.get(i).getItems();
            if (options.length == 0) {
                continue;
            }
            List<String> ids = new ArrayList<>(options.length);
            for (ItemStack option : options) {
                ResourceLocation itemId =
                    BuiltInRegistries.ITEM.getKey(option.getItem());
                ids.add(itemId != null ? itemId.toString() : "unknown");
            }
            placements.put(i, List.copyOf(ids));
        }
        if (placements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeView(key.toString(),
            BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
            result.getCount(), shaped.getWidth(), placements));
    }
}
