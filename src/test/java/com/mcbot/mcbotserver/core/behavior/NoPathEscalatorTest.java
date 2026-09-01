package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.goal.GoalRange;
import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * State-machine gates for the unreachable-goal escalation: witness
 * counting against the best-ever terminal distance, budget ladder,
 * and the reset semantics (goal change, complete route).
 *
 * <p>Contract: see NoPathEscalator - the field failure this guards
 * against is a budget-cut partial loop that burns the whole mission
 * timeout on an unreachable goal.
 */
class NoPathEscalatorTest {

    private static final GoalBlock GOAL = new GoalBlock(new CellPos(0, 64, 0));

    private static CellPos at(int x, int y, int z) {
        return new CellPos(x, y, z);
    }

    /** First adoption only sets the baseline; nothing is a witness. */
    /**
     * Range-band goals measure witnesses against the band edge, not
     * the center (decision 56): an inside-min kite whose partials
     * walk the terminal OUTWARD is improving every time - a
     * center-anchored metric would mint three witnesses and trip a
     * false NO_PATH on a perfectly converging kite.
     */
    @Test
    void rangeBandOutwardKiteNeverMintsWitnesses() {
        NoPathEscalator e = new NoPathEscalator(100);
        GoalRange band = new GoalRange(new CellPos(10, 64, 10), 8, 12);
        // Terminals march outward from Chebyshev 5 to the min edge 8
        // (west of center: x decreasing).
        for (int x = 5; x >= 2; x--) {
            e.onAdopted(band, at(x, 64, 10));
        }
        assertEquals(0, e.witnesses(), "outward kite terminals improve against the band edge");
        assertFalse(e.tripped());
        // Repeated terminals ON the band are at distance zero: no
        // improvement possible, but also the predicate is satisfied -
        // the caller retires the ledger via onRouteComplete before
        // witnesses could matter (pinned for the metric, not the
        // retirement, which PathingBehavior owns).
    }

    /**
     * The flip side: terminals that move toward the center (away
     * from the band) DO accumulate witnesses - the band metric must
     * not merely invert the old one.
     */
    @Test
    void rangeBandInwardTerminalsAccumulateWitnesses() {
        NoPathEscalator e = new NoPathEscalator(100);
        GoalRange band = new GoalRange(new CellPos(10, 64, 10), 8, 12);
        e.onAdopted(band, at(5, 64, 10));
        for (int i = 0; i < NoPathEscalator.WITNESS_LIMIT; i++) {
            e.onAdopted(band, at(6, 64, 10));
        }
        assertTrue(e.tripped(), "inward terminals open the band gap and must trip");
    }

