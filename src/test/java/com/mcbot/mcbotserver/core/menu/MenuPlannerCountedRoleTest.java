package com.mcbot.mcbotserver.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Counted role deposit/take gate for the issue 0012 D1 verbs:
 * exact counts need split carries - lift a source stack, place
 * exactly one item per right-click, return the remainder - and
 * every under-supply or capacity failure happens before any click
 * exists.
 *
 * <p>Contract: see boundaries.md section A and issue 0012 D1
 * (counted deposit / take-role).
 */
class MenuPlannerCountedRoleTest {

    @Test
    void countedDepositLiftsPlacesExactlyAndReturnsRemainder() {
        MenuView menu = MenuFixtures.furnace(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:coal", 16));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.FUEL, "minecraft:coal", 5);
        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(38, 0, MenuClick.PICKUP));
        for (int i = 0; i < 5; i++) {
            expected.add(new MenuPlanner.Step(1, 1, MenuClick.PICKUP));
        }
        expected.add(new MenuPlanner.Step(38, 0, MenuClick.PICKUP));
        assertEquals(expected, plan);
    }

    @Test
    void countedDepositSpansSourcesAndFillsHeadroomThenEmpty() {
        // The INPUT slot holds 60/64 (headroom 4) and a furnace has
        // exactly one INPUT slot: a 6-count deposit exceeds capacity
        // and fails before any click; the 4 that fit plan as
        // lift + 4 places + return = 6 steps.
        MenuView menu = MenuFixtures.furnace(MenuFixtures.builder()
                .put(SlotRole.INPUT, 0, "minecraft:iron_ore", 60)
                .put(SlotRole.MAIN, 3, "minecraft:iron_ore", 10)
                .put(SlotRole.MAIN, 4, "minecraft:iron_ore", 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:iron_ore", 6));
        assertEquals(
                6,
                MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:iron_ore", 4)
                        .size());
    }

    @Test
    void countedDepositFailsClosedOnShortSupply() {
        MenuView menu = MenuFixtures.furnace(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:coal", 2));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> MenuPlanner.planDepositCounted(menu, SlotRole.FUEL, "minecraft:coal", 3));
        assertEquals("not enough: have 2, need 3", ex.getMessage());
    }

    @Test
    void takeRoleDrainQuickMovesEveryNonEmptySlot() {
        MenuView menu = MenuFixtures.furnace(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:iron_ingot", 7));
        assertEquals(
                List.of(new MenuPlanner.Step(2, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void takeRoleDrainOfEmptyRolePlansZeroSteps() {
        MenuView menu = MenuFixtures.furnace(MenuFixtures.builder());
        assertEquals(List.of(), MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void countedTakePlacesExactlyIntoPlayerRoom() {
        MenuView menu =
                MenuFixtures.furnace(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:iron_ingot", 10));
        List<MenuPlanner.Step> plan = MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 3);
        List<MenuPlanner.Step> expected = new ArrayList<>();
        expected.add(new MenuPlanner.Step(2, 0, MenuClick.PICKUP));
        for (int i = 0; i < 3; i++) {
            expected.add(new MenuPlanner.Step(3, 1, MenuClick.PICKUP));
        }
        expected.add(new MenuPlanner.Step(2, 0, MenuClick.PICKUP));
        assertEquals(expected, plan);
    }

    @Test
    void countedTakeFailsWithoutDestinationRoom() {
        // Player region entirely full of other items: no empty slot,
        // no same-item headroom.
        MenuFixtures.Builder b = MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:iron_ingot", 5);
        for (int i = 3; i <= 38; i++) {
            b.put(i <= 29 ? SlotRole.MAIN : SlotRole.HOTBAR, i, "minecraft:cobblestone", 64);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> MenuPlanner.planTakeRole(MenuFixtures.furnace(b), SlotRole.OUTPUT, 2));
    }

    @Test
    void anvilDepositInputFillsFirstEmptyInputSlot() {
        // Anvil has two INPUT slots (0=target, 1=material); deposit
        // INPUT fills the first empty one (slot 0) in v1. Counted
        // deposit uses right-click placement + return remainder.
        MenuView menu =
                MenuFixtures.anvil(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:iron_pickaxe", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:iron_pickaxe", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void anvilTakeOutputQuickMovesResult() {
        MenuView menu = MenuFixtures.anvil(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:iron_pickaxe", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(2, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void brewingDepositBottleFillsFirstEmptyBottleSlot() {
        MenuView menu =
                MenuFixtures.brewingStand(MenuFixtures.builder().put(SlotRole.HOTBAR, 40, "minecraft:water_bottle", 3));
        List<MenuPlanner.Step> plan =
                MenuPlanner.planDepositCounted(menu, SlotRole.BOTTLE, "minecraft:water_bottle", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void brewingDepositIngredientGoesToSlot3() {
        MenuView menu =
                MenuFixtures.brewingStand(MenuFixtures.builder().put(SlotRole.HOTBAR, 40, "minecraft:nether_wart", 16));
        List<MenuPlanner.Step> plan =
                MenuPlanner.planDepositCounted(menu, SlotRole.INGREDIENT, "minecraft:nether_wart", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(3, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void brewingDepositFuelGoesToSlot4() {
        MenuView menu = MenuFixtures.brewingStand(
                MenuFixtures.builder().put(SlotRole.HOTBAR, 40, "minecraft:blaze_powder", 16));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.FUEL, "minecraft:blaze_powder", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(4, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(40, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void brewingTakeBottleQuickMovesFinishedPotion() {
        MenuView menu =
                MenuFixtures.brewingStand(MenuFixtures.builder().put(SlotRole.BOTTLE, 0, "minecraft:strength", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(0, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.BOTTLE, 0));
    }
}
