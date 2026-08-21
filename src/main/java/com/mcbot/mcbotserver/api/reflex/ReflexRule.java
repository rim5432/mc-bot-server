package com.mcbot.mcbotserver.api.reflex;

/**
 * One row of the reflex rule table: a dynamic priority function over
 * the blackboard. Rules decide IF and HOW URGENT; the layer decides
 * WHO wins and WHAT the frozen body does.
 *
 * <p>Contract: see ADR-0003 section 2 (dynamic priority functions
 * replace static fields and meta-rules) and boundaries.md decision
 * ledger items 9-11. Reflex priorities live entirely outside the task
 * channel's bands — no numeric competition with processes, ever.
 *
 * <p>Why functions instead of constants: "creeper at two blocks" must
 * outrank "skeleton at fifteen", and only a function of the blackboard
 * can say that without a meta-rule layer on top.
 */
public interface ReflexRule {

    /**
     * Priority of firing this tick, computed from fresh sensor data.
     *
     * @param board this tick's sensed state; never null
     * @return positive priority when the rule fires (higher wins);
     *         negative or zero when it does not fire — never a
     *         constant for rules whose urgency varies with distance,
     *         health, or fuse state
     */
    int computePriority(ThreatBlackboard board);

    /**
     * Stable identity for diagnostics, interruption cause summaries and
     * crash reports.
     *
     * @return short rule name, e.g. {@code FREEZE_ON_LOW_HEALTH};
     *         never null or blank
     */
    String name();
}
