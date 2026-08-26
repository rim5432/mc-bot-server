package com.mcbot.mcbotserver.api.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only snapshot of the bot's full inventory layout. Pure data —
 * the snapshot is captured once per perception tick and is immutable
 * thereafter; planners read it without locks.
 *
 * <p>Contract: see boundaries.md section A (WorldView is read-only) and
 * issue 0007 Phase 1 (inventory sense). The slot layout mirrors
 * vanilla {@code Inventory}: 36 main slots (indices 0-8 hotbar, 9-35
 * backpack), 4 armor slots (head / chest / legs / feet in that order),
 * and one offhand. {@code selectedSlot} is a hotbar index (0-8),
 * matching the carrier's {@code selectedSlot} field.
 *
 * <p>Why a snapshot and not a live view: the core planner may run
 * off-thread in Stage 2 (decision 17b SNAPSHOT mode); a live
 * container reference would cross threads and race with menu clicks.
 * The snapshot is the boundary-A-safe shape.
 *
 * @param main         36 main slots (0-8 hotbar, 9-35 backpack);
 *                     never null, never null elements, size exactly 36
 * @param selectedSlot hotbar index of the currently held item; 0-8
 * @param armor        4 armor slots (head, chest, legs, feet); never
 *                     null, never null elements, size exactly 4
 * @param offhand      the offhand slot; never null
 */
public record InventoryView(
    List<ItemView> main,
    int selectedSlot,
    List<ItemView> armor,
    ItemView offhand
) {

    /** Number of main inventory slots (9 hotbar + 27 backpack). */
    public static final int MAIN_SIZE = 36;

    /** Number of hotbar slots (the first 9 of {@link #main}). */
    public static final int HOTBAR_SIZE = 9;

    /** Number of armor slots (head, chest, legs, feet). */
    public static final int ARMOR_SIZE = 4;

    /**
     * Creates a validated inventory snapshot. Defensive copies make the
     * record immutable even if the caller mutates the source lists after
     * construction.
     *
     * @param main         must not be null; size must be exactly 36
     * @param selectedSlot must be in 0..8
     * @param armor        must not be null; size must be exactly 4
     * @param offhand      must not be null
     */
    public InventoryView {
        if (main == null) {
            throw new IllegalArgumentException("main must not be null");
        }
        if (main.size() != MAIN_SIZE) {
            throw new IllegalArgumentException(
                "main size must be " + MAIN_SIZE + ", was " + main.size());
        }
        if (selectedSlot < 0 || selectedSlot >= HOTBAR_SIZE) {
            throw new IllegalArgumentException(
                "selectedSlot must be 0.." + (HOTBAR_SIZE - 1)
                    + ", was " + selectedSlot);
        }
        if (armor == null) {
            throw new IllegalArgumentException("armor must not be null");
        }
        if (armor.size() != ARMOR_SIZE) {
            throw new IllegalArgumentException(
                "armor size must be " + ARMOR_SIZE + ", was "
                    + armor.size());
        }
        if (offhand == null) {
            throw new IllegalArgumentException("offhand must not be null");
        }
        main = List.copyOf(main);
        armor = List.copyOf(armor);
    }

    /**
     * An empty inventory: every slot {@link ItemView#EMPTY}, selected
     * slot 0.
     *
     * @return a fresh empty snapshot; never null
     */
    public static InventoryView empty() {
        List<ItemView> mainSlots = new ArrayList<>(MAIN_SIZE);
        for (int i = 0; i < MAIN_SIZE; i++) {
            mainSlots.add(ItemView.EMPTY);
        }
        List<ItemView> armorSlots = new ArrayList<>(ARMOR_SIZE);
        for (int i = 0; i < ARMOR_SIZE; i++) {
            armorSlots.add(ItemView.EMPTY);
        }
        return new InventoryView(mainSlots, 0, armorSlots, ItemView.EMPTY);
    }

    /**
     * The item currently held in the selected hotbar slot.
     *
     * @return the hotbar slot at {@link #selectedSlot}; never null
     */
    public ItemView getSelected() {
        return main.get(selectedSlot);
    }

    /**
     * Read-only hotbar slice (indices 0-8 of {@link #main}).
     *
     * @return unmodifiable list of the 9 hotbar slots; never null
     */
    public List<ItemView> hotbar() {
        return Collections.unmodifiableList(main.subList(0, HOTBAR_SIZE));
    }

    /**
     * Read-only backpack slice (indices 9-35 of {@link #main}).
     *
     * @return unmodifiable list of the 27 backpack slots; never null
     */
    public List<ItemView> backpack() {
        return Collections.unmodifiableList(
            main.subList(HOTBAR_SIZE, MAIN_SIZE));
    }
}
