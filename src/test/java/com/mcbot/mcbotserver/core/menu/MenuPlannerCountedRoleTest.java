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
// TooManyMethods exempted: each verb scenario is one short
// arrange/act/assert method over shared fixtures; splitting the
// catalog would scatter one contract across files.
@SuppressWarnings("PMD.TooManyMethods")
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

    @Test
    void grindstoneDepositInputFillsFirstEmptySlot() {
        MenuView menu =
                MenuFixtures.grindstone(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:iron_pickaxe", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:iron_pickaxe", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void grindstoneTakeOutputQuickMovesResult() {
        MenuView menu =
                MenuFixtures.grindstone(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:iron_pickaxe", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(2, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void stonecutterDepositInputGoesToSlot0() {
        MenuView menu =
                MenuFixtures.stonecutter(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, "minecraft:stone", 16));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:stone", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void stonecutterTakeOutputQuickMovesResult() {
        MenuView menu =
                MenuFixtures.stonecutter(MenuFixtures.builder().put(SlotRole.OUTPUT, 1, "minecraft:stone_bricks", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(1, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void cartographyDepositInputFillsFirstEmptySlot() {
        MenuView menu =
                MenuFixtures.cartographyTable(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:map", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:map", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void cartographyTakeOutputQuickMovesResult() {
        MenuView menu =
                MenuFixtures.cartographyTable(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:map", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(2, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void smithingDepositInputFillsFirstEmptySlot() {
        MenuView menu = MenuFixtures.smithingTable(
                MenuFixtures.builder().put(SlotRole.HOTBAR, 39, "minecraft:netherite_ingot", 1));
        List<MenuPlanner.Step> plan =
                MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:netherite_ingot", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(39, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(39, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void smithingTakeOutputQuickMovesResult() {
        MenuView menu = MenuFixtures.smithingTable(
                MenuFixtures.builder().put(SlotRole.OUTPUT, 3, "minecraft:netherite_pickaxe", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void loomDepositInputFillsFirstEmptySlot() {
        MenuView menu = MenuFixtures.loom(MenuFixtures.builder().put(SlotRole.HOTBAR, 39, "minecraft:white_banner", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:white_banner", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(39, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(39, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void loomTakeOutputQuickMovesResult() {
        MenuView menu = MenuFixtures.loom(MenuFixtures.builder().put(SlotRole.OUTPUT, 3, "minecraft:white_banner", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void enchantingDepositInputFillsFirstEmptySlot() {
        MenuView menu = MenuFixtures.enchantingTable(
                MenuFixtures.builder().put(SlotRole.HOTBAR, 37, "minecraft:diamond_sword", 1));
        List<MenuPlanner.Step> plan =
                MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:diamond_sword", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void beaconDepositInputGoesToSlot0() {
        MenuView menu = MenuFixtures.beacon(MenuFixtures.builder().put(SlotRole.HOTBAR, 36, "minecraft:iron_ingot", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:iron_ingot", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(36, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void merchantDepositInputFillsFirstEmptySlot() {
        MenuView menu = MenuFixtures.merchant(MenuFixtures.builder().put(SlotRole.HOTBAR, 38, "minecraft:emerald", 16));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.INPUT, "minecraft:emerald", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(38, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void merchantTakeOutputQuickMovesResult() {
        MenuView menu = MenuFixtures.merchant(MenuFixtures.builder().put(SlotRole.OUTPUT, 2, "minecraft:bread", 1));
        assertEquals(
                List.of(new MenuPlanner.Step(2, 0, MenuClick.QUICK_MOVE)),
                MenuPlanner.planTakeRole(menu, SlotRole.OUTPUT, 0));
    }

    @Test
    void horseDepositArmorGoesToSlot0() {
        MenuView menu =
                MenuFixtures.horse(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, "minecraft:diamond_horse_armor", 1));
        List<MenuPlanner.Step> plan =
                MenuPlanner.planDepositCounted(menu, SlotRole.ARMOR, "minecraft:diamond_horse_armor", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(0, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                plan);
    }

    @Test
    void horseDepositContainerGoesToSlot1() {
        MenuView menu = MenuFixtures.horse(MenuFixtures.builder().put(SlotRole.HOTBAR, 37, "minecraft:saddle", 1));
        List<MenuPlanner.Step> plan = MenuPlanner.planDepositCounted(menu, SlotRole.CONTAINER, "minecraft:saddle", 1);
        assertEquals(
                List.of(
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP),
                        new MenuPlanner.Step(1, 1, MenuClick.PICKUP),
                        new MenuPlanner.Step(37, 0, MenuClick.PICKUP)),
                plan);
    }
}
