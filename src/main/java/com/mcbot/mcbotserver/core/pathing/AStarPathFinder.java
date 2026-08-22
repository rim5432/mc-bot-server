package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.pathing.Heuristic;
import com.mcbot.mcbotserver.api.pathing.Movement;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * The A* engine over the move graph: g-scores, a binary-heap open set
 * and a node budget. Knows nothing about individual movement types,
 * threading, or MC - fully offline-testable per boundaries.md
 * decision 5.
 *
 * <p>Contract: see numen-notes.md section 17 for the semantics we copy:
 * returns the goal path when found, a best partial when the budget cut
 * the search with something usable, or failure when nothing usable
 * exists. {@code maxNodesPerSearch} is a safety net against pathological
 * searches, never a CPU throttle (thread pools own CPU - numen-notes
 * section 17's explicit warning).
 *
 * <p>Implementation note: runs on the caller's thread; the Stage 2
 * async replan worker will own the threading around this class.
 */
// contract: see numen-notes.md section 17 (anytime A*, budget = safety net)
public final class AStarPathFinder {

    /** Expansion budget before the search gives up (safety net). */
    public static final int DEFAULT_NODE_BUDGET = 20_000;

    /**
     * Search outcome: full success (goal reached), best partial (budget
     * exhausted with a usable prefix toward the goal), or total failure.
     */
    public record PathResult(List<CellPos> waypoints, int expandedNodes,
                             boolean reachedGoal) {

        /**
         * Creates a validated result.
         *
         * @param waypoints     start-first cell chain; empty only when
         *                      no progress was possible; never null
         * @param expandedNodes nodes popped from the open set
         * @param reachedGoal   whether the last waypoint satisfies the
         *                      goal predicate
         */
        public PathResult {
            waypoints = List.copyOf(waypoints);
        }

        /**
         * Failure result carrying the expansion count.
         *
         * @param expanded nodes expanded before giving up
         * @return an empty, unreached result
         */
        public static PathResult failed(int expanded) {
            return new PathResult(List.of(), expanded, false);
        }
    }

    private final MoveGraph graph;
    private final Heuristic heuristic;
    private final int nodeBudget;

    /**
     * Creates a finder with the default node budget.
     *
     * @param graph      edge supplier; never null
     * @param heuristic  cost-to-go estimator; must be admissible for
     *                   optimality (the engine does not verify); never
     *                   null
     */
    public AStarPathFinder(MoveGraph graph, Heuristic heuristic) {
        this(graph, heuristic, DEFAULT_NODE_BUDGET);
    }

    /**
     * Creates a finder with an explicit expansion budget.
     *
     * @param graph      edge supplier; never null
     * @param heuristic  cost-to-go estimator; never null
     * @param nodeBudget safety-net cap on expansions; positive
     */
    public AStarPathFinder(MoveGraph graph, Heuristic heuristic,
                           int nodeBudget) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.heuristic = Objects.requireNonNull(heuristic, "heuristic");
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException(
                "nodeBudget must be positive");
        }
        this.nodeBudget = nodeBudget;
    }

    /**
     * Search from start until the goal predicate passes on a popped
     * node or the budget runs out.
     *
     * @param world read-only perception backing movement viability;
     *              never null
     * @param start search origin; never null
     * @param goal  arrival predicate deciding termination; never null
     * @return the result; {@link PathResult#waypoints} is start-first
     *         and ends at a goal cell when {@code reachedGoal}; on
     *         budget exhaustion it is the best frontier-adjacent chain
     *         toward the goal, possibly empty when nothing useful was
     *         ever expanded; never null
     */
    public PathResult compute(WorldView world, CellPos start,
                              Goal goal) {
        return compute(world, start, goal, heuristic);
    }

    /**
     * Search with a call-specific heuristic - goals move between
     * calls, so callers supply an estimator aimed at the current goal
     * instead of rebuilding the finder.
     *
     * @param world     read-only perception; never null
     * @param start     search origin; never null
     * @param goal      arrival predicate; never null
     * @param heuristic cost-to-go for THIS search; must be admissible
     *                  for optimality; never null
     * @return see {@link #compute(WorldView, CellPos, Goal)}
     */
    public PathResult compute(WorldView world, CellPos start,
                              Goal goal, Heuristic heuristic) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(heuristic, "heuristic");

        var open = new PriorityQueue<Node>();
        Map<CellPos, Double> gScore = new HashMap<>();
        Map<CellPos, CellPos> cameFrom = new HashMap<>();
        var closed = new HashMap<CellPos, Boolean>();

        gScore.put(start, 0.0);
        open.add(new Node(start, heuristic.estimate(start)));

        int expanded = 0;
        CellPos bestPartial = null;
        double bestH = Double.MAX_VALUE;
        boolean budgetCut = false;

        while (!open.isEmpty() && expanded < nodeBudget) {
            Node current = open.poll();
            if (closed.putIfAbsent(current.pos(), Boolean.TRUE) != null) {
                continue;
            }
            expanded++;

            double h = heuristic.estimate(current.pos());
            if (h < bestH) {
                bestH = h;
                bestPartial = current.pos();
            }

            if (goal.isInGoal(current.pos())) {
                return new PathResult(
                    reconstruct(cameFrom, current.pos()), expanded, true);
            }

            double g = gScore.getOrDefault(current.pos(), Double.MAX_VALUE);
            for (Movement move : graph.movesFrom(current.pos())) {
                if (!move.isViable(world)) {
                    continue;
                }
                CellPos next = move.destination();
                if (closed.containsKey(next)) {
                    continue;
                }
                double tentative = g + move.cost(world);
                if (tentative < gScore.getOrDefault(next,
                        Double.MAX_VALUE)) {
                    gScore.put(next, tentative);
                    cameFrom.put(next, current.pos());
                    open.add(new Node(next,
                        tentative + heuristic.estimate(next)));
                }
            }
        }
        budgetCut = expanded >= nodeBudget;

        // Best-partial is a BUDGET-EXHAUSTION outcome only: when the
        // open set drains naturally there is definitively no path right
        // now, and pretending otherwise would hand the executor a
        // doomed prefix.
        if (budgetCut && bestPartial != null
            && !bestPartial.equals(start)) {
            return new PathResult(
                reconstruct(cameFrom, bestPartial), expanded, false);
        }
        return PathResult.failed(expanded);
    }

    private static List<CellPos> reconstruct(Map<CellPos, CellPos> links,
                                             CellPos end) {
        List<CellPos> out = new ArrayList<>();
        CellPos cursor = end;
        out.add(cursor);
        while (links.containsKey(cursor)) {
            cursor = links.get(cursor);
            out.add(cursor);
        }
        Collections.reverse(out);
        return out;
    }

    private record Node(CellPos pos, double f)
        implements Comparable<Node> {

        @Override
        public int compareTo(Node other) {
            return Double.compare(f, other.f);
        }
    }
}