    @Test
    void firstAdoptionSetsBaselineWithoutWitness() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(0, e.witnesses());
        assertFalse(e.tripped());
    }

    /**
     * The observed failure shape: partials whose terminal stops
     * approaching the goal. Baseline plus WITNESS_LIMIT repeats trips
     * the verdict.
     */
    @Test
    void repeatedNonImprovingTerminalsTripAfterLimit() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        for (int i = 0; i < NoPathEscalator.WITNESS_LIMIT; i++) {
            e.onAdopted(GOAL, at(20, 64, 0));
        }
        assertTrue(e.tripped());
    }

    /** One repeat short of the limit stays running. */
    @Test
    void limitMinusOneRepeatDoesNotTrip() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        for (int i = 0; i < NoPathEscalator.WITNESS_LIMIT - 1; i++) {
            e.onAdopted(GOAL, at(20, 64, 0));
        }
        assertFalse(e.tripped());
    }

    /**
     * A lateral terminal at the same distance is a witness: the
     * search answered again without getting any closer - equal
     * distance is not progress.
     */
    @Test
    void lateralTerminalAtEqualDistanceIsWitness() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(0, 64, 20));
        assertEquals(1, e.witnesses());
    }

    /**
     * Improvements below the epsilon do NOT reset the ledger - jitter
     * around the plateau is exactly the false-progress the margin
     * exists to absorb.
     */
    @Test
    void subEpsilonImprovementStillCountsAsWitness() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        // sqrt(397) ~ 19.92: 0.08 closer than the baseline - below
        // IMPROVEMENT_EPSILON.
        e.onAdopted(GOAL, at(19, 64, 6));
        assertEquals(1, e.witnesses());
    }

    /** A genuinely closer terminal (>= one axis step) resets. */
    @Test
    void axisStepImprovementResetsWitnesses() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(1, e.witnesses());
        e.onAdopted(GOAL, at(19, 64, 0));
        assertEquals(0, e.witnesses());
    }

    /** Detour tolerance: only a new global minimum counts. */
    @Test
    void worseningTerminalStillWitnessButNeverUpdatesBaseline() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(30, 64, 0));
        assertEquals(1, e.witnesses());
        // Back to 21: still worse than the 20 baseline - witness.
        e.onAdopted(GOAL, at(21, 64, 0));
        assertEquals(2, e.witnesses());
    }

    /** GoalNear anchors at the center cell, same as PathingBehavior. */
    @Test
    void goalNearWitnessMeasuresToCenter() {
        NoPathEscalator e = new NoPathEscalator(100);
        GoalNear near = new GoalNear(at(0, 64, 0), 2);
        e.onAdopted(near, at(10, 64, 0));
        e.onAdopted(near, at(0, 64, 10));
        assertEquals(1, e.witnesses());
    }

    /** Witnesses from one goal never leak into the next goal. */
    @Test
    void goalChangeResetsLedger() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        assertTrue(e.witnesses() >= 2);
        GoalBlock other = new GoalBlock(at(100, 64, 100));
        e.onAdopted(other, at(90, 64, 90));
        assertEquals(0, e.witnesses());
        assertFalse(e.tripped());
    }

    /** A tripped ledger clears on the goal change too. */
    @Test
    void goalChangeClearsTrippedVerdict() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        for (int i = 0; i < NoPathEscalator.WITNESS_LIMIT; i++) {
            e.onAdopted(GOAL, at(20, 64, 0));
        }
        assertTrue(e.tripped());
        e.onAdopted(new GoalBlock(at(50, 64, 50)), at(45, 64, 45));
        assertFalse(e.tripped());
    }

    /** A complete route retires the ledger - the mission converges. */
    @Test
    void routeCompleteResetsLedger() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onRouteComplete();
        assertEquals(0, e.witnesses());
        assertFalse(e.tripped());
        // Post-reset first adoption is a fresh baseline again.
        e.onAdopted(GOAL, at(25, 64, 0));
        assertEquals(0, e.witnesses());
    }

    /** The budget ladder escalates per witness and caps. */
    @Test
    void budgetEscalatesPerWitnessAndCaps() {
        NoPathEscalator e = new NoPathEscalator(100);
        assertEquals(100, e.nextNodeBudget());
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(400, e.nextNodeBudget());
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(1600, e.nextNodeBudget());
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(6400, e.nextNodeBudget());
        for (int i = 0; i < 10; i++) {
            e.onAdopted(GOAL, at(20, 64, 0));
        }
        assertEquals(NoPathEscalator.MAX_NODE_BUDGET, e.nextNodeBudget());
    }

    /** Improvement after witnesses keeps the budget ladder honest. */
    @Test
    void improvementLowersBudgetBackToBase() {
        NoPathEscalator e = new NoPathEscalator(100);
        e.onAdopted(GOAL, at(20, 64, 0));
        e.onAdopted(GOAL, at(20, 64, 0));
        assertEquals(400, e.nextNodeBudget());
        e.onAdopted(GOAL, at(10, 64, 0));
        assertEquals(100, e.nextNodeBudget());
    }

    /** Construction rejects a non-positive budget loudly. */
    @Test
    void nonPositiveBaseBudgetRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NoPathEscalator(0));
    }
}
