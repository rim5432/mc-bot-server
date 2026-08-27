package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.FoodCatalog;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the forage mission (issue 0010 v1): food in
 * inventory completes at once, a null sensed target is the one-shot
 * FOOD_STRATEGY_EXHAUSTED escalation verdict, the walk hands the
 * bush to the goal algebra, dig claims arm only in reach, and the
 * tick budget times out honestly.
 */
class HungryProcessTest {

    private static final CellPos BODY = new CellPos(0, 64, 0);

    private static final CellPos BUSH = new CellPos(8, 64, 0);

    private static final FoodCatalog CATALOG =
            id -> "minecraft:sweet_berries".equals(id) ? new FoodCatalog.FoodFacts(2, 0.1f) : null;

    @Test
    void foodAlreadyInInventoryCompletesImmediately() {
        MockWorldView world = worldWithFood();
        HungryProcess hungry = mission(() -> BUSH);

        hungry.onTick(world);

        assertFalse(hungry.isActive());
        assertTrue(hungry.missionSucceeded());
        assertNull(hungry.failureReasonOrNull());
    }

    @Test
    void noSensedBushIsStrategyExhaustion() {
        WorldView world = new MockWorldView();
        HungryProcess hungry = mission(() -> null);

        hungry.onTick(world);

        assertFalse(hungry.isActive());
        assertFalse(hungry.missionSucceeded());
        assertEquals(HungryProcess.REASON_EXHAUSTED, hungry.failureReasonOrNull());
        assertEquals("none", hungry.verdictAttrs().get("strategy"));
    }

    @Test
    void farBushWalksAndDoesNotDig() {
        WorldView world = new MockWorldView();
        HungryProcess hungry = mission(() -> BUSH);

        var directive = hungry.onTick(world);

        assertEquals(new GoalNear(BUSH, 1), directive.goal());
        assertFalse(hungry.isDigging());
    }

    @Test
    void nearBushDigsAndFoodCompletes() {
        MockWorldView world = worldWithFood();
        HungryProcess hungry = mission(() -> new CellPos(2, 64, 0));

        hungry.onTick(world);

        // Food check runs first: the mission completes without digging.
        assertFalse(hungry.isActive());
        assertTrue(hungry.missionSucceeded());

        HungryProcess walker = mission(() -> new CellPos(3, 64, 0));
        walker.onTick(new MockWorldView());
        assertTrue(walker.isDigging(), "3 blocks away is dig reach");
        assertEquals(new CellPos(3, 64, 0), walker.digTarget());
    }

    @Test
    void digTargetOutsideDigPhaseIsIllegal() {
        HungryProcess hungry = mission(() -> BUSH);
        assertThrows(IllegalStateException.class, hungry::digTarget);
    }

    @Test
    void tickBudgetTimesOut() {
        WorldView world = new MockWorldView();
        HungryProcess hungry = mission(() -> BUSH);

        for (int i = 0; i < 10; i++) {
            hungry.onTick(world);
        }

        assertFalse(hungry.isActive());
        assertEquals(HungryProcess.REASON_TIMEOUT, hungry.failureReasonOrNull());
    }

    @Test
    void huntsNearestPassiveMobWhenNoBush() {
        MockWorldView world = new MockWorldView();
        world.addEntity(new com.mcbot.mcbotserver.api.world.EntitySnapshot(
                "cow-1", "minecraft:cow", new CellPos(5, 64, 0), 10f, 10f));
        HungryProcess hungry = mission(() -> null);

        var directive = hungry.onTick(world);

        assertEquals(new GoalNear(new CellPos(5, 64, 0), 2), directive.goal());
        assertEquals(
                "cow-1",
                ((com.mcbot.mcbotserver.api.process.Attack)
                                directive.overrides().combat())
                        .targetId());
    }

    @Test
    void vanishedPreyWalksLastCellThenExhausts() {
        MockWorldView world = new MockWorldView();
        CellPos cowCell = new CellPos(5, 64, 0);
        world.addEntity(
                new com.mcbot.mcbotserver.api.world.EntitySnapshot("cow-1", "minecraft:cow", cowCell, 10f, 10f));
        HungryProcess hungry =
                new HungryProcess("t-h", 50, 500, () -> BODY, () -> null, CATALOG, java.util.Set.of("minecraft:cow"));
        hungry.onTick(world);
        world.removeEntity("cow-1");

        var directive = hungry.onTick(world);
        assertEquals(new GoalNear(cowCell, 0), directive.goal(), "collect walks the prey's last cell");
        for (int i = 0; i < 60 && hungry.isActive(); i++) {
            hungry.onTick(world);
        }

        assertFalse(hungry.isActive());
        assertEquals(HungryProcess.REASON_EXHAUSTED, hungry.failureReasonOrNull());
        assertEquals("hunt", hungry.verdictAttrs().get("strategy"));
    }

    @Test
    void collectedDropCompletesTheHunt() {
        MockWorldView world = new MockWorldView();
        CellPos cowCell = new CellPos(5, 64, 0);
        world.addEntity(
                new com.mcbot.mcbotserver.api.world.EntitySnapshot("cow-1", "minecraft:cow", cowCell, 10f, 10f));
        HungryProcess hungry =
                new HungryProcess("t-h", 50, 500, () -> BODY, () -> null, CATALOG, java.util.Set.of("minecraft:cow"));
        hungry.onTick(world);
        world.removeEntity("cow-1");
        hungry.onTick(world);

        world.setInventory(worldWithFood().getInventory());
        hungry.onTick(world);

        assertFalse(hungry.isActive());
        assertTrue(hungry.missionSucceeded());
    }

    private static HungryProcess mission(java.util.function.Supplier<CellPos> target) {
        return new HungryProcess("t-hungry-1", 50, 9, () -> BODY, target, CATALOG, java.util.Set.of("minecraft:cow"));
    }

    private static MockWorldView worldWithFood() {
        MockWorldView world = new MockWorldView();
        List<ItemView> main = new ArrayList<>(Collections.nCopies(36, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:sweet_berries", 3));
        world.setInventory(new com.mcbot.mcbotserver.api.inventory.InventoryView(
                main, 0, List.copyOf(Collections.nCopies(4, ItemView.EMPTY)), ItemView.EMPTY));
        return world;
    }
}
