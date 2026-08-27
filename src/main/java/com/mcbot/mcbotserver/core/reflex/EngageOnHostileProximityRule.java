package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The idle-combat reflex: a hostile inside arm's reach means fight
 * NOW, whether or not any mission is running.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row) and
 * ReflexAction#ENGAGE - the rule only NOTICES the threat; the
 * controller's ENGAGE handling parks the current mission for one
 * tick and submits a reflex-owned DefendProcess, and the fight runs
 * through the normal mission stage from the next tick. The 2026-08-24
 * unattended run lost the body in a night cave exactly because
 * combat answered only to an explicit defend mission.
 *
 * <p>Threshold grounding: TRIGGER_DISTANCE 6 sits inside melee
 * closing range (zombies charge to contact) and well inside ranged
 * kiting standoff (~8-10 for skeletons) - a ranged mob hovering at
 * its preferred distance never trips this rule, because engaging it
 * would only mint ENGAGEMENT_REFUSED churn (decision 11). RELEASE
 * 14 sits above DefendProcess's LEASH_RADIUS (12) so a fleeing
 * target produces the fight's own verdict (LOST_TARGET) before the
 * rule quiets; the resubmit cooldown in the controller absorbs the
 * residual window.
 *
 * <p>Priority sits BELOW the survival holds (FREEZE 100): at three
 * health points the right reflex is to stop, not to start a fight.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class EngageOnHostileProximityRule implements ReflexRule, ReflexHysteresis {

    /** Threat distance at or below which the rule fires. */
    public static final double TRIGGER_DISTANCE = 6.0;

    /**
     * Distance at or above which a firing rule releases; above
     * DefendProcess's leash so the fight's own verdict lands first.
     */
    public static final double RELEASE_DISTANCE = 14.0;

    /** Hold window; absorbs a threat hovering at the release edge. */
    public static final int ENGAGE_HOLD_TICKS = 20;

    /** Flat firing priority; below FREEZE (100) by design. */
    public static final int ENGAGE_PRIORITY = 90;

    private final double trigger;
    private final double release;
    private final int priority;

    /** Creates the rule with the default table's thresholds. */
    public EngageOnHostileProximityRule() {
        this(TRIGGER_DISTANCE, RELEASE_DISTANCE, ENGAGE_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param trigger  threat distance at or below which the rule
     *                 fires; positive
     * @param release  threat distance at or above which the rule
     *                 releases; strictly above trigger
     * @param priority flat firing priority; positive
     */
    public EngageOnHostileProximityRule(double trigger, double release, int priority) {
        if (trigger <= 0f) {
            throw new IllegalArgumentException("trigger must be positive");
        }
        if (release <= trigger) {
            throw new IllegalArgumentException("release must be strictly above trigger");
        }
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
        this.trigger = trigger;
        this.release = release;
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.nearestThreat != null && board.nearestThreatDistance <= trigger ? priority : -1;
    }

    /** The configured firing distance. */
    public double trigger() {
        return trigger;
    }

    /** The configured release distance. */
    public double release() {
        return release;
    }

    /** The configured flat priority. */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "ENGAGE_ON_HOSTILE_PROXIMITY";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.ENGAGE;
    }

    @Override
    public float triggerThreshold() {
        return (float) trigger;
    }

    @Override
    public float releaseThreshold() {
        return (float) release;
    }

    @Override
    public float signalValue(ThreatBlackboard board) {
        // Infinity when no threat is sensed (MAX_VALUE distance):
        // still above release, so the state machine reads it as
        // "quiet" without a null special case.
        return (float) board.nearestThreatDistance;
    }

    @Override
    public int minHoldTicks() {
        return ENGAGE_HOLD_TICKS;
    }
}
