package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Stage-2 wall-clock gate: a clock-bounded search is an EXHAUSTION,
 * never a lie - it returns the best partial toward the goal instead of
 * reporting failure, and it must cut before the node budget would.
 *
 * <p>Contract: see numen-notes.md section 17 (wall clock every 64
 * nodes) and AStarPathFinder exhaustion semantics.
 */
class AStarWallClockGateTest {

    /**
     * Steady fake clock on a wide open floor: the injected nanotime
     * advances a fixed step per read, so the 64-node clock check
     * crosses the deadline after a deterministic number of expansions
     * - the cut arrives long before any sane node budget, and the
     * partial still points at the goal. (The pre-seam version armed a
     * real 1 ms budget and raced fast machines.)
     */
    @Test
    void clockCutYieldsBestPartialBeforeBudget() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 120; x++) {
            for (int z = 0; z <= 120; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        // Advance 300 us per nanotime read: arming reads ~0.3 ms,
        // checks land past the 5 ms deadline around expansion ~1000.
        long[] nanos = {0};
        var finder = new AStarPathFinder(
                BasicMoves::from,
                Heuristic.euclideanTo(new CellPos(110, 64, 110)),
                1_000_000,
                () -> nanos[0] += 300_000L);

        AStarPathFinder.PathResult result = finder.compute(
                world,
                new CellPos(0, 64, 0),
                new GoalBlock(new CellPos(110, 64, 110)),
                Heuristic.euclideanTo(new CellPos(110, 64, 110)),
                5L);

        assertFalse(result.reachedGoal(), "the injected clock must cut before the far corner");
        assertTrue(result.expandedNodes() < 1_000_000, "the clock, not the node budget, must do the cutting");
        assertFalse(
                result.waypoints().isEmpty(),
                "an exhausted search with frontier progress owes a " + "usable partial, not an empty failure");
    }

    /**
     * The offline default stays unlimited: same search, no clock -
     * reaches the goal outright.
     */
    @Test
    void defaultSearchIgnoresClockAndReachesGoal() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 20; x++) {
            world.putBlock(new BlockSnapshot(new CellPos(x, 63, 0), "minecraft:smooth_stone"));
        }
        var finder = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(new CellPos(15, 64, 0)));

        AStarPathFinder.PathResult result =
                finder.compute(world, new CellPos(0, 64, 0), new GoalBlock(new CellPos(15, 64, 0)));

        assertTrue(result.reachedGoal(), "unbounded offline search must stay exact");
    }

    /**
     * Verdict weight: a cut that lands before any frontier progress -
     * the worker was starved right after arming, the field shape
     * behind the pullscrafts flake - collapses its near-zero partial
     * to empty, and that empty must carry NO unreachability claim:
     * drainedOpenSet stays false so the lifecycle re-asks instead of
     * declaring NO_PATH.
     */
    @Test
    void earlyClockCutCarriesNoVerdictWeight() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 120; x++) {
            for (int z = 0; z <= 120; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        // 6 ms per nanotime read: the arming read lands at 6 ms, the
        // deadline at 11 ms, the first checkpoint (expansion 0,
        // 0 % 64 == 0) reads 12 ms and cuts immediately — zero
        // frontier progress, the exact starved-worker shape behind the
        // pullscrafts flake (a thread starved past its budget before
        // the first checkpoint returns a zero-progress cut).
        long[] nanos = {0};
        var finder = new AStarPathFinder(
                BasicMoves::from,
                Heuristic.euclideanTo(new CellPos(110, 64, 110)),
                1_000_000,
                () -> nanos[0] += 6_000_000L);

        AStarPathFinder.PathResult result = finder.compute(
                world,
                new CellPos(0, 64, 0),
                new GoalBlock(new CellPos(110, 64, 110)),
                Heuristic.euclideanTo(new CellPos(110, 64, 110)),
                5L);

        assertFalse(result.reachedGoal(), "the starved-start clock must cut before the far corner");
        assertTrue(result.expandedNodes() < 1_000_000, "the clock, not the node budget, must do the cutting");
        assertTrue(result.waypoints().isEmpty(), "a near-zero-progress cut collapses its partial");
        assertFalse(result.drainedOpenSet(), "a cut says 'stopped early', never 'no route exists'");
    }

    /**
     * The complementary half of the verdict weight: an open set that
     * drains NATURALLY (a one-floor-pocket start with the goal far
     * away) still fails with full unreachability weight - the honest
     * instant NO_PATH the fast-fail memory pinned.
     */
    @Test
    void drainedOpenSetKeepsVerdictWeight() {
        MockWorldView world = new MockWorldView();
        world.putBlock(new BlockSnapshot(new CellPos(0, 63, 0), "minecraft:smooth_stone"));
        var finder = new AStarPathFinder(BasicMoves::from, Heuristic.euclideanTo(new CellPos(30, 64, 0)));

        AStarPathFinder.PathResult result =
                finder.compute(world, new CellPos(0, 64, 0), new GoalBlock(new CellPos(30, 64, 0)));

        assertFalse(result.reachedGoal(), "the pocket cannot reach the far goal");
        assertTrue(result.waypoints().isEmpty(), "a drained open set has no partial to offer");
        assertTrue(result.drainedOpenSet(), "a drained open set IS the no-route verdict");
    }
}
