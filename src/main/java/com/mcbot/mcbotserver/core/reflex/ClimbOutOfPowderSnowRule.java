package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The powder-snow freeze reflex: hold jump while freeze progress
 * approaches the fully-frozen threshold, so vanilla climbable-block
 * physics walks the body out of the snow.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row, flat priority)
 * and boundary A - the rule never computes climbing: the ASCEND action
 * maps to a held jump, and Entity#isStateClimbable treats powder snow
 * as a ladder (decompiled 1.20.1 Entity.java:764), so a held jump in
 * powder snow applies the same upward impulse as on a ladder. Issue
 * 0008 F3 (powder-snow freeze row) deferred this rule behind
 * lava/fire/suffocation because the 140-tick freeze budget is the
 * least urgent vital.
 *
 * <p>Numeric grounding (decompiled 1.20.1): freeze accumulates
 * +1/tick in powder snow, capped at 140 (Entity.getTicksRequiredToFreeze);
 * fully frozen deals 1.0 per 40 ticks (LivingEntity.baseTick "freezing"
 * phase). Thaw is -2/tick out of snow - twice the freeze rate - so
 * once the body steps clear, progress decays rapidly. TRIGGER=100
 * leaves 40 ticks (2 seconds) before damage starts. No hysteresis
 * deadband: freezeTicks changes at most 2/tick, so boundary flapping
 * is sub-perceptible; EscapeLavaRule and ExtinguishFireRule use the
 * same flat-priority shape.
 *
 * <p>Priority sits below FREEZE_ON_LOW_HEALTH (100): a bot that is
 * both freezing and low-health should park and let the harness
 * triage - 47 seconds of freeze budget gives the harness ample
 * response time, and climbing out at 2 HP may not change the outcome.
 * Above ENGAGE (90) so a freezing bot climbs instead of continuing a
 * fight.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class ClimbOutOfPowderSnowRule implements ReflexRule {

    /** Freeze progress at or above which the rule fires (default table). */
    public static final int TRIGGER_FREEZE = 100;

    /**
     * Flat firing priority; below FREEZE_PRIORITY (100) by design -
     * see class doc. Above ENGAGE (90) so a freezing body climbs
     * instead of continuing a fight.
     */
    public static final int CLIMB_PRIORITY = 95;

    private final int trigger;
    private final int priority;

    /** Creates the rule with the default table's threshold. */
    public ClimbOutOfPowderSnowRule() {
        this(TRIGGER_FREEZE, CLIMB_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param trigger  freeze progress at or above which the rule
     *                 fires; in [1, 139]
     * @param priority flat firing priority; positive
     */
    public ClimbOutOfPowderSnowRule(int trigger, int priority) {
        if (trigger < 1 || trigger >= 140) {
            throw new IllegalArgumentException(
                "trigger must be in [1, 139]");
        }
        if (priority <= 0) {
            throw new IllegalArgumentException(
                "priority must be positive");
        }
        this.trigger = trigger;
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.freezeTicks >= trigger ? priority : -1;
    }

    /** The configured firing threshold. */
    public int trigger() {
        return trigger;
    }

    /** The configured flat priority. */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "CLIMB_OUT_OF_POWDER_SNOW";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.ASCEND;
    }
}
