package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Click planning for crafting surfaces: grid fills from the player
 * region, recipe realization, and result collection. All plans are
 * pure functions over snapshots - they compute before the first click
 * so an invalid request performs none.
 *
 * <p>Sourcing runs exclusively against {@link PlayerRegion} supplies.
 * Public entry points live on {@link MenuPlanner}, which delegates
 * here.
 */
final class CraftingPlanner {

    private CraftingPlanner() {}

    /**
     * Validate arguments and the open surface before any step is
     * planned: non-blank item id, well-formed non-duplicated
     * positions inside the grid, every target cell empty.
     */
    static void requireFillableGrid(CraftingView menu, String itemId, int... gridPos) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be null or blank");
        }
        if (gridPos == null || gridPos.length == 0) {
            throw new IllegalArgumentException("at least one grid position is required");
        }
        requirePositionsInBounds(menu, gridPos);
        requireNoDuplicates(gridPos);
    }

    /** Every position must address an existing cell on the open surface. */
    private static void requirePositionsInBounds(CraftingView menu, int[] gridPos) {
        for (int p : gridPos) {
            if (p < 0 || p >= menu.grid().size()) {
                throw new IllegalArgumentException("grid position " + p + " out of range for a " + menu.gridSide() + "x"
                        + menu.gridSide() + " grid");
            }
            var slot = menu.grid().get(p);
            if (slot.isEmpty()) {
                continue;
            }
            throw new IllegalArgumentException(
                    "target cell " + p + " already holds " + slot.item().itemId() + " — refusing to overwrite");
        }
    }

    /** Grid positions must not repeat - filling one cell twice has no meaning. */
    private static void requireNoDuplicates(int[] gridPos) {
        for (int i = 0; i < gridPos.length; i++) {
            for (int j = i + 1; j < gridPos.length; j++) {
                if (gridPos[i] == gridPos[j]) {
                    throw new IllegalArgumentException("duplicate grid position " + gridPos[i]);
                }
            }
        }
    }
    /**
     * Emit the lift/distribute/return sequence for one material into
     * validated cells. Sources come off the player-region ledger
     * front-first; whole stacks are lifted once and spread one item
     * per cell with right-clicks; a partially used lift returns its
     * remainder to the source slot it came from. Supply sufficiency
     * was proven by the caller before this runs.
     */
    static List<MenuPlanner.Step> emitGridFill(CraftingView menu, String itemId, Deque<int[]> supply, int... gridPos) {
        long available = 0;
        for (int[] src : supply) {
            available += src[1];
        }
        if (available < gridPos.length) {
            throw new IllegalArgumentException(
                    "insufficient " + itemId + ": need " + gridPos.length + ", player region carries " + available);
        }

        List<MenuPlanner.Step> steps = new ArrayList<>();
        int carried = 0;
        int lastSource = -1;
        for (int p : gridPos) {
            if (carried == 0) {
                int[] src = supply.poll();
                steps.add(new MenuPlanner.Step(src[0], 0, MenuClick.PICKUP));
                carried = src[1];
                lastSource = src[0];
            }
            steps.add(new MenuPlanner.Step(menu.flatGridIndex(p), 1, MenuClick.PICKUP));
            carried--;
        }
        if (carried > 0) {
            steps.add(new MenuPlanner.Step(lastSource, 0, MenuClick.PICKUP));
        }
        return List.copyOf(steps);
    }

    /**
     * Scarce-first resolution: positions whose accepted-kind list is
     * narrowest resolve first (stable within equal widths, so shaped
     * position order survives), each picking the first accepted kind
     * with remaining supply; cells group per chosen kind so each
     * sub-plan stays single-material. Scarce-first is what makes
     * nested acceptance sets ({@code {oak}} inside
     * {@code {oak, birch}}) resolve the way vanilla's multiset
     * matcher does - a naive position-order greedy would spend the
     * last oak on the wide cell and refuse the narrow one. Pattern
     * coordinates translate onto the open surface's grid row-major,
     * anchored top-left - one recipe serves both the 2x2 and the 3x3.
     * Shapeless views skip the geometry: ingredient index i fills
     * flat cell i, which vanilla's arrangement-agnostic matching
     * accepts on any surface large enough.
     *
     * @param recipe   the recipe being realized; never null
     * @param supply   mutable totals consumed as cells resolve
     * @param gridSide open surface's side length
     * @return chosen item id -> flat grid positions; never null
     */
    static Map<String, List<Integer>> resolvePattern(RecipeView recipe, Map<String, Integer> supply, int gridSide) {
        Map<String, List<Integer>> chosen = new LinkedHashMap<>();
        var placements = new ArrayList<>(recipe.placements().entrySet());
        placements.sort(Comparator.comparingInt(e -> e.getValue().size()));
        for (var placement : placements) {
            int pos = placement.getKey();
            String picked = null;
            for (String candidate : placement.getValue()) {
                Integer left = supply.get(candidate);
                if (left != null && left > 0) {
                    picked = candidate;
                    supply.put(candidate, left - 1);
                    break;
                }
            }
            if (picked == null) {
                throw new IllegalArgumentException("cannot source cell " + pos + " of "
                        + recipe.recipeId() + ": none of "
                        + placement.getValue()
                        + " remain in the player region");
            }
            int cell =
                    recipe.shapeless() ? pos : (pos / recipe.patternWidth()) * gridSide + pos % recipe.patternWidth();
            chosen.computeIfAbsent(picked, k -> new ArrayList<>()).add(cell);
        }
        return chosen;
    }

    /** Shared plan pipeline: validate, source check, then fill. */
    static Deque<int[]> checkedSupply(CraftingView menu, String itemId, int cellCount) {
        Deque<int[]> supply = PlayerRegion.ledger(menu.menu(), itemId);
        long available = 0;
        for (int[] src : supply) {
            available += src[1];
        }
        if (available < cellCount) {
            throw new IllegalArgumentException(
                    "insufficient " + itemId + ": need " + cellCount + ", player region carries " + available);
        }
        return supply;
    }
}
