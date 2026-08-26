package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-thread home for A* searches, keeping the server tick thread
 * free of pathfinding cost (ADR-0004 D5 made real). Callers hand in a
 * {@link com.mcbot.mcbotserver.core.world.SnapshotWorldView} captured
 * on the main thread; the worker reads only that immutable view plus
 * pure engine state, so no live world access ever crosses threads.
 *
 * <p>Contract: see boundaries.md ledger entry 17b - the worker never
 * touches LIVE views, and every public method
 * is annotated with its legal thread. The thread is a daemon: a
 * forgotten shutdown cannot block JVM exit.
 *
 * <p>TODO adapter: shut the worker down on server stop once a mod
 * lifecycle hook exists (Ref: workplan Stage 2 async item).
 */
public final class PlanWorker {

    private final ExecutorService pool;

    /**
     * Creates a worker with one daemon thread named for diagnostics.
     * One worker per bot is plenty: replans are serialized by design,
     * and a second concurrent search would just fight for CPU.
     */
    public PlanWorker() {
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mcbot-plan-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Run one search off-thread. The view must be a snapshot or other
     * immutable implementation - passing a LIVE view here violates the
     * threading contract and will read torn world state.
     *
     * @param snapshot          immutable perception to read; never null
     * @param start             search origin; never null
     * @param goal              arrival predicate; never null
     * @param heuristic         cost-to-go estimator; never null
     * @param nodeBudget        safety-net expansion cap; positive
     * @param wallClockBudgetMs soft time cap in milliseconds; positive
     *                          required here - unbounded searches have
     *                          no place on a shared worker
     * @return the future result; completes on the worker thread
     */
    // runs on any thread; the returned future completes on mcbot-plan-worker
    public CompletableFuture<AStarPathFinder.PathResult> submit(
        WorldView snapshot, MoveGraph graph, CellPos start, Goal goal,
        Heuristic heuristic, int nodeBudget, long wallClockBudgetMs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        if (wallClockBudgetMs <= 0) {
            throw new IllegalArgumentException(
                "wallClockBudgetMs must be positive on the worker");
        }
        return CompletableFuture.supplyAsync(() ->
                new AStarPathFinder(graph, heuristic, nodeBudget)
                    .compute(snapshot, start, goal, heuristic,
                        wallClockBudgetMs),
            pool);
    }

    /**
     * Stop accepting work and interrupt anything running. Safe to call
     * twice; pending futures complete exceptionally.
     */
    // runs on any thread
    public void shutdown() {
        pool.shutdownNow();
    }
}
