package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.ArmorCatalog;
import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.RecipeView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Public planner facade: computes click sequences over snapshots of
 * menus without performing any of them. Dispatch only - the planning
 * implementations live in {@link CraftingPlanner} (crafting surfaces),
 * {@link TransferPlanner} (container/furnace transfers, exact-count
 * split carries), {@link WearPlanner} (armor wearing) and the shared
 * sourcing primitives in
 * {@link PlayerRegion}. All plans are computed before the first
 * click, so an invalid request performs none.
 */
public final class MenuPlanner {

    /** One click for the executor: pass the triple straight to
     * {@code menuClick}. */
    public record Step(int slot, int button, MenuClick kind) {}

    private MenuPlanner() {}

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
    public static List<Step> planGridFill(CraftingView menu, String itemId, int... gridPos) {
        CraftingPlanner.requireFillableGrid(menu, itemId, gridPos);
        Deque<int[]> supply = CraftingPlanner.checkedSupply(menu, itemId, gridPos.length);
        return CraftingPlanner.emitGridFill(menu, itemId, supply, gridPos);
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
            throw new IllegalStateException("result slot is empty — nothing to take");
        }
        return List.of(new Step(menu.result().index(), 0, MenuClick.QUICK_MOVE));
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
    public static List<Step> planRecipe(CraftingView menu, RecipeView recipe) {
        if (!recipe.fitsInventoryGrid() && menu.gridSide() < 3) {
            throw new IllegalArgumentException(
                    recipe.recipeId() + " needs a crafting table; this" + " menu only offers a 2x2 grid");
        }

        Map<String, Integer> supply = PlayerRegion.totals(menu.menu());
        Map<String, List<Integer>> chosen = CraftingPlanner.resolvePattern(recipe, supply, menu.gridSide());

        List<Step> steps = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> group : chosen.entrySet()) {
            List<Integer> cells = group.getValue();
            steps.addAll(planGridFill(
                    menu,
                    group.getKey(),
                    cells.stream().mapToInt(Integer::intValue).toArray()));
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
    public static List<Step> planWithdraw(MenuView menu, String itemId, int minCount) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be null or blank");
        }
        if (minCount <= 0) {
            throw new IllegalArgumentException("minCount must be positive, got " + minCount);
        }
        Deque<int[]> sources = new ArrayDeque<>(TransferPlanner.containerStacks(menu, itemId));
        int available = TransferPlanner.total(sources);
        if (available < minCount) {
            throw new IllegalArgumentException(
                    "insufficient " + itemId + " in container: need " + minCount + ", holds " + available);
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
            throw new IllegalArgumentException("itemId must not be null or blank");
        }
        List<Step> steps = new ArrayList<>();
        for (var slot : PlayerRegion.slots(menu)) {
            if (!slot.isEmpty() && slot.item().itemId().equals(itemId)) {
                steps.add(new Step(slot.index(), 0, MenuClick.QUICK_MOVE));
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("nothing to deposit: the player region holds no " + itemId);
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
     * @param itemId the item to move in canonical registry-key form
     *               ({@code minecraft:iron_ore}); never null or blank.
     *               Wire entry points normalize via
     *               {@link com.mcbot.mcbotserver.api.inventory.ItemIds#normalize(String)}
     *               before calling; a bare id matches nothing and reads
     *               as zero supply
     * @param count  exact number of items to deposit; positive
     * @return the click sequence in execution order; never null
     * @throws IllegalArgumentException when itemId or count is
     *         malformed, the player region holds fewer than count of
     *         the item, or the target role's free capacity cannot
     *         hold the deposit
     */
    public static List<Step> planDepositCounted(MenuView menu, SlotRole target, String itemId, int count) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be null or blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }
        int supply = PlayerRegion.countOf(menu, itemId);
        if (supply == 0) {
            throw new IllegalArgumentException("item not found in player region: " + itemId);
        }
        if (supply < count) {
            throw new IllegalArgumentException("not enough: have " + supply + ", need " + count);
        }
        // Target capacity: empty slots take up to 64, same-item slots
        // take their headroom. Deposits fill targets in snapshot order.
        List<int[]> targets = TransferPlanner.roomForItem(slot -> slot.role() == target, itemId, menu);
        int capacity = TransferPlanner.total(targets);
        if (capacity < count) {
            throw new IllegalArgumentException(
                    "target " + target + " cannot hold " + count + " (room " + capacity + ")");
        }
        List<Step> steps = new ArrayList<>();
        TransferPlanner.splitCarry(PlayerRegion.ledger(menu, itemId), targets, count, steps);
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
    public static List<Step> planTakeRole(MenuView menu, SlotRole source, int count) {
        List<Step> steps = new ArrayList<>();
        if (count <= 0) {
            for (var slot : menu.slots()) {
                if (slot.role() == source && !slot.isEmpty()) {
                    steps.add(new Step(slot.index(), 0, MenuClick.QUICK_MOVE));
                }
            }
            return List.copyOf(steps);
        }
        // Counted take: destination = first player slots with room
        // (empty, or same-item headroom under the 64 assumption).
        List<int[]> sourceStacks = TransferPlanner.roleStacks(menu, source);
        int available = TransferPlanner.total(sourceStacks);
        int planned = Math.min(available, count);
        List<int[]> destinations = TransferPlanner.roomOnPlayerSlots(menu);
        int destRoom = TransferPlanner.total(destinations);
        if (destRoom < planned) {
            throw new IllegalArgumentException(
                    "player inventory cannot hold the take (room " + destRoom + ", need " + planned + ")");
        }
        TransferPlanner.splitCarry(sourceStacks, destinations, planned, steps);
        return List.copyOf(steps);
    }

    /**
     * Plan wearing the best available armor: for each own-inventory
     * armor slot (flat 5..8, head..feet), the highest-protection
     * wearable in the player region replaces a strictly worse (or
     * absent) worn piece; equal or unclassifiable worn pieces stay
     * on. Computed on one snapshot - moves never interfere, see
     * {@link WearPlanner}.
     *
     * @param menu    the own-inventory snapshot; never null
     * @param catalog armor classification; never null
     * @return the click sequence in execution order; never null, may
     *         be empty when nothing improves
     * @throws IllegalArgumentException when the menu carries no
     *                                  ARMOR-role slots
     */
    public static List<Step> planWear(MenuView menu, ArmorCatalog catalog) {
        return WearPlanner.plan(menu, catalog);
    }
}
