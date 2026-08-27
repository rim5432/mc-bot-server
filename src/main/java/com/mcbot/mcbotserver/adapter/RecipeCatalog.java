package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.RecipeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * Queries the server {@code RecipeManager} and translates shaped
 * crafting recipes into pure {@link RecipeView} descriptions — the
 * only place engine recipe types exist, so the core planner and any
 * harness stay MC-clean (workplan Phase 3: "recipe query service over
 * RecipeManager, adapter-side only").
 *
 * <p>Translation rules: shaped recipes translate to pattern-relative
 * placements; shapeless recipes translate to the ingredient-index
 * encoding ({@link RecipeView} documents both). Ingredients arrive
 * tag-expanded ({@code Ingredient.getItems()} already enumerates every
 * member of a tag), which becomes the accepted-id list per cell. Empty
 * cells are omitted from the shaped placement map entirely; a
 * shapeless ingredient with no item options is dropped and the
 * remaining indices compacted. Result count comes from the recipe's
 * output stack.
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
     *         not represent (non-crafting types)
     */
    public Optional<RecipeView> byId(String recipeId) {
        ResourceLocation key = ResourceLocation.tryParse(recipeId);
        if (key == null) {
            return Optional.empty();
        }
        return recipes.byKey(key).flatMap(recipe -> translate(key, recipe));
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
                    .filter(view -> view.resultItemId().equals(resultItemId))
                    .ifPresent(matches::add);
        }
        matches.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));
        return List.copyOf(matches);
    }

    /**
     * Paginated list of every recipe the catalog can translate.
     *
     * <p>Used by the {@code recipes list} wire command and the harness
     * {@code dump-recipes} admin verb: the full set is too large for one
     * RCON payload (Source RCON caps around 4 KB; vanilla has hundreds
     * of crafting recipes), so the consumer pages through with
     * offset/limit.
     *
     * @param offset zero-based start index; clamped to [0, total]
     * @param limit  maximum recipes per page; clamped to [1, 200]
     * @return a page with the recipe views, total count, and effective
     *         offset/limit; never null
     */
    public RecipePage list(int offset, int limit) {
        List<RecipeView> all = new ArrayList<>();
        for (ResourceLocation key : recipes.getRecipeIds().toList()) {
            recipes.byKey(key).flatMap(recipe -> translate(key, recipe)).ifPresent(all::add);
        }
        all.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));
        int total = all.size();
        int from = Math.max(0, Math.min(offset, total));
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int to = Math.min(from + safeLimit, total);
        return new RecipePage(List.copyOf(all.subList(from, to)), total, from, to - from);
    }

    /**
     * One page of the full recipe listing.
     *
     * @param recipes the recipes in this page; never null, immutable
     * @param total   total translatable recipes across all pages
     * @param offset  effective zero-based start of this page
     * @param limit   effective number of recipes in this page
     */
    public record RecipePage(List<RecipeView> recipes, int total, int offset, int limit) {}

    /** Placeholder width on shapeless views; positions are indices, not geometry. */
    private static final int SHAPELESS_PLACEHOLDER_WIDTH = 3;

    /** Vanilla serializer cap: a shapeless recipe carries at most nine ingredients. */
    private static final int MAX_SHAPELESS_INGREDIENTS = 9;

    /**
     * Translate one engine recipe into the pure description.
     *
     * @param key    the recipe's registry key; never null
     * @param recipe the engine recipe; never null
     * @return the description, or empty when it is not a representable
     *         crafting recipe (non-crafting types, an empty/degenerate
     *         result, or a shapeless recipe with more than nine
     *         usable ingredients)
     */
    private Optional<RecipeView> translate(ResourceLocation key, Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return translateShaped(key, shaped);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            return translateShapeless(key, shapeless);
        }
        return Optional.empty();
    }

    /**
     * Shaped translation: ingredient i keeps its pattern position;
     * ingredients with no item options leave that position empty.
     *
     * @param key     the recipe's registry key; never null
     * @param shaped  the engine recipe; never null
     * @return the pattern-relative view, or empty on a degenerate
     *         result or an all-empty ingredient list
     */
    private Optional<RecipeView> translateShaped(ResourceLocation key, ShapedRecipe shaped) {
        ItemStack result = shaped.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        TreeMap<Integer, List<String>> placements = new TreeMap<>();
        NonNullList<Ingredient> ingredients = shaped.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            List<String> ids = expandIds(ingredients.get(i).getItems());
            if (ids == null) {
                continue;
            }
            placements.put(i, ids);
        }
        if (placements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeView(
                key.toString(),
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                result.getCount(),
                shaped.getWidth(),
                placements));
    }

    /**
     * Shapeless translation: the ingredient-index encoding - usable
     * ingredients compact onto indices 0..n-1, patternWidth is the
     * placeholder constant.
     *
     * @param key       the recipe's registry key; never null
     * @param shapeless the engine recipe; never null
     * @return the index-encoded view, or empty on a degenerate
     *         result, an all-empty ingredient list, or more than
     *         nine usable ingredients
     */
    private Optional<RecipeView> translateShapeless(ResourceLocation key, ShapelessRecipe shapeless) {
        ItemStack result = shapeless.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        TreeMap<Integer, List<String>> placements = new TreeMap<>();
        for (Ingredient ingredient : shapeless.getIngredients()) {
            List<String> ids = expandIds(ingredient.getItems());
            if (ids == null) {
                continue;
            }
            if (placements.size() == MAX_SHAPELESS_INGREDIENTS) {
                // Positions cap at 8 (pattern-relative space); a
                // tenth usable ingredient is not representable.
                return Optional.empty();
            }
            placements.put(placements.size(), ids);
        }
        if (placements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecipeView(
                key.toString(),
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                result.getCount(),
                SHAPELESS_PLACEHOLDER_WIDTH,
                placements,
                true));
    }

    /**
     * Tag-expanded accepted ids for one ingredient's item options.
     *
     * @param options the ingredient's enumerated item stacks; never
     *                null
     * @return the id list, or null when the ingredient accepts
     *         nothing (the caller drops such cells/ingredients)
     */
    private static List<String> expandIds(ItemStack[] options) {
        if (options.length == 0) {
            return null;
        }
        List<String> ids = new ArrayList<>(options.length);
        for (ItemStack option : options) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(option.getItem());
            ids.add(itemId != null ? itemId.toString() : "unknown");
        }
        return List.copyOf(ids);
    }
}
