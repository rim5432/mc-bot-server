package com.mcbot.mcbotserver.api.inventory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline gates for the Phase 1 inventory sense api: {@link ItemView}
 * slot identity and {@link InventoryView} full-layout snapshot. Pure
 * Java — zero MC imports, runs on any JUnit 5 runner without a Forge
 * runtime.
 *
 * <p>Contract: see boundaries.md section A (WorldView is read-only) and
 * issue 0007 Phase 1 (inventory sense). The slot layout (36 main / 4
 * armor / 1 offhand, hotbar indices 0-8) mirrors vanilla
 * {@code Inventory.getContainerSize()}=41.
 */
class InventorySenseTest {

    // ── ItemView ──────────────────────────────────────────────

    @Test
    void emptyConstantIsTrulyEmpty() {
        assertEquals("", ItemView.EMPTY.itemId());
        assertEquals(0, ItemView.EMPTY.count());
        assertEquals("", ItemView.EMPTY.nbtDigest());
        assertTrue(ItemView.EMPTY.isEmpty());
    }

    @Test
    void twoArgConstructorDefaultsNbtToEmptyString() {
        var item = new ItemView("minecraft:diamond", 32);
        assertEquals("minecraft:diamond", item.itemId());
        assertEquals(32, item.count());
        assertEquals("", item.nbtDigest());
        assertFalse(item.isEmpty());
    }

    @Test
    void rejectsNullItemId() {
        assertThrows(IllegalArgumentException.class,
            () -> new ItemView(null, 1, ""));
    }

    @Test
    void rejectsNegativeCount() {
        assertThrows(IllegalArgumentException.class,
            () -> new ItemView("minecraft:dirt", -1, ""));
    }

    @Test
    void rejectsNullNbtDigest() {
        assertThrows(IllegalArgumentException.class,
            () -> new ItemView("minecraft:dirt", 1, null));
    }

    @Test
    void zeroCountReadsAsEmptyEvenWithId() {
        // A slot with count 0 is empty regardless of what id it carries;
        // the adapter normalizes empty stacks to ItemView.EMPTY but the
        // api type must not assume that normalization happened.
        var weird = new ItemView("minecraft:dirt", 0, "");
        assertTrue(weird.isEmpty());
    }

    // ── InventoryView construction ────────────────────────────

    @Test
    void emptyFactoryHasAllEmptySlotsAndSelectedZero() {
        var inv = InventoryView.empty();
        assertEquals(InventoryView.MAIN_SIZE, inv.main().size());
        assertEquals(InventoryView.ARMOR_SIZE, inv.armor().size());
        assertEquals(0, inv.selectedSlot());
        for (ItemView slot : inv.main()) {
            assertTrue(slot.isEmpty(), "every main slot empty");
        }
        for (ItemView slot : inv.armor()) {
            assertTrue(slot.isEmpty(), "every armor slot empty");
        }
        assertTrue(inv.offhand().isEmpty());
        assertTrue(inv.getSelected().isEmpty());
    }

