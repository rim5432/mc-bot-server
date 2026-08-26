package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans click sequences against menu snapshots: crafting fills,
 * result takes, and chest deposits/withdrawals. Pure static planning
 * — reads the snapshot, emits {@link Step}s, touches nothing: the
 * caller executes steps through the boundary-A transaction surface
 * ({@code MenuTransactions.menuClick}) so every mutation rides the
 * vanilla menu state machine and {@code ResultSlot.onTake} keeps
 * consuming grid materials.
 *
 * <p>Contract: see boundaries.md section A (core holds no menu state)
 * and issue 0007 Phase 2 ("core click-sequence planner over the
 * read-only view"). Plans are computed from ONE snapshot; execution
 * must happen while the menu is unchanged (freshly opened menus are
 * the intended regime). Re-planning after foreign mutations is the
 * caller's job — the planner never re-reads between steps.
 *
 * <p>Click economics: one whole-stack lift sources every placement it
 * can, right-clicks deposit exactly one item per grid cell, and any
 * remainder returns to the slot it was lifted from. Mixed recipes are
 * composed by chaining single-material plans — each plan below ends
 * with the carried stack either spent or returned, so the next plan
 * starts from an empty cursor. Chest transfers ride QUICK_MOVE and
 * therefore move WHOLE stacks: withdrawals may overshoot the demanded
 * count rather than split stacks.
 */
// contract: see boundaries.md §A (planner reads views; mutation only
// through the transaction surface)
public final class MenuPlanner {

    /** One click for the executor: pass the triple straight to
     * {@code menuClick}. */
    public record Step(int slot, int button, MenuClick kind) {
    }

    private MenuPlanner() {
    }

    /**
     * Plan placing one item kind into the given grid cells. Sources are
     * the player region (MAIN then HOTBAR, snapshot order); whole
     * stacks are lifted once and distributed one item per cell with
     * right-clicks; a partially used lift returns its remainder to the
     * source slot it came from.
     *
     * @param menu     the freshly snapshotted crafting surface; never
     *                 null
     * @param itemId   registry key of the material, e.g.
     *                 {@code minecraft:diamond}; never null or blank
     * @param gridPos  row-major grid positions to fill, each 0..grid
     *                 size-1; at least one, no duplicates
     * @return the click sequence in execution order; never null,
     *         immutable
     * @throws IllegalArgumentException when an argument is malformed
     *                                 (position out of range or
     *                                 duplicated, blank item id), a
     *                                 target cell already holds an
     *                                 item, or the player region
     *                                 carries fewer than the demanded
     *                                 count of the item
     */
    public static List<Step> planGridFill(CraftingView menu,
                                          String itemId,
                                          int... gridPos) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                "itemId must not be null or blank");
        }
        if (gridPos == null || gridPos.length == 0) {
            throw new IllegalArgumentException(
                "at least one grid position is required");
        }

        for (int p : gridPos) {
            if (p < 0 || p >= menu.grid().size()) {
                throw new IllegalArgumentException(
                    "grid position " + p + " out of range for a "
                        + menu.gridSide() + "x" + menu.gridSide()
                        + " grid");
            }
            if (!menu.grid().get(p).isEmpty()) {
                throw new IllegalArgumentException(
                    "target cell " + p + " already holds "
                        + menu.grid().get(p).item().itemId()
                        + " — refusing to overwrite");
            }
        }
        for (int i = 0; i < gridPos.length; i++) {
            for (int j = i + 1; j < gridPos.length; j++) {
                if (gridPos[i] == gridPos[j]) {
                    throw new IllegalArgumentException(
                        "duplicate grid position " + gridPos[i]);
                }
            }
        }

        Deque<int[]> supply = sourceLedger(menu.menu(), itemId);
        long available = 0;
        for (int[] src : supply) {
            available += src[1];
        }
        if (available < gridPos.length) {
            throw new IllegalArgumentException(
                "insufficient " + itemId + ": need " + gridPos.length
                    + ", player region carries " + available);
        }

        List<Step> steps = new ArrayList<>();
        int carried = 0;
        int lastSource = -1;
        for (int p : gridPos) {
            if (carried == 0) {
                int[] src = supply.poll();
                steps.add(new Step(src[0], 0, MenuClick.PICKUP));
                carried = src[1];
                lastSource = src[0];
            }
            steps.add(new Step(menu.flatGridIndex(p), 1,
                MenuClick.PICKUP));
            carried--;
        }
        if (carried > 0) {
            steps.add(new Step(lastSource, 0, MenuClick.PICKUP));
        }
        return List.copyOf(steps);
    }

    /**
     * Plan taking the crafted product into the player inventory.
     *
     * @param menu the crafting surface whose grid currently matches a
     *             recipe; never null
     * @return the click sequence (a single shift-click on the result
     *         slot); never null, immutable
     * @throws IllegalStateException when the result slot is empty —
     *                               nothing was crafted (or the grid
     *                               pattern does not resolve)
     */
    public static List<Step> planTakeResult(CraftingView menu) {
        if (menu.result().isEmpty()) {
            throw new IllegalStateException(
                "result slot is empty — nothing to take");
        }
        return List.of(new Step(menu.result().index(), 0,
            MenuClick.QUICK_MOVE));
    }

    /**
     * Plan filling a crafting surface straight from a recipe
     * description. Each pattern cell resolves to the first accepted
     * item kind the player region actually carries (first-fit against
     * snapshot supply), cells are grouped per chosen kind, and one
     * {@link #planGridFill} runs per group — pattern coordinates
     * translate onto the open surface's grid (row-major anchoring at
     * the top-left), so one recipe serves both the 2x2 and the 3x3.
     *
     * @param menu   the freshly snapshotted crafting surface; never
     *               null
     * @param recipe the recipe to realize; never null
     * @return the click sequence in execution order; never null,
     *         immutable
     * @throws IllegalArgumentException when the recipe needs a table
     *                                  but a 2x2 inventory grid is
     *                                  open, or the player region
     *                                  cannot cover every cell from
     *                                  its accepted kinds
     */
    public static List<Step> planRecipe(CraftingView menu,
                                        RecipeView recipe) {
        if (!recipe.fitsInventoryGrid() && menu.gridSide() < 3) {
            throw new IllegalArgumentException(
                recipe.recipeId() + " needs a crafting table; this"
                    + " menu only offers a 2x2 grid");
        }

        Map<String, Integer> supply = playerRegionTotals(menu.menu());
        // First-fit resolution: ascending pattern positions, first
        // accepted kind with remaining supply. Grouping by kind keeps
        // each sub-plan single-material.
        Map<String, List<Integer>> chosen = new LinkedHashMap<>();
        for (Integer pos : recipe.placements().keySet()) {
            String picked = null;
            for (String candidate : recipe.placements().get(pos)) {
                Integer left = supply.get(candidate);
                if (left != null && left > 0) {
                    picked = candidate;
                    supply.put(candidate, left - 1);
                    break;
                }
            }
            if (picked == null) {
                throw new IllegalArgumentException(
                    "cannot source cell " + pos + " of "
                        + recipe.recipeId() + ": none of "
                        + recipe.placements().get(pos)
                        + " remain in the player region");
            }
            int row = pos / recipe.patternWidth();
            int col = pos % recipe.patternWidth();
            chosen.computeIfAbsent(picked, k -> new ArrayList<>())
                .add(row * menu.gridSide() + col);
        }

        List<Step> steps = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> group : chosen
                .entrySet()) {
            List<Integer> cells = group.getValue();
            steps.addAll(planGridFill(menu, group.getKey(),
                cells.stream().mapToInt(Integer::intValue)
                    .toArray()));
        }
        return List.copyOf(steps);
    }

    /**
     * Plan pulling an item kind out of an opened container into the
     * player region. Rides QUICK_MOVE, so whole stacks move: the plan
     * stops once at least the demanded count has been sourced, which
     * may overshoot into the next stack.
     *
     * @param menu     the opened container's snapshot; never null
     * @param itemId   registry key of the material; never null or
     *                 blank
     * @param minCount minimum items that must reach the player
     *                 region; positive
     * @return the click sequence in execution order; never null,
     *         immutable
     * @throws IllegalArgumentException when an argument is malformed
     *                                 or the container holds fewer
     *                                 than minCount of the item
     */
    public static List<Step> planWithdraw(MenuView menu, String itemId,
                                          int minCount) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                "itemId must not be null or blank");
        }
        if (minCount <= 0) {
            throw new IllegalArgumentException(
                "minCount must be positive, got " + minCount);
        }
        Deque<int[]> sources = new ArrayDeque<>();
        int available = 0;
        for (var slot : menu.slots()) {
            if (slot.role() == SlotRole.CONTAINER && !slot.isEmpty()
                && slot.item().itemId().equals(itemId)) {
                sources.add(new int[] {slot.index(),
                    slot.item().count()});
                available += slot.item().count();
            }
        }
        if (available < minCount) {
            throw new IllegalArgumentException(
                "insufficient " + itemId + " in container: need "
                    + minCount + ", holds " + available);
        }
        List<Step> steps = new ArrayList<>();
        int collected = 0;
        while (collected < minCount) {
            int[] src = sources.poll();
            steps.add(new Step(src[0], 0, MenuClick.QUICK_MOVE));
            collected += src[1];
        }
        return List.copyOf(steps);
    }

    /**
     * Plan moving every stack of an item kind from the player region
     * into the opened container ("banking"). Rides QUICK_MOVE, whole
     * stacks; assumes the container has room — a full chest silently
     * leaves overflow behind at execution time, which verification
     * after the run must catch.
     *
     * @param menu   the opened container's snapshot; never null
     * @param itemId registry key of the product to bank; never null
     *               or blank
     * @return the click sequence in execution order; never null,
     *         immutable
     * @throws IllegalArgumentException when itemId is null or blank
     * @throws IllegalStateException    when the player region holds no
     *                                  stack of the item
     */
    public static List<Step> planDeposit(MenuView menu, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                "itemId must not be null or blank");
        }
        List<Step> steps = new ArrayList<>();
        for (var slot : menu.slots()) {
            boolean playerRegion = slot.role() == SlotRole.MAIN
                || slot.role() == SlotRole.HOTBAR;
            if (playerRegion && !slot.isEmpty()
                && slot.item().itemId().equals(itemId)) {
                steps.add(new Step(slot.index(), 0,
                    MenuClick.QUICK_MOVE));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException(
                "nothing to deposit: the player region holds no "
                    + itemId);
        }
        return List.copyOf(steps);
    }

    /**
     * Plans an exact-count deposit into one role's slots (issue 0012
     * D1: {@code menu deposit <slotRole> <item> <count>}).
     *
     * <p>Exact counts need split carries, not QUICK_MOVE (which moves
     * whole stacks): lift a source stack with a left-click, place
     * exactly one item per right-click onto the target, return the
     * remainder with a final left-click on the source. The whole plan
     * is computed before the first click - an under-supplied or
     * over-full request performs zero clicks.
     *
     * <p>Capacity uses the 64-stack assumption for same-item
     * headroom; items with smaller stack maximums simply stop filling
     * early (vanilla rejects the excess place clicks and the remainder
     * returns to the source), so the assumption is safe, never
     * lossy.
     *
     * @param menu   snapshot of the open menu; never null
     * @param target the role to deposit into (CONTAINER on chests,
     *               INPUT / FUEL on furnace kinds); the wire layer
     *               resolves intent roles per menu kind before calling
     * @param itemId the item to move; never null or blank
     * @param count  exact number of items to deposit; positive
     * @return the click sequence in execution order; never null
     * @throws IllegalArgumentException when itemId or count is
     *         malformed, the player region holds fewer than count of
     *         the item, or the target role's free capacity cannot
     *         hold the deposit
     */
    public static List<Step> planDepositCounted(MenuView menu,
                                                SlotRole target,
                                                String itemId,
                                                int count) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException(
                "itemId must not be null or blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException(
                "count must be positive, got " + count);
        }
        int supply = 0;
        for (var slot : menu.slots()) {
            if (playerRegion(slot) && !slot.isEmpty()
                    && slot.item().itemId().equals(itemId)) {
                supply += slot.item().count();
            }
        }
        if (supply < count) {
            throw new IllegalArgumentException(
                "not enough: have " + supply + ", need " + count);
        }
        // Target capacity: empty slots take up to 64, same-item slots
        // take their headroom. Deposits fill targets in snapshot
        // order.
        record Target(int index, int room) { }
        List<Target> targets = new ArrayList<>();
        for (var slot : menu.slots()) {
            if (slot.role() != target) {
                continue;
            }
            int room = slot.isEmpty() ? 64
                : slot.item().itemId().equals(itemId)
                    ? 64 - slot.item().count()
                    : 0;
            if (room > 0) {
                targets.add(new Target(slot.index(), room));
            }
        }
        int capacity = targets.stream().mapToInt(Target::room).sum();
        if (capacity < count) {
            throw new IllegalArgumentException(
                "target " + target + " cannot hold " + count
                    + " (room " + capacity + ")");
        }
        Deque<int[]> sources = sourceLedger(menu, itemId);
        List<Step> steps = new ArrayList<>();
        int remaining = count;
        int targetIdx = 0;
        int targetRoom = targets.isEmpty() ? 0 : targets.get(0).room();
        while (remaining > 0) {
            int[] source = sources.pop();
            int take = Math.min(source[1], remaining);
            int src = source[0];
            steps.add(new Step(src, 0, MenuClick.PICKUP));
            for (int placed = 0; placed < take; placed++) {
                while (targetRoom == 0) {
                    targetIdx++;
                    targetRoom = targets.get(targetIdx).room();
                }
                steps.add(new Step(targets.get(targetIdx).index(), 1,
                    MenuClick.PICKUP));
                targetRoom--;
            }
            // Return the remainder (a no-op click when the whole
            // stack was placed: both carried and source are empty).
            steps.add(new Step(src, 0, MenuClick.PICKUP));
            remaining -= take;
        }
        return List.copyOf(steps);
    }

    /**
     * Plans taking from one role's slots into the player region
     * (issue 0012 D1: {@code menu take <slotRole> [count]}).
     *
     * <p>{@code count <= 0} drains: one QUICK_MOVE (shift-click) per
     * non-empty role slot, whole stacks - an empty role plans zero
     * steps and the reply honestly reports {@code taken:0}. A
     * positive count is exact, using the same split-carry mechanics as
     * {@link #planDepositCounted}: lift a source stack, right-click a
     * destination player slot once per item, return the remainder.
     *
     * @param menu   snapshot of the open menu; never null
     * @param source the role to take from (OUTPUT on furnace kinds,
     *               CONTAINER on chests); the wire layer resolves
     *               intent roles per menu kind before calling
     * @param count  exact number of items to take, or 0/negative to
     *               drain every non-empty source slot
     * @return the click sequence in execution order; never null
     * @throws IllegalArgumentException when a counted take cannot find
     *         destination room in the player region (no empty slot
     *         and no same-item headroom)
     */
    public static List<Step> planTakeRole(MenuView menu, SlotRole source,
                                          int count) {
        List<Step> steps = new ArrayList<>();
        if (count <= 0) {
            for (var slot : menu.slots()) {
                if (slot.role() == source && !slot.isEmpty()) {
                    steps.add(new Step(slot.index(), 0,
                        MenuClick.QUICK_MOVE));
                }
            }
            return List.copyOf(steps);
        }
        // Counted take: destination = first player slots with room
        // (empty, or same-item headroom under the 64 assumption).
        List<int[]> sourceStacks = new ArrayList<>();
        int available = 0;
        for (var slot : menu.slots()) {
            if (slot.role() == source && !slot.isEmpty()) {
                sourceStacks.add(
                    new int[] {slot.index(), slot.item().count()});
                available += slot.item().count();
            }
        }
        int planned = Math.min(available, count);
        List<int[]> destinations = new ArrayList<>();
        int destRoom = 0;
        for (var slot : menu.slots()) {
            if (!playerRegion(slot)) {
                continue;
            }
            int room = slot.isEmpty() ? 64
                : 64 - slot.item().count();
            if (room > 0) {
                destinations.add(new int[] {slot.index(), room});
                destRoom += room;
            }
        }
        if (destRoom < planned) {
            throw new IllegalArgumentException(
                "player inventory cannot hold the take (room "
                    + destRoom + ", need " + planned + ")");
        }
        int remaining = planned;
        int destIdx = 0;
        int room = destinations.isEmpty() ? 0 : destinations.get(0)[1];
        for (int[] stack : sourceStacks) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(stack[1], remaining);
            int src = stack[0];
            steps.add(new Step(src, 0, MenuClick.PICKUP));
            for (int taken = 0; taken < take; taken++) {
                while (room == 0) {
                    destIdx++;
                    room = destinations.get(destIdx)[1];
                }
                steps.add(new Step(destinations.get(destIdx)[0], 1,
                    MenuClick.PICKUP));
                room--;
            }
            steps.add(new Step(src, 0, MenuClick.PICKUP));
            remaining -= take;
        }
        return List.copyOf(steps);
    }

    private static boolean playerRegion(
            com.mcbot.mcbotserver.api.menu.SlotView slot) {
        return slot.role() == SlotRole.MAIN
            || slot.role() == SlotRole.HOTBAR;
    }

    /**
     * Collects the player-region slots holding the item, in snapshot
     * order (MAIN before HOTBAR on every current menu kind).
     *
     * @param menu   the full snapshot; never null
     * @param itemId the item to source; never null
     * @return queue of {flat slot, count} pairs, front = first source;
     *         never null
     */
    private static Deque<int[]> sourceLedger(
            com.mcbot.mcbotserver.api.menu.MenuView menu, String itemId) {
        Deque<int[]> ledger = new ArrayDeque<>();
        for (var slot : menu.slots()) {
            boolean playerRegion = slot.role() == SlotRole.MAIN
                || slot.role() == SlotRole.HOTBAR;
            if (playerRegion && !slot.isEmpty()
                && slot.item().itemId().equals(itemId)) {
                ledger.add(new int[] {slot.index(),
                    slot.item().count()});
            }
        }
        return ledger;
    }

    /**
     * Total carried count per item kind across the player region —
     * the supply pool recipe resolution draws from.
     *
     * @param menu the full snapshot; never null
     * @return item id → total count; never null, possibly empty
     */
    private static Map<String, Integer> playerRegionTotals(
            com.mcbot.mcbotserver.api.menu.MenuView menu) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (var slot : menu.slots()) {
            boolean playerRegion = slot.role() == SlotRole.MAIN
                || slot.role() == SlotRole.HOTBAR;
            if (playerRegion && !slot.isEmpty()) {
                totals.merge(slot.item().itemId(),
                    slot.item().count(), Integer::sum);
            }
        }
        return totals;
    }
}
