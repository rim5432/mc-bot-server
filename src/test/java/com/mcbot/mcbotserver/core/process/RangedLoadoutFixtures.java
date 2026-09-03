package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared ranged-loadout inventory snapshots for the directed-combat
 * process tests (attack/defend): the minimal bow rig and the
 * bow-plus-melee rig that decides the standoff-vs-swing routing.
 */
final class RangedLoadoutFixtures {

    private RangedLoadoutFixtures() {}

    /**
     * An own-inventory snapshot carrying a bow in slot 0 and arrows in
     * the backpack - the minimal ranged loadout.
     *
     * @return the inventory view; never null
     */
    static InventoryView inventoryWithBow() {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bow", 1));
        main.set(InventoryView.HOTBAR_SIZE, new ItemView("minecraft:arrow", 32));
        return new InventoryView(
                main, 0, List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)), ItemView.EMPTY);
    }

    /**
     * The bow-plus-sword loadout: a ranged loadout that must NOT
     * trigger the standoff opening.
     *
     * @return the inventory view; never null
     */
    static InventoryView inventoryWithBowAndSword() {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bow", 1));
        main.set(1, new ItemView("minecraft:iron_sword", 1));
        main.set(InventoryView.HOTBAR_SIZE, new ItemView("minecraft:arrow", 32));
        return new InventoryView(
                main, 0, List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)), ItemView.EMPTY);
    }
}
