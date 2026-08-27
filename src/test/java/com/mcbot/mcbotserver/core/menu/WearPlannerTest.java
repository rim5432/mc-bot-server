package com.mcbot.mcbotserver.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.menu.ArmorCatalog;
import com.mcbot.mcbotserver.api.menu.ArmorCatalog.ArmorPiece;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for wear planning (Stage 3 Phase 4 wear slice):
 * best-per-slot selection from the player region, strictly-better
 * replacement, the lift-swap-return click triple, and the
 * no-armor-slots guard - all over synthetic own-inventory snapshots
 * with a table-backed catalog.
 */
class WearPlannerTest {

    private static final String DIAMOND_HELMET = "minecraft:diamond_helmet";

    private static final String IRON_HELMET = "minecraft:iron_helmet";

    private static final String DIAMOND_CHEST = "minecraft:diamond_chestplate";

    private static final String DIAMOND_BOOTS = "minecraft:diamond_boots";

    private static final String SWORD = "minecraft:diamond_sword";

    /** Helmet fits 5, chest 6, boots 8; diamond rank 3 beats iron 2. */
    private static final ArmorCatalog CATALOG = new ArmorCatalog() {
        private final Map<String, ArmorPiece> table = Map.of(
                DIAMOND_HELMET, new ArmorPiece(5, 3),
                IRON_HELMET, new ArmorPiece(5, 2),
                DIAMOND_CHEST, new ArmorPiece(6, 3),
                DIAMOND_BOOTS, new ArmorPiece(8, 3));

        @Override
        public ArmorPiece classify(String itemId) {
            return table.get(itemId);
        }
    };

    @Test
    void wearsBestIntoEmptyArmorSlots() {
        MenuView menu = MenuFixtures.ownInventory(MenuFixtures.builder()
                .put(SlotRole.MAIN, 9, DIAMOND_HELMET, 1)
                .put(SlotRole.MAIN, 10, SWORD, 1)
                .put(SlotRole.MAIN, 11, DIAMOND_CHEST, 1)
                .put(SlotRole.HOTBAR, 36, DIAMOND_BOOTS, 1));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(9, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(5, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(9, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(11, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(6, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(11, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(8, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP)),
                MenuPlanner.planWear(menu, CATALOG));
    }

    @Test
    void skipsWornPieceThatIsEqualOrBetter() {
        MenuView wornDiamond = MenuFixtures.ownInventory(
                MenuFixtures.builder().put(SlotRole.ARMOR, 5, DIAMOND_HELMET, 1).put(SlotRole.MAIN, 9, IRON_HELMET, 1));
        assertTrue(MenuPlanner.planWear(wornDiamond, CATALOG).isEmpty());

        MenuView wornIronBetterCandidate = MenuFixtures.ownInventory(MenuFixtures.builder()
                .put(SlotRole.ARMOR, 5, DIAMOND_HELMET, 1)
                .put(SlotRole.MAIN, 9, DIAMOND_HELMET, 1));
        assertTrue(MenuPlanner.planWear(wornIronBetterCandidate, CATALOG).isEmpty());
    }

    @Test
    void swapsStrictlyBetterPieceAndReturnsTheOldOne() {
        MenuView menu = MenuFixtures.ownInventory(MenuFixtures.builder()
                .put(SlotRole.ARMOR, 5, IRON_HELMET, 1)
                .put(SlotRole.MAIN, 12, DIAMOND_HELMET, 1));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(12, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(5, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(12, 0, MenuClick.PICKUP)),
                MenuPlanner.planWear(menu, CATALOG));
    }

    @Test
    void ignoresItemsForOtherSlots() {
        MenuView menu = MenuFixtures.ownInventory(MenuFixtures.builder().put(SlotRole.MAIN, 9, DIAMOND_BOOTS, 1));
        assertEquals(
                List.of(
                        new MenuPlanner.Step(9, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(8, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(9, 0, MenuClick.PICKUP)),
                MenuPlanner.planWear(menu, CATALOG));
    }

    @Test
    void emptyPlanWhenNothingWearable() {
        MenuView menu = MenuFixtures.ownInventory(
                MenuFixtures.builder().put(SlotRole.MAIN, 9, SWORD, 1).put(SlotRole.HOTBAR, 36, SWORD, 1));
        assertTrue(MenuPlanner.planWear(menu, CATALOG).isEmpty());
    }

    @Test
    void tieKeepsSnapshotOrder() {
        MenuView menu = MenuFixtures.ownInventory(
                MenuFixtures.builder().put(SlotRole.MAIN, 9, IRON_HELMET, 1).put(SlotRole.MAIN, 10, IRON_HELMET, 1));
        assertEquals(9, MenuPlanner.planWear(menu, CATALOG).get(0).slot());
    }

    @Test
    void rejectsMenusWithoutArmorSlots() {
        MenuView chest = MenuFixtures.chest(MenuFixtures.builder().put(SlotRole.CONTAINER, 0, DIAMOND_HELMET, 1));
        assertThrows(IllegalArgumentException.class, () -> MenuPlanner.planWear(chest, CATALOG));
    }
}
