package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Chest QUICK_MOVE gate for {@link MenuPlanner}:
 * {@code planWithdraw} shifts whole stacks until demand is met (and
 * may overshoot rather than split), refuses short containers;
 * {@code planDeposit} shifts every matching player stack and refuses
 * when nothing matches.
 *
 * <p>Contract: see boundaries.md section A and issue 0007 Phase 3
 * (chest withdraw/deposit).
 */
class MenuPlannerChestTransferTest {

    @Test
    void withdrawShiftsWholeStacksUntilDemandIsMet() {
        MenuView chest = MenuFixtures.chest(MenuFixtures.builder()
            .put(SlotRole.CONTAINER, 3, MenuFixtures.OAK, 5)
            .put(SlotRole.CONTAINER, 9, MenuFixtures.BIRCH, 10)
            .put(SlotRole.CONTAINER, 14, MenuFixtures.OAK, 10));

        // 5 from slot 3 already meets minCount 5 - one shift-click.
        assertEquals(List.of(
            new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, MenuFixtures.OAK, 5));

        // minCount 6 overshoots into the second oak stack.
        assertEquals(List.of(
            new MenuPlanner.Step(3, 0, MenuClick.QUICK_MOVE),
            new MenuPlanner.Step(14, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, MenuFixtures.OAK, 6));

        // Other kinds are never touched.
        assertEquals(List.of(
            new MenuPlanner.Step(9, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planWithdraw(chest, MenuFixtures.BIRCH, 1));
    }

    @Test
    void withdrawRejectsShortContainersAndBadArgs() {
        MenuView chest = MenuFixtures.chest(MenuFixtures.builder()
            .put(SlotRole.CONTAINER, 3, MenuFixtures.OAK, 5));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, MenuFixtures.OAK, 6));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> MenuPlanner.planWithdraw(chest, MenuFixtures.OAK, 0));
    }

    @Test
    void depositShiftsEveryPlayerStackOfTheKind() {
        MenuView chest = MenuFixtures.chest(MenuFixtures.builder()
            .put(SlotRole.MAIN, 30, "minecraft:stick", 4)
            .put(SlotRole.HOTBAR, 55, "minecraft:stick", 12)
            .put(SlotRole.HOTBAR, 56, MenuFixtures.OAK, 7));
        assertEquals(List.of(
            new MenuPlanner.Step(30, 0, MenuClick.QUICK_MOVE),
            new MenuPlanner.Step(55, 0, MenuClick.QUICK_MOVE)),
            MenuPlanner.planDeposit(chest, "minecraft:stick"));
    }

    @Test
    void depositRefusesWhenNothingMatches() {
        MenuView chest = MenuFixtures.chest(MenuFixtures.builder()
            .put(SlotRole.CONTAINER, 0, "minecraft:stick", 4));
        assertThrows(IllegalStateException.class,
            () -> MenuPlanner.planDeposit(chest, "minecraft:stick"));
    }
}
