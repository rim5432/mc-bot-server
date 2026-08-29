package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Contract gate for MineProcess's scan geometry, budgets and
 * returned-directive discipline - the mutants the first PIT pass
 * survived (2026-08-29): the chebyshev sort, the scan-axis offsets,
 * the per-target budget boundaries, the FAILED-report skip, resume
 * honesty, and the non-null hold directive on every terminal tick.
 *
 * <p>The returned Directive is contract surface (boundary B: a seated
 * mission answers with a directive every tick, the failure tick
 * included), so each scenario asserts the RETURNED value, not just
 * the process fields.
 */
class MineProcessContractGateTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);

    private static MockWorldView worldWith(CellPos at, String blockId) {
        return new MockWorldView().putBlock(new BlockSnapshot(at, blockId));
    }

    private static void drive(MineProcess mine, WorldView world, int ticks) {
        for (int i = 0; i < ticks && mine.isActive(); i++) {
            mine.onTick(world);
        }
    }

    @Test
    void scanFindsOffAxisTargets() {
        // Target offset only in Y and only in Z: an axis-addition
        // mutant (center.y()+dy -> center.y()-dy) or a boundary mutant
        // on the loop bounds misses it and the mission fails instead
        // of moving.
        CellPos target = new CellPos(0, 65, 3);
        MockWorldView world = worldWith(target, "minecraft:stone");
        MineProcess mine = new MineProcess("s1", "minecraft:stone", 1, 50, 1000, () -> BOT);

        Directive d = mine.onTick(world);
        assertEquals(MineProcess.Phase.MOVING, mine.phase());
        assertEquals(target, mine.digTarget());
        assertNotNull(d, "the moving tick must answer with a directive");
        assertEquals(new GoalNear(target, 2), d.goal());
    }

    @Test
    void nearestCandidateWinsOverScanOrder() {
        // Scan order (dy, then dx, then dz) visits the farther
        // candidate FIRST: (0,64,2) is scanned before (1,64,0). The
        // chebyshev sort must still pick (1,64,0) - a removed sort, a
        // zeroed chebyshev, or a min/max mutant flips the pick.
        MockWorldView world = worldWith(new CellPos(0, 64, 2), "minecraft:stone")
                .putBlock(new BlockSnapshot(new CellPos(1, 64, 0), "minecraft:stone"));
        MineProcess mine = new MineProcess("s2", "minecraft:stone", 1, 50, 1000, () -> BOT);

        mine.onTick(world);
        assertEquals(new CellPos(1, 64, 0), mine.digTarget(), "chebyshev-nearest must win over scan order");
    }

    @Test
    void failedTickStillAnswersWithAHoldDirective() {
        MockWorldView world = new MockWorldView();
        MineProcess mine = new MineProcess("s3", "minecraft:stone", 1, 50, 1000, () -> BOT);

        Directive d = mine.onTick(world);
        assertFalse(mine.isActive());
        assertNotNull(d, "the failure tick must still answer with a hold directive");
        assertEquals(new GoalNear(BOT, 2), d.goal(), "the hold anchors at the construction position");
        // And the terminal hold persists on later ticks.
        assertNotNull(mine.onTick(world), "terminal hold keeps answering");
    }

    @Test
    void moveBudgetBoundarySkipsOnTheExactTick() {
        CellPos near = new CellPos(1, 64, 0);
        CellPos far = new CellPos(2, 64, 0);
        MockWorldView world = worldWith(near, "minecraft:stone").putBlock(new BlockSnapshot(far, "minecraft:stone"));
        MineProcess mine = new MineProcess("s4", "minecraft:stone", 2, 50, 1000, () -> BOT);
        mine.onTick(world); // IDLE -> SEARCH -> MOVING (to `near`)

        // Budget is 200; the phase counter reads totalTicks - 1, so
        // total tick 200 stays MOVING and total tick 201 skips to
        // SEARCH - which seats the second candidate.
        for (int i = 0; i < 199; i++) {
            mine.onTick(world);
        }
        assertEquals(MineProcess.Phase.MOVING, mine.phase(), "total tick 200 must still be MOVING");
        assertEquals(near, mine.digTarget());
        mine.onTick(world);
        // Total tick 201: the budget skip and the reseat of the next
        // candidate happen inside this tick (SEARCH is transient), so
        // the observable end-of-tick phase is already MOVING at `far`.
        assertEquals(MineProcess.Phase.MOVING, mine.phase(), "total tick 201 must have skipped and reseated");
        assertEquals(far, mine.digTarget());
    }

    @Test
    void digBudgetBoundarySkipsOnTheExactTick() {
        CellPos near = new CellPos(1, 64, 0);
        CellPos far = new CellPos(2, 64, 0);
        MockWorldView world = worldWith(near, "minecraft:stone").putBlock(new BlockSnapshot(far, "minecraft:stone"));
        MineProcess mine = new MineProcess("s5", "minecraft:stone", 2, 50, 2000, () -> BOT);
        mine.onTick(world);
        mine.onExecutionReport(ExecutionReport.success());
        assertEquals(MineProcess.Phase.DIGGING, mine.phase());

        // Dig budget is 400; the phase counter reads totalTicks - 1,
        // so total tick 400 still digs and total tick 401 skips to
        // SEARCH - which seats the second candidate.
        for (int i = 0; i < 399; i++) {
            mine.onTick(world);
        }
        assertEquals(MineProcess.Phase.DIGGING, mine.phase(), "total tick 400 must still be DIGGING");
        mine.onTick(world);
        assertEquals(MineProcess.Phase.MOVING, mine.phase(), "total tick 401 must have skipped and reseated");
        assertEquals(far, mine.digTarget());
    }

    @Test
    void failedChaseReportSkipsTheTarget() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = worldWith(a, "minecraft:stone");
        MineProcess mine = new MineProcess("s6", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        mine.onExecutionReport(ExecutionReport.failed("NO_PATH"));

        assertEquals(MineProcess.Phase.SEARCH, mine.phase(), "a FAILED chase must skip, not retry");
        mine.onTick(world);
        assertFalse(mine.isActive());
        assertTrue(mine.failureReasonOrNull().contains("(1 skipped)"));
    }

    @Test
    void resumeIsHonestAboutLiveness() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = worldWith(a, "minecraft:stone");
        MineProcess mine = new MineProcess("s7", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        assertTrue(mine.resume(null), "a live mission resumes");
        mine.abort();
        assertFalse(mine.resume(null), "a terminated mission does not resume");
    }

    @Test
    void accessorRoundTrip() {
        MineProcess mine = new MineProcess("s8", "minecraft:stone", 3, 50, 1000, () -> BOT);
        assertEquals("minecraft:stone", mine.blockType());
        assertEquals(3, mine.targetCount());
    }

    @Test
    void doneTerminalTickAnswersHoldAtInitialPosition() {
        CellPos a = new CellPos(2, 64, 0);
        MockWorldView world = worldWith(a, "minecraft:stone");
        MineProcess mine = new MineProcess("s9", "minecraft:stone", 1, 50, 1000, () -> BOT);
        mine.onTick(world);
        breakOne(mine, world, a);
        drive(mine, world, 20);
        assertEquals(MineProcess.Phase.DONE, mine.phase());
        Directive hold = mine.onTick(world);
        assertNotNull(hold, "the DONE hold answers a directive");
        // currentTarget survives into the terminal hold: the anchor is
        // the last worked cell, not the construction position.
        assertEquals(new GoalNear(a, 2), hold.goal(), "the DONE hold anchors at the last target");
    }

    private static void breakOne(MineProcess mine, MockWorldView world, CellPos target) {
        mine.onExecutionReport(ExecutionReport.success());
        world.putAir(target);
        mine.onTick(world);
    }
}
