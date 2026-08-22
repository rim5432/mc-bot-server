package com.mcbot.mcbotserver.api.reflex;

/**
 * Optional companion to {@link ReflexRule}: when a rule implements this
 * interface the layer applies a double-threshold state machine + a
 * hold window so the rule's fire/silent transitions stay stable under
 * signal oscillation.
 *
 * <p>Contract: see ADR-0003 section 2 (reflex bypass) and the
 * anti-oscillation semantics in the boundary D protocol. The two
 * thresholds exist so a signal that jitters around one of them does
 * not flip the rule's decision every tick — the rule is released only
 * when the signal CROSSES the release threshold upward (or the
 * trigger threshold downward). The hold window exists so even a clean
 * threshold-crossing waits {@link #minHoldTicks} ticks before the
 * decision actually changes — protects against tick-rate-driven
 * thrash and against a single noisy sensor sample.
 *
 * <p>Why a separate interface: keeps {@link ReflexRule} pure (no
 * numeric signal, no hold config) and lets non-hysteretic rules ship
 * with zero overhead. Rules opt in by implementing this alongside
 * {@link ReflexRule}.
 */
public interface ReflexHysteresis {

    /**
     * Signal value at or below which the rule wants to fire when it
     * is currently silent. Below this number the rule's raw priority
     * is positive.
     *
     * @return the trigger threshold in the rule's natural signal units
     */
    float triggerThreshold();

    /**
     * Signal value strictly above which the rule wants to stop when
     * it is currently firing. Strictly greater than
     * {@link #triggerThreshold} — the gap between the two is the
     * deadband that absorbs jitter.
     *
     * @return the release threshold in the rule's natural signal units
     */
    float releaseThreshold();

    /**
     * Current signal value from the blackboard. Read on the tick
     * thread only; the board is per-tick scratchpad and never outlives
     * a single reflex pass.
     *
     * @param board the layer's tick-scoped blackboard; never null
     * @return the rule's current signal value
     */
    float signalValue(ThreatBlackboard board);

    /**
     * Minimum ticks the decision must hold after a state change before
     * another change is allowed. Default 0 (no hold) keeps the
     * interface usable for rules whose signal is intrinsically stable;
     * a rule with a jittery signal should override to a positive value.
     *
     * @return non-negative number of ticks
     */
    default int minHoldTicks() {
        return 0;
    }
}
