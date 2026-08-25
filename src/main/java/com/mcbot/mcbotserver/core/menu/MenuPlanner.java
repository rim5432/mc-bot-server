package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.CraftingView;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.SlotRole;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Plans click sequences against a {@link CraftingView} snapshot. Pure
 * static planning — reads the snapshot, emits {@link Step}s, touches
 * nothing: the caller executes steps through the boundary-A transaction
 * surface ({@code MenuTransactions.menuClick}) so every mutation rides
 * the vanilla menu state machine and {@code ResultSlot.onTake} keeps
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
 * starts from an empty cursor.
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
}
