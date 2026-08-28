package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.Goals;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.util.Objects;

/**
 * Unreachable-goal escalation: turns a repeating cycle of budget-cut
 * partial plans that stop approaching the goal into an honest
 * mission-level NO_PATH verdict.
 *
 * <p>Why this exists: a drained A* open set already fast-fails (empty
 * waypoints -> cursor cleared -> NO_PATH the same tick). The gap is
 * the budget-cut partial: a non-empty prefix with confidence >=
 * {@link com.mcbot.mcbotserver.core.pathing.AStarPathFinder#MIN_PARTIAL_CONFIDENCE}
 * is adopted, walked, exhausted, replanned - forever, because every
 * individual search "succeeded" locally while the mission never
 * converges. The observed field failure: an unreachable goal burned
 * the full mission timeout through that loop.
 *
 * <p>Mechanism: every adopted partial is a <em>witness</em>. A witness
 * whose terminal waypoint does not improve the best-ever
 * terminal-to-goal distance (beyond {@link #IMPROVEMENT_EPSILON})
 * increments the witness count; an improving witness resets it. Each
 * witness also escalates the node budget for the next search
 * ({@link #nextNodeBudget}), so the verdict only lands after the
 * search had a progressively fair chance: budget-cut plateau at the
 * maximum budget is evidence of unreachability, not of a stingy
 * search.
 *
 * <p>Improvement is measured against the best-ever distance, not the
 * previous one: a legitimate detour that temporarily moves the
 * terminal away from the goal cannot mint false witnesses, because
 * only a new global minimum counts as progress.
 *
 * <p>Contract: see boundaries.md decision 8 vocabulary (the NO_PATH
 * verdict stays PathingBehavior's to declare; this class only
 * accumulates evidence and never emits reports).
 *
 * <p>Implementation note: single-threaded, tick-thread-local like all
 * follower state.
 */
final class NoPathEscalator {

    /**
     * Terminal-to-goal distance improvement that counts as progress,
     * in blocks. Strictly below 1.0 so any genuinely closer terminal
     * cell (minimum axis step) resets the witnesses; same-distance
     * lateral moves do not.
     */
    static final double IMPROVEMENT_EPSILON = 0.5;

    /** Non-improving witnesses required before the NO_PATH verdict. */
    static final int WITNESS_LIMIT = 3;

    /** Budget multiplication per accumulated witness. */
    static final int ESCALATION_FACTOR = 4;

    /** Ceiling for the escalated budget; the wall clock caps time. */
    static final int MAX_NODE_BUDGET = 320_000;

    private final int baseBudget;
    private Goal goal;
    private double bestDistance = Double.MAX_VALUE;
    private int witnesses;
    private boolean tripped;

    /**
     * Creates an escalator over one follower.
     *
     * @param baseBudget the node budget for a fresh goal's first
     *                   search; positive
     */
    NoPathEscalator(int baseBudget) {
        if (baseBudget <= 0) {
            throw new IllegalArgumentException("baseBudget must be positive");
        }
        this.baseBudget = baseBudget;
    }

    /**
     * Record one adopted partial plan as a witness. A goal change
     * resets the ledger first - witnesses are only comparable within
     * one goal.
     *
     * @param goal     the goal the partial was searched for; never null
     * @param terminal the adopted plan's terminal waypoint; never null
     */
    void onAdopted(Goal goal, CellPos terminal) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(terminal, "terminal");
        if (!goal.equals(this.goal)) {
            reset();
            this.goal = goal;
        }
        double distance = Goals.cellOf(goal).distanceTo(terminal);
        if (distance < bestDistance - IMPROVEMENT_EPSILON) {
            bestDistance = distance;
            witnesses = 0;
            return;
        }
        witnesses++;
        if (witnesses >= WITNESS_LIMIT) {
            tripped = true;
        }
    }

    /**
     * A complete route (goal predicate satisfied by the plan) retires
     * the ledger: the mission is converging and partials before it
     * were honest progress.
     */
    void onRouteComplete() {
        reset();
    }

    /**
     * Whether the evidence threshold was reached and the follower
     * should declare NO_PATH.
     *
     * @return true after {@link #WITNESS_LIMIT} non-improving
     *         witnesses
     */
    boolean tripped() {
        return tripped;
    }

    /**
     * Node budget for the next search request: the base budget,
     * escalated once per accumulated witness, capped.
     *
     * @return budget in expansions, always positive
     */
    int nextNodeBudget() {
        long escalated = baseBudget;
        for (int i = 0; i < witnesses; i++) {
            escalated *= ESCALATION_FACTOR;
            if (escalated >= MAX_NODE_BUDGET) {
                return MAX_NODE_BUDGET;
            }
        }
        return (int) Math.min(escalated, MAX_NODE_BUDGET);
    }

    /** Forget all evidence; a new goal starts from a clean ledger. */
    void reset() {
        goal = null;
        bestDistance = Double.MAX_VALUE;
        witnesses = 0;
        tripped = false;
    }

    /**
     * Current witness count, for the keepalive snapshot.
     *
     * @return non-negative count of consecutive non-improving partials
     */
    int witnesses() {
        return witnesses;
    }
}
