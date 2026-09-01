package com.mcbot.mcbotserver.api.menu;

import java.util.List;
import java.util.Map;

/**
 * Pure description of one shaped crafting recipe: which item kinds go
 * into which pattern cells, and what the grid yields. Data only — the
 * adapter produces it from the server {@code RecipeManager}, and the
 * core planner consumes it without ever seeing an engine type.
 *
 * <p>Contract: see boundaries.md section A (api purity) and workplan
 * Phase 3 (recipe query service, adapter-side only). Coordinates are
 * PATTERN-relative, not grid-relative: {@code patternPos} is row-major
 * within {@code patternWidth} (position {@code p} reads as row
 * {@code p / patternWidth}, column {@code p % patternWidth}). A 1x2
 * pattern therefore anchors at whatever surface opens — the planner
 * translates pattern coordinates onto the 2x2 or 3x3 grid it is
 * handed, which is also why vanilla's any-offset shaped matching
 * stays satisfied (top-left anchoring is always a legal placement).
 *
 * <p>{@code acceptedItemIds} are tag-expanded at query time: a cell
 * backed by {@code #minecraft:planks} arrives listing every plank
 * kind, so the planner resolves against actual inventory contents
 * without knowing tags exist.
 *
 * <p>Shapeless recipes use the ingredient-index encoding:
 * {@code shapeless} is true, positions are compact ingredient
 * indices {@code 0..n-1} (not geometry), and {@code patternWidth}
 * is a placeholder with no geometric meaning. Vanilla's
 * {@code ShapelessRecipe.matches} accepts any arrangement whose
 * item multiset equals the ingredients, so the planner maps index
 * {@code i} onto flat grid cell {@code i} and the grid-fit rule is
 * the vanilla count rule (an inventory 2x2 holds at most four
 * ingredients).
 *
 * @param recipeId     registry key of the recipe, e.g.
 *                     {@code minecraft:sticks}; never null or blank
 * @param resultItemId registry key of the product; never null or blank
 * @param resultCount  items produced per craft; positive
 * @param patternWidth pattern row length, 1..3 (vanilla grids are at
 *                     most 3x3); placeholder without geometric
 *                     meaning when {@code shapeless} is true
 * @param placements   pattern position -> accepted item ids (non-empty,
 *                     in query order); for shapeless views the keys
 *                     are exactly 0..n-1; never null, immutable
 * @param shapeless    true when positions are ingredient indices
 *                     rather than pattern geometry
 */
public record RecipeView(
        String recipeId,
        String resultItemId,
        int resultCount,
        int patternWidth,
        Map<Integer, List<String>> placements,
        boolean shapeless) {

    /**
     * Shaped-recipe convenience constructor used by callers that
     * never produce shapeless views.
     *
     * @param recipeId     registry key of the recipe; never null or
     *                     blank
     * @param resultItemId registry key of the product; never null or
     *                     blank
     * @param resultCount  items produced per craft; positive
     * @param patternWidth pattern row length, 1..3
     * @param placements   pattern position -> accepted item ids; never
     *                     null
     */
    public RecipeView(
            String recipeId,
            String resultItemId,
            int resultCount,
            int patternWidth,
            Map<Integer, List<String>> placements) {
        this(recipeId, resultItemId, resultCount, patternWidth, placements, false);
    }

    /**
     * Creates a validated, immutable recipe description.
     *
     * @param recipeId     must not be null or blank
     * @param resultItemId must not be null or blank
     * @param resultCount  must be positive
     * @param patternWidth must be 1..3
     * @param placements   keys must be unique pattern positions in
     *                     0..8, values non-empty id lists; must not be
     *                     null
     * @throws IllegalArgumentException when any invariant above fails
     */
    public RecipeView {
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be null or blank");
        }
        if (resultItemId == null || resultItemId.isBlank()) {
            throw new IllegalArgumentException("resultItemId must not be null or blank");
        }
        if (resultCount <= 0) {
            throw new IllegalArgumentException("resultCount must be positive, got " + resultCount);
        }
        if (patternWidth < 1 || patternWidth > 3) {
            throw new IllegalArgumentException("patternWidth must be 1..3, got " + patternWidth);
        }
        if (placements == null || placements.isEmpty()) {
            throw new IllegalArgumentException("placements must not be null or empty");
        }
        Map<Integer, List<String>> copied = new java.util.TreeMap<>();
        placements.forEach((pos, ids) -> {
            if (pos == null || pos < 0 || pos > 8) {
                throw new IllegalArgumentException("placement position out of range: " + pos);
            }
            if (ids == null || ids.isEmpty()) {
                throw new IllegalArgumentException("placement " + pos + " must accept at least one" + " item id");
            }
            copied.put(pos, List.copyOf(ids));
        });
        if (shapeless) {
            // Ingredient-index encoding: exactly 0..n-1, ascending —
            // the planner fills the first n flat cells from it.
            int expected = 0;
            for (Integer pos : copied.keySet()) {
                if (pos != expected) {
                    throw new IllegalArgumentException(
                            "shapeless positions must be compact ingredient indices 0..n-1, saw " + pos);
                }
                expected++;
            }
        }
        placements = java.util.Collections.unmodifiableMap(copied);
    }

    /**
     * Whether the pattern fits the inventory menu's 2x2 grid. Recipes
     * needing a crafting table report false — the caller uses this to
     * decide which surface to open.
     *
     * @return shaped: true when every occupied cell sits in the
     *         top-left 2x2; shapeless: true while at most four
     *         ingredients exist (the vanilla count rule,
     *         {@code canCraftInDimensions(2, 2)})
     */
    public boolean fitsInventoryGrid() {
        if (shapeless) {
            return placements.size() <= 4;
        }
        for (Integer pos : placements.keySet()) {
            if (pos / patternWidth > 1 || pos % patternWidth > 1) {
                return false;
            }
        }
        return true;
    }
}
