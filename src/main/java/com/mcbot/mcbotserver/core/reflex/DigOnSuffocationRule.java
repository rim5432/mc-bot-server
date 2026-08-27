package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * The suffocation reflex: dig the block at the eye out the instant the
 * eye is inside solid geometry, and hold the dig until the condition
 * clears.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row), issue 0008
 * F4/D3 for the original FREEZE ruling and issue 0009 for the upgrade:
 * with dig capability the rescue direction is no longer unknown - it
 * is the eye block itself, so freezing (the stopgap whose whole
 * argument was "a guessed direction can push deeper") is superseded
 * by the deterministic self-rescue. Suffocation still deals 1.0F
 * every tick with no interval (LivingEntity.baseTick:387, decompiled
 * 1.20.1): bare-hand gravel costs 18 dig ticks (DigPacing), so the
 * body escapes on a sliver of health - authentic vanilla math, not a
 * balance choice. When the eye sits in an unbreakable or tool-required
 * block the dig outlasts the 20-tick survival window and the body
 * dies holding the claim; the preemption's TASK_PAUSED is the siren
 * either way.
 *
 * <p>Target discipline: the rule stays a pure blackboard function -
 * it reads {@link ThreatBlackboard#suffocationBlock}, never computes
 * the cell itself. A board that stamps inWall without a position
 * yields a targetless DIG decision, which the controller degrades to
 * the freeze hold (missing data must not mint a dig-at-null).
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class DigOnSuffocationRule implements ReflexRule, ReflexHysteresis {

    /**
     * Flat firing priority; between SURFACE (110) and LAVA (130) -
     * drowning does not negotiate with geometry, and lava does not
     * negotiate with anything.
     */
    public static final int SUFFOCATION_PRIORITY = 115;

    /**
     * Hold window: once the eye leaves the wall, stay fired briefly
     * so a bobbing/glitching boundary does not flap the rule; the
     * mission resumes on the next quiet tick after. During the hold
     * the target is null (the eye is clear) and the controller's
     * degrade path holds the body still.
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
    public DigOnSuffocationRule() {
        this(SUFFOCATION_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public DigOnSuffocationRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.inWall ? priority : -1;
    }

    /**
     * The configured flat priority.
     *
     * @return the flat priority
     */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "DIG_ON_SUFFOCATION";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.DIG;
    }

    @Override
    public CellPos actionTarget(ThreatBlackboard board) {
        return board.inWall ? board.suffocationBlock : null;
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
