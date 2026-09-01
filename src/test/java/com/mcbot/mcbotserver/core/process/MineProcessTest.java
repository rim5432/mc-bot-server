package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline gates for {@link MineProcess} (issue 0014, post-landing
 * review round): termination counts blocks broken (never inventory
 * items - the block id and its drop id routinely differ), the search
 * centers on the injected bot position (never world origin), and
 * per-target failures skip instead of failing the mission.
 */
class MineProcessTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);

    /** Drives one full break cycle: MOVING -> SUCCESS -> air -> COLLECT. */
    private static void breakOne(MineProcess mine, MockWorldView world, CellPos target) {
        mine.onExecutionReport(ExecutionReport.success());
        world.putAir(target);
        mine.onTick(world);
    }

    private static InventoryView stoneStock(int count) {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:stone", count, ""));
        return new InventoryView(
                main, 0, Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY), ItemView.EMPTY);
    }

    @Test
    void happyPathCompletesByBreaks() {
        CellPos a = new CellPos(2, 64, 0);
        CellPos b = new CellPos(3, 64, 0);
        MockWorldView world = new MockWorldView()
                .putBlock(new BlockSnapshot(a, "minecraft:stone"))
                .putBlock(new BlockSnapshot(b, "minecraft:stone"));
        MineProcess mine = new MineProcess("t1", "minecraft:stone", 2, 50, 1000, () -> BOT);

        mine.onTick(world);
        assertEquals(MineProcess.Phase.MOVING, mine.phase());
        // Nearest candidate first: chebyshev 2 beats 3.
        assertEquals(a, mine.digTarget());

        breakOne(mine, world, a);
        assertEquals(MineProcess.Phase.COLLECTING, mine.phase());
        assertEquals(1, mine.brokenCount());
        MineProcess.BlockBreak recorded = mine.pollBreak();
        assertEquals(a, recorded.pos());
        assertEquals("minecraft:stone", recorded.blockId());
        assertNull(mine.pollBreak());

        for (int i = 0; i < 20; i++) {
            mine.onTick(world);
        }
        assertEquals(MineProcess.Phase.MOVING, mine.phase());
        assertEquals(b, mine.digTarget());

        breakOne(mine, world, b);
        assertEquals(2, mine.brokenCount());
        for (int i = 0; i < 20; i++) {
            mine.onTick(world);
        }
        assertEquals(MineProcess.Phase.DONE, mine.phase());
        assertTrue(mine.missionSucceeded());
        assertFalse(mine.isActive());
        assertNull(mine.failureReasonOrNull());
    }

    @Test
    void terminationIgnoresPreOwnedStock() {
        // Regression gate for the review finding: counting inventory
        // items would let a pre-owned 64-stack satisfy "mine 1" after
        // zero breaks. Breaks are the only counter.
        CellPos a = new CellPos(1, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        world.setInventory(stoneStock(64));
        MineProcess mine = new MineProcess("t2", "minecraft:stone", 1, 50, 1000, () -> BOT);

        mine.onTick(world);
        assertTrue(mine.isActive());
        assertEquals(MineProcess.Phase.MOVING, mine.phase());

        breakOne(mine, world, a);
        for (int i = 0; i < 20; i++) {
            mine.onTick(world);
        }
        assertTrue(mine.missionSucceeded());
        assertEquals(1, mine.brokenCount());
    }

    @Test
    void searchCentersOnInjectedBotPosition() {
        // Regression gate: the v1 shipped with a hardcoded origin
        // fallback; the supplier must be the only search center.
        CellPos target = new CellPos(102, 64, 102);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(target, "minecraft:stone"));
        MineProcess mine = new MineProcess("t3", "minecraft:stone", 1, 50, 1000, () -> new CellPos(100, 64, 100));
        mine.onTick(world);
        assertEquals(MineProcess.Phase.MOVING, mine.phase());
        assertEquals(target, mine.digTarget());

        // And the inverse: stone only near the origin must NOT be
        // found by a bot standing elsewhere.
        MockWorldView originWorld =
                new MockWorldView().putBlock(new BlockSnapshot(new CellPos(0, 64, 0), "minecraft:stone"));
        MineProcess far = new MineProcess("t4", "minecraft:stone", 1, 50, 1000, () -> new CellPos(1000, 64, 1000));
        far.onTick(originWorld);
        assertFalse(far.isActive());
        assertTrue(far.failureReasonOrNull().startsWith("no minecraft:stone within 16"));
    }

    @Test
    void noCandidatesFailsHonestly() {
        MockWorldView world = new MockWorldView();
        MineProcess mine = new MineProcess("t5", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        assertFalse(mine.isActive());
        assertFalse(mine.missionSucceeded());
        assertEquals("no minecraft:stone within 16 (0 skipped)", mine.failureReasonOrNull());
    }

    @Test
    void stuckReportSkipsTargetNotMission() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t6", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        mine.onExecutionReport(ExecutionReport.stuck("no displacement"));
        assertEquals(MineProcess.Phase.SEARCH, mine.phase());
        // The only candidate is now skipped: the mission fails
        // honestly instead of retrying the same unreachable target.
        mine.onTick(world);
        assertFalse(mine.isActive());
        assertTrue(mine.failureReasonOrNull().contains("(1 skipped)"));
    }

    @Test
    void moveBudgetExpiresAndSkipsTarget() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t7", "minecraft:stone", 1, 50, 1000, () -> BOT);
        // Never report SUCCESS: the per-target move budget (200) must
        // skip the target well before the mission budget (1000).
        for (int i = 0; i < 210 && mine.isActive(); i++) {
            mine.onTick(world);
        }
        assertFalse(mine.isActive());
        assertTrue(mine.failureReasonOrNull().contains("(1 skipped)"));
    }

    @Test
    void digBudgetSkipsUnbreakableTarget() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t8", "minecraft:stone", 1, 50, 2000, () -> BOT);
        mine.onTick(world);
        mine.onExecutionReport(ExecutionReport.success());
        assertEquals(MineProcess.Phase.DIGGING, mine.phase());
        // The block never turns to air (unbreakable stand-in); the
        // per-target dig budget (400) must skip it.
        for (int i = 0; i < 410 && mine.isActive(); i++) {
            mine.onTick(world);
        }
        assertFalse(mine.isActive());
        assertTrue(mine.failureReasonOrNull().contains("(1 skipped)"));
    }

    @Test
    void unloadedMidDigSkipsTarget() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t9", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        mine.onExecutionReport(ExecutionReport.success());
        world.markUnloaded(a);
        mine.onTick(world);
        assertFalse(mine.isActive());
        assertTrue(mine.failureReasonOrNull().contains("(1 skipped)"));
    }

    @Test
    void missionBudgetFailsWithTimeout() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t10", "minecraft:stone", 1, 50, 50, () -> BOT);
        for (int i = 0; i < 60 && mine.isActive(); i++) {
            mine.onTick(world);
        }
        assertFalse(mine.isActive());
        assertEquals("TIMEOUT", mine.failureReasonOrNull());
    }

    @Test
    void isDiggingOnlyInDiggingPhase() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t11", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        assertFalse(mine.isDigging());
        mine.onExecutionReport(ExecutionReport.success());
        assertTrue(mine.isDigging());
        breakOne(mine, world, a);
        assertFalse(mine.isDigging());
        for (int i = 0; i < 20; i++) {
            mine.onTick(world);
        }
        assertFalse(mine.isDigging());
    }

    @Test
    void abortTerminatesSilently() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = new MockWorldView().putBlock(new BlockSnapshot(a, "minecraft:stone"));
        MineProcess mine = new MineProcess("t12", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        mine.abort();
        assertFalse(mine.isActive());
        assertFalse(mine.missionSucceeded());
        assertNull(mine.failureReasonOrNull());
    }

    @Test
    void malformedConstructionRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> new MineProcess("", "minecraft:stone", 1, 50, 100, () -> BOT));
        assertThrows(IllegalArgumentException.class, () -> new MineProcess("t", " ", 1, 50, 100, () -> BOT));
        assertThrows(
                IllegalArgumentException.class, () -> new MineProcess("t", "minecraft:stone", 0, 50, 100, () -> BOT));
        assertThrows(
                IllegalArgumentException.class, () -> new MineProcess("t", "minecraft:stone", 1, 50, 0, () -> BOT));
        assertThrows(IllegalArgumentException.class, () -> new MineProcess("t", "minecraft:stone", 1, 50, 100, null));
    }
}
