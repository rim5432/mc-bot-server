package com.mcbot.mcbotserver.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.inventory.ItemView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Offline gates for {@link CraftingView}: the role-driven projection
 * that lets a planner address the crafting grid without per-kind flat
 * arithmetic. Pure Java — zero MC imports.
 *
 * <p>Contract: see boundaries.md section A (api purity) and issue 0007
 * Phase 2 ("2x2 InventoryMenu baseline, 3x3 table extension"). The
 * fakes mirror the adapter's role tables: a crafting table carries
 * result 0 + grid 1..9 + player region; the inventory menu carries
 * result 0 + grid 1..4 + armor + offhand + player region.
 */
class CraftingViewTest {

    @Test
    void projectsTheThreeByThreeTableGrid() {
        MenuView table = tableSnapshot(3);
        CraftingView view = CraftingView.of(table);
        assertEquals(3, view.gridSide());
        assertEquals(9, view.grid().size());
        assertEquals(0, view.result().index());
        // Row-major projection: grid position p maps to flat slot p+1
        // on the table layout.
        for (int p = 0; p < 9; p++) {
            assertEquals(p + 1, view.flatGridIndex(p), "grid position " + p + " must map to flat slot " + (p + 1));
        }
    }

    @Test
    void projectsTheTwoByTwoInventoryGrid() {
        // InventoryMenu shape: result 0, grid 1..4, armor 5..8,
        // offhand 45, main 9..35, hotbar 36..44 (only endpoints needed
        // here — the lens must skip every non-grid role).
        Map<Integer, SlotRole> roles = new HashMap<>();
        roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= 4; i++) {
            roles.put(i, SlotRole.GRID);
        }
        for (int i = 5; i <= 8; i++) {
            roles.put(i, SlotRole.ARMOR);
        }
        roles.put(45, SlotRole.OFFHAND);
        for (int i = 36; i <= 44; i++) {
            roles.put(i, SlotRole.HOTBAR);
        }
        MenuView inv = snapshot("inventory", 46, roles);
        CraftingView view = CraftingView.of(inv);
        assertEquals(2, view.gridSide());
        assertEquals(4, view.grid().size());
        assertEquals(0, view.result().index());
        assertEquals(1, view.flatGridIndex(0));
        assertEquals(4, view.flatGridIndex(3));
    }

    @Test
    void rejectsAMenuWithoutAResultSlot() {
        // Chest-shaped: CONTAINER region only.
        Map<Integer, SlotRole> roles = new HashMap<>();
        for (int i = 0; i < 27; i++) {
            roles.put(i, SlotRole.CONTAINER);
        }
        roles.put(27, SlotRole.HOTBAR);
        MenuView chest = snapshot("chest", 28, roles);
        assertThrows(IllegalArgumentException.class, () -> CraftingView.of(chest));
    }

    @Test
    void rejectsAGridOutsideTheVanillaPair() {
        // 7 GRID slots is neither the 2x2 nor the 3x3 surface.
        Map<Integer, SlotRole> roles = new HashMap<>();
        roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= 7; i++) {
            roles.put(i, SlotRole.GRID);
        }
        roles.put(8, SlotRole.HOTBAR);
        MenuView odd = snapshot("odd", 9, roles);
        assertThrows(IllegalArgumentException.class, () -> CraftingView.of(odd));
    }

    @Test
    void canonicalConstructorRejectsForeignRoles() {
        SlotView result = new SlotView(0, ItemView.EMPTY, SlotRole.RESULT);
        List<SlotView> badGrid = List.of(new SlotView(1, ItemView.EMPTY, SlotRole.MAIN));
        assertThrows(IllegalArgumentException.class, () -> new CraftingView(tableSnapshot(3), badGrid, result));

        SlotView notResult = new SlotView(20, ItemView.EMPTY, SlotRole.MAIN);
        List<SlotView> goodGrid = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            goodGrid.add(new SlotView(i, ItemView.EMPTY, SlotRole.GRID));
        }
        assertThrows(IllegalArgumentException.class, () -> new CraftingView(tableSnapshot(3), goodGrid, notResult));
    }

    @Test
    void gridListIsImmutable() {
        CraftingView view = CraftingView.of(tableSnapshot(3));
        assertThrows(
                UnsupportedOperationException.class,
                () -> view.grid().add(new SlotView(99, ItemView.EMPTY, SlotRole.GRID)));
    }

    @Test
    void flatGridIndexRejectsOutOfRangePositions() {
        CraftingView view = CraftingView.of(tableSnapshot(3));
        assertThrows(IndexOutOfBoundsException.class, () -> view.flatGridIndex(9));
        assertThrows(IndexOutOfBoundsException.class, () -> view.flatGridIndex(-1));
        assertTrue(view.gridSide() == 2 || view.gridSide() == 3);
    }

    /** A crafting-table-shaped snapshot: result 0, grid 1..side^2,
     * one MAIN and one HOTBAR slot after it. */
    private static MenuView tableSnapshot(int side) {
        int grid = side * side;
        Map<Integer, SlotRole> roles = new HashMap<>();
        roles.put(0, SlotRole.RESULT);
        for (int i = 1; i <= grid; i++) {
            roles.put(i, SlotRole.GRID);
        }
        roles.put(grid + 1, SlotRole.MAIN);
        roles.put(grid + 2, SlotRole.HOTBAR);
        return snapshot("crafting_table", grid + 3, roles);
    }

    /** Builds a snapshot where every unlisted slot is MAIN and every
     * item is empty. */
    private static MenuView snapshot(String type, int size, Map<Integer, SlotRole> special) {
        List<SlotView> slots = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new SlotView(i, ItemView.EMPTY, special.getOrDefault(i, SlotRole.MAIN)));
        }
        return new MenuView(type, null, ItemView.EMPTY, size, slots, null);
    }
}
