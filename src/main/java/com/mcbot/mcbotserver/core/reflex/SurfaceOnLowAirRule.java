package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The drowning reflex: hold jump while the air supply runs out, so
 * vanilla fluid physics swims the body to the surface.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row, dynamic
 * priority function) and boundary A - the rule never computes
 * swimming: the ASCEND action maps to a held jump, and
 * LivingEntity#aiStep routes a held jump in water deeper than the
 * fluid-jump threshold to jumpInFluid (+0.04/tick upward impulse;
 * the engine owns all motion). Issue 0004 F6(2) reserved this row
 * after a body drowned in the spawn lake during the 2026-08-24
 * unattended run.
 *
 * <p>Numeric grounding (decompiled 1.20.1): air drains 1/tick from
 * 300, so TRIGGER_AIR = 80 leaves ~4 seconds of air; terminal
 * swim-up velocity is ~0.16 blocks/tick (impulse 0.04 against
 * 0.8/tick vertical drag), i.e. ~13 blocks of depth still surface
 * in time. RELEASE_AIR = 200 keeps the hold alive through the
 * surface-bobbing regen (+4/tick) instead of flapping at the
 * surface film.
 *
 * <p>Priority outranks FREEZE_ON_LOW_HEALTH (100): a bleeding body
 * that is also drowning must surface first - freezing under water
 * converts one lethal condition into two.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class SurfaceOnLowAirRule implements ReflexRule, ReflexHysteresis {

    /** Air at or below which the rule fires (default table). */
    public static final int TRIGGER_AIR = 80;

    /**
     * Air at or above which a firing rule releases. The wide
     * deadband (80 -> 200) keeps the ascend held while the body
     * bobs at the surface regaining air; a narrow band would
     * release at the first surface breath and re-trigger on the
     * next submersion tick.
     */
    public static final int RELEASE_AIR = 200;

    /** Hold window; second defense against surface-film jitter. */
    public static final int SURFACE_HOLD_TICKS = 20;

    /**
     * Flat firing priority; above FREEZE_PRIORITY (100) by design -
     * see class doc. Outside all task bands by convention.
     */
    public static final int SURFACE_PRIORITY = 110;

    private final int trigger;
    private final int release;
    private final int priority;

    /** Creates the rule with the default table's thresholds. */
    public SurfaceOnLowAirRule() {
        this(TRIGGER_AIR, RELEASE_AIR, SURFACE_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param trigger  air at or below which the rule fires;
     *                 in [1, 299]
     * @param release  air at or above which the rule releases;
     *                 strictly above trigger, at most
     *                 {@link ThreatBlackboard#MAX_AIR_SUPPLY}
     * @param priority flat firing priority; positive
     */
    public SurfaceOnLowAirRule(int trigger, int release, int priority) {
        if (trigger < 1 || trigger >= ThreatBlackboard.MAX_AIR_SUPPLY) {
            throw new IllegalArgumentException("trigger must be in [1, 299]");
        }
        if (release <= trigger || release > ThreatBlackboard.MAX_AIR_SUPPLY) {
            throw new IllegalArgumentException("release must be in (trigger, 300]");
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
        return board.airSupply <= trigger ? priority : -1;
    }

    /** The configured firing threshold. */
    public int trigger() {
        return trigger;
    }

    /** The configured release threshold. */
    public int release() {
        return release;
    }

    /** The configured flat priority. */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "SURFACE_ON_LOW_AIR";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.ASCEND;
    }

    @Override
    public float triggerThreshold() {
        return trigger;
    }

    @Override
    public float releaseThreshold() {
        return release;
    }

    @Override
    public float signalValue(ThreatBlackboard board) {
        return board.airSupply;
    }

    @Override
    public int minHoldTicks() {
        return SURFACE_HOLD_TICKS;
    }
}
