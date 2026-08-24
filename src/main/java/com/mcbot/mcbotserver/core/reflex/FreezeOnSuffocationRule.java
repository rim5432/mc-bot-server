package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The suffocation reflex: halt the body the instant its eye is
 * inside solid geometry, and hold still until the condition clears.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row) and issue
 * 0008 F4/D3. Suffocation deals 1.0F every tick with no interval
 * (LivingEntity.baseTick:387, decompiled 1.20.1) - instant class,
 * 20 ticks from full health. Unlike every other vital, the rescue
 * direction is UNKNOWN: a reflex guessing a movement direction can
 * push the body deeper into the glitch, so freezing is strictly
 * correct here - it is the one scenario where halting beats acting.
 *
 * <p>On this codebase, inWall is almost always a symptom of a
 * move-vocabulary or collision-predicate bug (the body occupies
 * geometry it should never have entered). The reflex is therefore
 * also the siren: FREEZE parks the mission and the TASK_PAUSED
 * event carries "SUFFOCATION" to the harness; the real fix lives
 * upstream in pathing. Priority 115 sits between SURFACE (110) and
 * LAVA (130) per the issue 0008 F9 triage table.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class FreezeOnSuffocationRule
        implements ReflexRule, ReflexHysteresis {

    /**
     * Flat firing priority; between SURFACE (110) and LAVA (130) -
     * drowning does not negotiate with geometry, and lava does not
     * negotiate with anything.
     */
    public static final int SUFFOCATION_PRIORITY = 115;

    /**
     * Hold window: once the eye leaves the wall, stay halted briefly
     * so a bobbing/glitching boundary does not flap the rule; the
     * mission resumes on the next quiet tick after.
     */
    public static final int SUFFOCATION_HOLD_TICKS = 10;

    /**
     * Hysteresis trigger on the inverted signal (0 = in wall,
     * 1 = safe). Signal 0 <= 0.5 fires; signal 1 stays silent.
     */
    private static final float TRIGGER = 0.5f;

    /** Release threshold on the inverted signal; 1 > 0.6 releases. */
    private static final float RELEASE = 0.6f;

    private final int priority;

    /** Creates the rule with the default table's priority. */
    public FreezeOnSuffocationRule() {
        this(SUFFOCATION_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public FreezeOnSuffocationRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException(
                "priority must be positive");
        }
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.inWall ? priority : -1;
    }

    /** The configured flat priority. */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "FREEZE_ON_SUFFOCATION";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.FREEZE;
    }

    @Override
    public float triggerThreshold() {
        return TRIGGER;
    }

    @Override
    public float releaseThreshold() {
        return RELEASE;
    }

    @Override
    public float signalValue(ThreatBlackboard board) {
        // Inverted: 0 = in wall (danger), 1 = safe - same shape as
        // AscendInLethalFluidRule; no new scheduler branch needed.
        return board.inWall ? 0f : 1f;
    }

    @Override
    public int minHoldTicks() {
        return SUFFOCATION_HOLD_TICKS;
    }
}
