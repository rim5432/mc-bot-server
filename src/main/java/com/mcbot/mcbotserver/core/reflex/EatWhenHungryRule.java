package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * EAT_WHEN_HUNGRY: the consumption half of issue 0010 - a pure
 * blackboard function that fires while food is at or below the
 * trigger AND the inventory carries food (the sensor's best-food
 * slot). The acquisition half (forage/hunt/fish, HungryProcess)
 * stays open in 0010; hungry with no food never fires - that is the
 * escalation contract, not a reflex.
 *
 * <p>Priority sits below ENGAGE (combat first, 0010 D6): a dead bot
 * does not eat. Trigger 16 per D1 - above the 18-regen loss zone,
 * low enough not to interrupt tasks constantly.
 *
 * <p>Contract: see boundaries.md decision C and issue 0010 sections
 * 4.1/D1/D6. Runs on the server tick thread only.
 */
public final class EatWhenHungryRule implements ReflexRule {

    /** Default trigger per issue 0010 D1. */
    public static final int TRIGGER_FOOD = 16;

    /** Default priority: below ENGAGE (90), per issue 0010 D6. */
    public static final int EAT_PRIORITY = 85;

    /** Creates the rule with the shipped default tuning (D1/D6). */
    public EatWhenHungryRule() {
        this(TRIGGER_FOOD, EAT_PRIORITY);
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
    public EatWhenHungryRule(int trigger, int priority) {
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
        return hungry && board.foodSlot >= 0 ? priority : -1;
    }

    @Override
    public String name() {
        return "EAT_WHEN_HUNGRY";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.EAT;
    }
}
