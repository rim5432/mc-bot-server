package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.Goals;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.pathing.AStarPathFinder;
import com.mcbot.mcbotserver.core.pathing.MoveGraph;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import com.mcbot.mcbotserver.core.world.SnapshotWorldView;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * The plan search lifecycle: launch a search, wait for it, decide
 * whether a finished result is still fresh, and hand the plan (or
 * the reason there is none) back to the follower.
 *
 * <p>Contract: see boundaries.md decision 17b - with a
 * {@link PlanWorker} injected, searches execute off-thread on an
 * immutable {@link SnapshotWorldView} captured here on the tick
 * thread; all bookkeeping fields are tick-thread-local, the future
 * is the sole cross-thread object. A {@code null} worker selects the
 * synchronous-planning mode used by offline tests.
 *
 * <p>Freshness is decided against the CURRENT directive and position:
 * a plan for an abandoned goal or from a cell we have left is stale
 * and discarded whole - adopting it would steer toward where we no
 * longer want to go.
 *
 * <p>Implementation note: single-threaded except for the future read.
 */
final class PlanLifecycle {

    /**
     * Freshness tolerance in cells (issue 0001 fix 5 / Ruling (c).
     * The bot's current cell must be within Chebyshev distance
     * {@code FRESHNESS_CELLS} of the {@code pendingStart} the
     * search was launched from for the result to be adopted.
     * {@code FRESHNESS_CELLS == 1} is the v1 vocabulary's
     * tightest tolerance: every BasicMoves move is max-Chebyshev
     * 1, so a 1-cell drift is the maximum the bot can accumulate
     * in any single tick.
     */
    public static final int FRESHNESS_CELLS = 1;

    /**
     * What a finished (or absent) search means for the follower.
     */
    enum Outcome {

        /** No finished result yet; keep steering by the old plan. */
        NOT_READY,

        /** Result answered a question we no longer ask; discarded. */
        STALE,

        /**
         * The search stopped early (budget cut or cancellation) with
         * nothing usable - NOT a verdict about the world. The follower
         * re-asks rather than concluding unreachability: a starved
         * worker thread must not read as a drained search space.
         */
        CUT,

        /** Fresh result, definitive no route. */
        NO_ROUTE,

        /** Fresh result with a usable waypoint chain. */
        ADOPTED
    }

    /**
     * @param outcome what happened to the pending search, if any
     * @param plan    the adopted waypoint chain when
     *                {@code outcome == ADOPTED}; null otherwise
     */
    record Adoption(Outcome outcome, @Nullable List<CellPos> plan) {}

    private final MoveGraph graph;

    @Nullable
    private final PlanWorker worker;
    // Async plan bookkeeping; all fields touched on the tick thread
    // only - the future itself is the sole cross-thread object.
    @Nullable
    private CompletableFuture<AStarPathFinder.PathResult> pendingPlan;

    @Nullable
    private Goal pendingGoal;

    @Nullable
    private CellPos pendingStart;