    @Test
    void rejectsNullMain() {
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(null, 0, emptyArmor(), ItemView.EMPTY));
    }

    @Test
    void rejectsWrongMainSize() {
        var tooSmall = new ArrayList<ItemView>(
            List.of(ItemView.EMPTY, ItemView.EMPTY));
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(tooSmall, 0, emptyArmor(),
                ItemView.EMPTY));
    }

    @Test
    void rejectsNullArmor() {
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(emptyMain(), 0, null,
                ItemView.EMPTY));
    }

    @Test
    void rejectsWrongArmorSize() {
        var tooSmall = new ArrayList<ItemView>(List.of(ItemView.EMPTY));
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(emptyMain(), 0, tooSmall,
                ItemView.EMPTY));
    }

    @Test
    void rejectsNullOffhand() {
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(emptyMain(), 0, emptyArmor(), null));
    }

    @Test
    void rejectsSelectedSlotBelowZero() {
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(emptyMain(), -1, emptyArmor(),
                ItemView.EMPTY));
    }

    @Test
    void rejectsSelectedSlotAboveHotbar() {
        assertThrows(IllegalArgumentException.class,
            () -> new InventoryView(emptyMain(),
                InventoryView.HOTBAR_SIZE, emptyArmor(), ItemView.EMPTY));
    }

    // ── InventoryView queries ─────────────────────────────────

    @Test
    void getSelectedReturnsTheHotbarSlotAtSelectedIndex() {
        var main = emptyMain();
        main.set(3, new ItemView("minecraft:iron_pickaxe", 1));
        var inv = new InventoryView(main, 3, emptyArmor(), ItemView.EMPTY);
        assertEquals("minecraft:iron_pickaxe",
            inv.getSelected().itemId());
        assertEquals(1, inv.getSelected().count());
    }

    @Test
    void equipmentLivesOutsideTheMainSlice() {
        // Consumers that aggregate or search main() must never see the
        // armor row or offhand: equipment is not transferable stack.
        var armor = emptyArmor();
        armor.set(0, new ItemView("minecraft:iron_helmet", 1));
        var inv = new InventoryView(emptyMain(), 0, armor,
            new ItemView("minecraft:shield", 1));
        assertTrue(inv.main().stream().allMatch(ItemView.EMPTY::equals),
            "equipment leaked into the main slice");
    }

    @Test
    void hotbarReturnsFirstNineSlots() {
        var main = emptyMain();
        main.set(0, new ItemView("minecraft:diamond", 1));
        main.set(8, new ItemView("minecraft:dirt", 1));
        var inv = new InventoryView(main, 0, emptyArmor(), ItemView.EMPTY);
        var hotbar = inv.hotbar();
        assertEquals(InventoryView.HOTBAR_SIZE, hotbar.size());
        assertEquals("minecraft:diamond", hotbar.get(0).itemId());
        assertEquals("minecraft:dirt", hotbar.get(8).itemId());
    }

    @Test
    void backpackReturnsSlotsNineThroughThirtyFive() {
        var main = emptyMain();
        main.set(9, new ItemView("minecraft:diamond", 1));
        main.set(35, new ItemView("minecraft:dirt", 1));
        var inv = new InventoryView(main, 0, emptyArmor(), ItemView.EMPTY);
        var backpack = inv.backpack();
        assertEquals(InventoryView.MAIN_SIZE - InventoryView.HOTBAR_SIZE,
            backpack.size());
        assertEquals("minecraft:diamond", backpack.get(0).itemId());
        assertEquals("minecraft:dirt",
            backpack.get(backpack.size() - 1).itemId());
    }

    // ── Immutability ──────────────────────────────────────────

    @Test
    void snapshotIsImmutableAgainstCallerMutation() {
        // The record must defensively copy input lists so a caller cannot
        // mutate the snapshot after construction — this is the boundary-A
        // read-only guarantee for off-thread consumers.
        var main = emptyMain();
        main.set(0, new ItemView("minecraft:diamond", 1));
        var inv = new InventoryView(main, 0, emptyArmor(), ItemView.EMPTY);
        main.set(0, new ItemView("minecraft:dirt", 64));
        assertEquals("minecraft:diamond",
            inv.main().get(0).itemId(),
            "snapshot unaffected by post-construction mutation");
    }

    @Test
    void returnedListsAreNotTheSameInstanceAsInput() {
        var main = emptyMain();
        var inv = new InventoryView(main, 0, emptyArmor(), ItemView.EMPTY);
        assertNotSame(main, inv.main());
    }

    @Test
    void hotbarAndBackpackAreUnmodifiable() {
        var inv = InventoryView.empty();
        assertThrows(UnsupportedOperationException.class,
            () -> inv.hotbar().set(0, new ItemView("x", 1)));
        assertThrows(UnsupportedOperationException.class,
            () -> inv.backpack().set(0, new ItemView("x", 1)));
    }

    // ── helpers ────────────────────────────────────────────────

    private static List<ItemView> emptyMain() {
        var list = new ArrayList<ItemView>(InventoryView.MAIN_SIZE);
        for (int i = 0; i < InventoryView.MAIN_SIZE; i++) {
            list.add(ItemView.EMPTY);
        }
        return list;
    }

    private static List<ItemView> emptyArmor() {
        var list = new ArrayList<ItemView>(InventoryView.ARMOR_SIZE);
        for (int i = 0; i < InventoryView.ARMOR_SIZE; i++) {
            list.add(ItemView.EMPTY);
        }
        return list;
    }
}
