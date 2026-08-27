package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * ACQUIRE_FOOD_WHEN_HUNGRY: the acquisition half of issue 0010 - the
 * exact complement of EAT_WHEN_HUNGRY. It fires only when the eat
 * rule cannot (hungry at/below the trigger AND no sensed food) and
 * hands off to the reflex-owned forage seat (HungryProcess, ledger
 * 34). Priority sits below EAT: with food in reach, eating outranks
 * foraging; without it, this rule escalates to the mission that
 * gets food - or, when the sensor finds nothing, the mission's
 * FOOD_STRATEGY_EXHAUSTED verdict reaches the harness as the 0010
 * section 5 escalation contract.
 *
 * <p>Contract: see boundaries.md decision C and issue 0010 sections
 * 4.1/5. Runs on the server tick thread only.
 */
public final class AcquireFoodWhenHungryRule implements ReflexRule {

    /** Default trigger, shared with the eat rule (issue 0010 D1). */
    public static final int TRIGGER_FOOD = 16;

    /** Default priority: below EAT (85), per the complement rule. */
    public static final int ACQUIRE_PRIORITY = 80;

    /** Creates the rule with the shipped default tuning. */
    public AcquireFoodWhenHungryRule() {
        this(TRIGGER_FOOD, ACQUIRE_PRIORITY);
    }

    private final int trigger;

    private final int priority;

    /**
     * Creates the rule with its tuning.
     *
     * @param trigger  food level at/below which the rule fires; 1..20
     * @param priority positive firing priority
     * @throws IllegalArgumentException when trigger is out of range or
     *                                  priority is not positive
     */
    public AcquireFoodWhenHungryRule(int trigger, int priority) {
        if (trigger < 1 || trigger > 20) {
            throw new IllegalArgumentException("trigger must be 1..20, was " + trigger);
        }
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive, was " + priority);
        }
        this.trigger = trigger;
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        boolean hungry = board.foodLevel <= trigger;
        return hungry && board.foodSlot < 0 ? priority : -1;
    }

    @Override
    public String name() {
        return "ACQUIRE_FOOD_WHEN_HUNGRY";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.FORAGE;
    }
}