    /**
     * Creates a lifecycle over one move graph. A {@code null} worker
     * is the deliberate synchronous-planning mode used by offline
     * tests; real bots pass the shared worker.
     *
     * @param graph  edge supplier for planning; never null
     * @param worker search executor; may be null (sync mode)
     */
    PlanLifecycle(MoveGraph graph, @Nullable PlanWorker worker) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.worker = worker;
    }

    /**
     * Whether a search has been launched and not finished yet.
     *
     * @return true iff a result is still pending
     */
    boolean inFlight() {
        return pendingPlan != null && !pendingPlan.isDone();
    }

    /**
     * Drop any pending search without consuming it - used when the
     * mission succeeds and a late result would answer a question no
     * one is asking.
     */
    void discardPending() {
        pendingPlan = null;
        pendingGoal = null;
        pendingStart = null;
    }

    /**
     * Launch a search for the current goal. Sync mode computes right
     * here and answers immediately (ADOPTED or NO_ROUTE) - matching
     * the historical behaviour where planSync applied within the
     * requesting tick. Async mode submits to the worker over a
     * snapshot and answers NOT_READY; the finished search is picked
     * up by {@link #adoptIfReady} on a later tick. One search in
     * flight at a time - the cooldown gate spaces requests, this
     * guard absorbs same-tick re-triggers.
     *
     * <p>The caller owns the node budget: the follower escalates it
     * per non-improving partial witness so an unreachable goal gets
     * progressively deeper searches before its NO_PATH verdict.
     *
     * @param world      the live view to snapshot from; never null
     * @param start      the cell to search from; never null
     * @param goal       the goal to search toward; never null
     * @param nodeBudget safety-net cap on expansions; positive
     * @return the verdict for THIS tick (never the pending search)
     */
    Adoption request(WorldView world, CellPos start, Goal goal, int nodeBudget) {
        CellPos anchor = Goals.cellOf(goal);
        if (worker == null) {
            var plan = computeSync(world, start, goal, nodeBudget);
            return plan.isPresent() ? new Adoption(Outcome.ADOPTED, plan.get()) : new Adoption(Outcome.NO_ROUTE, null);
        }
        // One search in flight at a time; the cooldown gate above
        // spaces requests, this guard absorbs same-tick re-triggers.
        if (inFlight()) {
            return new Adoption(Outcome.NOT_READY, null);
        }
        pendingGoal = goal;
        pendingStart = start;
        var snapshot = SnapshotWorldView.capture(world, start, anchor);
        pendingPlan = worker.submit(
                snapshot, graph, start, goal, Goals.heuristicOf(goal), nodeBudget, PathingBehavior.PLAN_WALL_CLOCK_MS);
        return new Adoption(Outcome.NOT_READY, null);
    }

    /**
     * Synchronous variant of {@link #request}: run the finder now and
     * return the waypoint chain, or an empty optional when no route
     * exists. The caller applies the chain to its cursor.
     *
     * @param world      the live view to search over; never null
     * @param start      the cell to search from; never null
     * @param goal       the goal to search toward; never null
     * @param nodeBudget safety-net cap on expansions; positive
     * @return the waypoint chain, or empty for no route
     */
    java.util.Optional<List<CellPos>> computeSync(WorldView world, CellPos start, Goal goal, int nodeBudget) {
        var heuristic = Goals.heuristicOf(goal);
        var finder = new AStarPathFinder(graph, heuristic, nodeBudget);
        var result = finder.compute(world, start, goal, heuristic);
        if (!result.reachedGoal() && result.waypoints().isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(result.waypoints());
    }

    /**
     * Consume a finished off-thread search, deciding freshness
     * against the current goal and position (issue 0001 fix 5 /
     * Ruling (c): Chebyshev-1 tolerance instead of strict cell
     * equality - see {@link #chebyshevDistance}).
     *
     * @param currentGoal the directive's goal this tick; never null
     * @param cell        the body's current cell; never null
     * @return the adoption verdict for this tick
     */
    Adoption adoptIfReady(Goal currentGoal, CellPos cell) {
        if (pendingPlan == null || !pendingPlan.isDone()) {
            return new Adoption(Outcome.NOT_READY, null);
        }
        AStarPathFinder.PathResult result;
        try {
            result = pendingPlan.join();
        } catch (RuntimeException cancelled) {
            result = AStarPathFinder.PathResult.cut(0);
        }
        pendingPlan = null;

        // Freshness check (issue 0001 fix 5 / Ruling (c): cell-equality
        // is too strict. A* takes time; the bot moves during the
        // search. Walking speed 0.215 b/tick x a 4-5 tick search
        // already crosses 1 cell; strict cell-equality would discard
        // every result, eating the fuse accumulator through churn.
        // Chebyshev distance 1 (max(|dx|, |dy|, |dz|) <= 1) accepts
        // any cell the bot can reach in 1 step in the move graph
        // (Walk, JumpUp, Drop, Diagonal - all max-Chebyshev 1).
        // Note: 1 cell is the v1 vocabulary's tightest tolerance;
        // future moves that span more cells per step must revisit
        // this constant in lockstep.
        CellPos start = pendingStart;
        boolean fresh =
                start != null && currentGoal.equals(pendingGoal) && chebyshevDistance(cell, start) <= FRESHNESS_CELLS;
        pendingGoal = null;
        pendingStart = null;
        if (!fresh) {
            return new Adoption(Outcome.STALE, null);
        }
        if (!result.reachedGoal() && result.waypoints().isEmpty()) {
            // Verdict weight lives in the result: only a drained open
            // set concludes "no route"; a cut or cancelled search with
            // nothing usable is a re-ask, never unreachability.
            return new Adoption(result.drainedOpenSet() ? Outcome.NO_ROUTE : Outcome.CUT, null);
        }
        return new Adoption(Outcome.ADOPTED, result.waypoints());
    }

    /**
     * Chebyshev distance between two cells (max of the per-axis
     * deltas). Used by the freshness check in
     * {@link #adoptIfReady}: a search result is fresh if the bot's
     * current cell is within
     * {@link PathingBehavior#FRESHNESS_CELLS} Chebyshev units of the
     * cell the search was launched from. Chebyshev (not Euclidean)
     * is the right metric here because the move graph's maximum step
     * in any single tick is max-Chebyshev 1 (Walk, JumpUp, Drop,
     * Diagonal) - a 1-cell Euclidean step would reject valid moves.
     *
     * @param a one cell; must not be null
     * @param b another cell; must not be null
     * @return non-negative Chebyshev distance in cells
     */
    static int chebyshevDistance(CellPos a, CellPos b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, Math.max(dy, dz));
    }
}
