package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * DRINK_ON_LOW_HEALTH: the consumable half of the potion reflex - a
 * pure blackboard function that fires while health is at or below the
 * trigger AND the inventory carries a healing potion (the sensor's
 * best-potion slot). The acquisition half (brewing, chest looting)
 * stays open; low health with no potion never fires - that is the
 * escalation contract, not a reflex.
 *
 * <p>Priority sits below ENGAGE (90) and above EAT (85): a combat
 * mission owns its consumable pacing (drinking mid-fight is a fight
 * decision, not a reflex), but health is more urgent than hunger when
 * no fight is running. Trigger 12 sits above the FREEZE threshold (10)
 * so the drink reflex gets a 2-HP window to heal before the freeze
 * rule locks in (FREEZE has hysteresis release at 15, so once it fires
 * the drink reflex cannot re-enter until health recovers above 15).
 *
 * <p>One firing episode drinks exactly one potion via the rising-edge
 * gate in BindingActor.onUsePressEdge - the same mechanism as EAT.
 * An instant_health I potion heals 4 HP, so from trigger 12 it always
 * restores to >= 16 and the rule releases; regeneration potions heal
 * over time and the rule holds until regen crosses the trigger.
 *
 * <p>Contract: see boundaries.md decision C. Runs on the server tick
 * thread only.
 */
public final class DrinkOnLowHealthRule implements ReflexRule {

    /** Default trigger: 2 HP above the FREEZE threshold (10). */
    public static final float TRIGGER_HEALTH = 12f;

    /** Default priority: below ENGAGE (90), above EAT (85). */
    public static final int DRINK_PRIORITY = 88;

    /** Creates the rule with the shipped default tuning. */
    public DrinkOnLowHealthRule() {
        this(TRIGGER_HEALTH, DRINK_PRIORITY);
    }

    private final float trigger;

    private final int priority;

    /**
     * Creates the rule with its tuning.
     *
     * @param trigger  health at/below which the rule fires; in (0, 20]
     * @param priority positive firing priority
     * @throws IllegalArgumentException when trigger is out of range or
     *                                  priority is not positive
     */
    public DrinkOnLowHealthRule(float trigger, int priority) {
        if (trigger <= 0f || trigger > 20f) {
            throw new IllegalArgumentException("trigger must be in (0, 20], was " + trigger);
        }
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive, was " + priority);
        }
        this.trigger = trigger;
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        boolean hurt = board.botHealth <= trigger;
        return hurt && board.potionSlot >= 0 ? priority : -1;
    }

    /**
     * The configured firing threshold.
     *
     * @return health value this rule fires at or below
     */
    public float trigger() {
        return trigger;
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
        return "DRINK_ON_LOW_HEALTH";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.DRINK;
    }
}
