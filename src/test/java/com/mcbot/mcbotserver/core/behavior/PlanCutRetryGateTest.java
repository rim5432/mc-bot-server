package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.pathing.MoveGraph;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the cut-vs-drained verdict weight (the pullscrafts
 * flake): an empty search result is only an unreachability VERDICT
 * when the open set drained naturally - a budget cut or a failed
 * future must re-ask, because a starved worker thread is not evidence
 * about the world. Drives the real worker through request/adoptIfReady
 * with deterministic cut producers (a 64-node budget collapses its
 * near-zero partial; a throwing graph fails the future).
 */
class PlanCutRetryGateTest {

    private static final CellPos START = new CellPos(0, 64, 0);

    private static final GoalBlock FAR_GOAL = new GoalBlock(new CellPos(110, 64, 110));

    /** A wide open floor: far goal, every cell standable. */
    private static MockWorldView openFloor() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 120; x++) {
            for (int z = 0; z <= 120; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    @Test
    void nodeBudgetCutWithNoProgressIsAReAsk() {
        PlanLifecycle lifecycle = new PlanLifecycle(BasicMoves::from, new PlanWorker());

        // Node budget 8: best-first A* has beelined ~11 blocks toward
        // the 155-block diagonal goal when the budget stops it -
        // confidence ~0.07 collapses the partial (under
        // MIN_PARTIAL_CONFIDENCE) and the empty result must NOT read
        // as unreachability.
        PlanLifecycle.Adoption requested = lifecycle.request(openFloor(), START, FAR_GOAL, 8);
        assertTrue(requested.outcome() == PlanLifecycle.Outcome.NOT_READY, "async requests never answer in-tick");

        PlanLifecycle.Adoption settled = settle(lifecycle, FAR_GOAL);

        assertEquals(PlanLifecycle.Outcome.CUT, settled.outcome(), "a stingy-budget cut is a re-ask, never NO_PATH");
    }

    @Test
    void failedFutureIsAReAsk() {
        // A graph that throws the moment the finder relaxes the first
        // node's neighbors: the worker future completes exceptionally,
        // which says nothing about the world either.
        MoveGraph boom = cell -> {
            throw new IllegalStateException("simulated worker crash");
        };
        PlanLifecycle lifecycle = new PlanLifecycle(boom, new PlanWorker());

        lifecycle.request(openFloor(), START, FAR_GOAL, 10_000);

        assertEquals(
                PlanLifecycle.Outcome.CUT, settle(lifecycle, FAR_GOAL).outcome(), "an exceptional future is a re-ask");
    }

    /** The honest fast-fail still holds: a drained open set IS the verdict. */
    @Test
    void drainedOpenSetIsANoRouteVerdict() {
        MockWorldView pocket = new MockWorldView();
        pocket.putBlock(new BlockSnapshot(new CellPos(0, 63, 0), "minecraft:smooth_stone"));
        GoalBlock goal = new GoalBlock(new CellPos(30, 64, 0));
        PlanLifecycle lifecycle = new PlanLifecycle(BasicMoves::from, new PlanWorker());

        lifecycle.request(pocket, START, goal, 10_000);

        assertEquals(
                PlanLifecycle.Outcome.NO_ROUTE,
                settle(lifecycle, goal).outcome(),
                "the pocket world genuinely has no route");
    }

    /**
     * Polls adoptIfReady until the worker's search has landed, under
     * a generous wall-clock cap: thread startup, snapshot capture and
     * JIT cold starts routinely outrun any fixed spin count.
     */
    private static PlanLifecycle.Adoption settle(PlanLifecycle lifecycle, GoalBlock goal) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            PlanLifecycle.Adoption adoption = lifecycle.adoptIfReady(goal, START);
            if (adoption.outcome() != PlanLifecycle.Outcome.NOT_READY) {
                return adoption;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the worker search never finished");
    }
}
