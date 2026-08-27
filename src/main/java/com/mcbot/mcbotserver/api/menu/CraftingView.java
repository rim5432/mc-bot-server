package com.mcbot.mcbotserver.api.menu;

import java.util.List;

/**
 * Typed lens over a crafting-capable menu snapshot: the grid cells and
 * the result slot, addressed by role instead of flat layout arithmetic.
 * Pure derived data — {@link #of} projects a {@link MenuView} through
 * its slot roles, so the planner never re-derives which flat indices
 * happen to be the 3x3 on this menu kind.
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 2 ("CraftingView — 2x2 InventoryMenu baseline, 3x3 table
 * extension"). Both crafting surfaces project through the same lens:
 * the inventory menu's 2x2 grid and the crafting table's 3x3 grid
 * differ only in {@link #gridSide()}. The grid list preserves the
 * snapshot's GRID-role order, which is vanilla's row-major
 * {@code addSlot} order for both kinds — grid position {@code p}
 * (0..size-1) reads as row {@code p / side}, column {@code p % side}.
 *
 * @param menu   the full snapshot this lens was projected from; never
 *               null
 * @param grid   the crafting-grid slots in row-major order (4 or 9
 *               entries); never null, immutable
 * @param result the crafting result slot; never null
 */
public record CraftingView(MenuView menu, List<SlotView> grid, SlotView result) {

    /**
     * Projects a crafting lens out of a menu snapshot.
     *
     * @param menu the snapshot to project; must carry exactly one
     *             RESULT-role slot and a vanilla crafting grid (4 or 9
     *             GRID-role slots); never null
     * @return the lens; never null
     * @throws IllegalArgumentException when the menu has no result
     *                                  slot or no crafting grid (a
     *                                  chest, for example), a grid
     *                                  size outside the vanilla 2x2 /
     *                                  3x3 pair, or any grid slot
     *                                  whose role is not GRID
     */
    public static CraftingView of(MenuView menu) {
        SlotView result = null;
        List<SlotView> grid = new java.util.ArrayList<>();
        for (SlotView slot : menu.slots()) {
            if (slot.role() == SlotRole.RESULT) {
                result = slot;
            } else if (slot.role() == SlotRole.GRID) {
                grid.add(slot);
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("menu '" + menu.type() + "' has no RESULT slot");
        }
        if (grid.size() != 4 && grid.size() != 9) {
            throw new IllegalArgumentException(
                    "menu '" + menu.type() + "' grid must have 4 (2x2) or" + " 9 (3x3) slots, got " + grid.size());
        }
        return new CraftingView(menu, grid, result);
    }

    /**
     * Creates a validated crafting lens. Prefer {@link #of}; the
     * canonical constructor is public because records are data.
     *
     * @param menu   must not be null
     * @param grid   must hold 4 (2x2) or 9 (3x3) entries, all
     *               GRID-role; never null
     * @param result must be RESULT-role; never null
     * @throws IllegalArgumentException when any invariant above fails
     */
    public CraftingView {
        if (menu == null) {
            throw new IllegalArgumentException("menu must not be null");
        }
        if (grid == null || (grid.size() != 4 && grid.size() != 9)) {
            throw new IllegalArgumentException(
                    "grid must have 4 (2x2) or 9 (3x3) slots, got " + (grid == null ? "null" : grid.size()));
        }
        if (result == null || result.role() != SlotRole.RESULT) {
            throw new IllegalArgumentException("result slot must carry the RESULT role");
        }
        for (SlotView slot : grid) {
            if (slot.role() != SlotRole.GRID) {
                throw new IllegalArgumentException("grid slot " + slot.index() + " must carry the GRID role");
            }
        }
        grid = List.copyOf(grid);
    }

    /**
     * The grid's side length: 2 for the inventory menu's baseline
     * grid, 3 for a crafting table.
     *
     * @return 2 or 3
     */
    public int gridSide() {
        return grid.size() == 4 ? 2 : 3;
    }

    /**
     * The flat menu index of a grid position — the number a click
     * targets.
     *
     * @param gridPos row-major grid position, 0..grid size-1
     * @return the menu's flat slot index for that cell
     * @throws IndexOutOfBoundsException when gridPos is out of range
     */
    public int flatGridIndex(int gridPos) {
        return grid.get(gridPos).index();
    }
}
