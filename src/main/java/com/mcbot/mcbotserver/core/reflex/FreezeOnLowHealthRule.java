package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The Stage-0 minimal rule: freeze locomotion while badly hurt.
 *
 * <p>Contract: see boundaries.md decision ledger item 16 — one real
 * reflex rule ships in Phase 0 so the full sensor -> board -> rule ->
 * preempt -> resume chain runs against mocks before any entity exists.
 *
 * <p>Priority is deliberately flat (no distance gradient): bleeding out
 * is urgent regardless of geometry. The threshold is a constant until
 * the rule-table JSON loader lands (Stage 2); hard-coding it now keeps
 * the gate test honest about mechanism, not configuration.
 */
// contract: see boundaries.md decision 16 (minimal Phase-0 reflex)
public final class FreezeOnLowHealthRule
        implements ReflexRule, ReflexHysteresis {

    /** Health at or below which the rule fires (default table). */
    public static final float FREEZE_THRESHOLD = 10f;

    /**
     * Health strictly above which a firing rule releases. The 5-point
     * deadband between trigger and release absorbs health jitter
     * around the trigger without flipping the decision every tick -
     * 10, 12, 10, 16 is one fire then one release, not three
     * fire/release flips the raw values would suggest.
     */
    public static final float FREEZE_RELEASE = 15f;

    /**
     * Hold window: once the decision flips, it stays for at least
     * this many ticks before the next flip is allowed. Second
     * defense against a noisy sensor or a tick-rate signal that
     * crosses the threshold cleanly but rapidly.
     */
    public static final int FREEZE_HOLD_TICKS = 20;

    /** Flat firing priority; outside all task bands by convention. */
    public static final int FREEZE_PRIORITY = 100;

    private final float threshold;
    private final int priority;

    /** Creates the rule with the default table's threshold/priority. */
    public FreezeOnLowHealthRule() {
        this(FREEZE_THRESHOLD, FREEZE_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the rule-table
     * JSON loader produces.
     *
     * @param threshold health at or below which the rule fires;
     *                  in (0, 20]
     * @param priority  flat firing priority; positive
     */
    public FreezeOnLowHealthRule(float threshold, int priority) {
        if (threshold <= 0f || threshold > 20f) {
            throw new IllegalArgumentException(
                "threshold must be in (0, 20]");
        }
        if (priority <= 0) {
            throw new IllegalArgumentException(
                "priority must be positive");
        }
        this.threshold = threshold;
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.botHealth <= threshold ? priority : -1;
    }

    /**
     * The configured firing threshold.
     *
     * @return health value this rule fires at or below
     */
    public float threshold() {
        return threshold;
    }

    /**
     * The configured flat priority.
     *
     * @return priority reported when the rule fires
     */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "FREEZE_ON_LOW_HEALTH";
    }

    @Override
    public float triggerThreshold() {
        return threshold;
    }

    @Override
    public float releaseThreshold() {
        return FREEZE_RELEASE;
    }

    @Override
    public float signalValue(ThreatBlackboard board) {
        return board.botHealth;
    }

    @Override
    public int minHoldTicks() {
        return FREEZE_HOLD_TICKS;
    }
}
