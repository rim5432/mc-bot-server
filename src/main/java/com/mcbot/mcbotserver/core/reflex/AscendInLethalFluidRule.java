package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The lava reflex: hold jump while the body stands in lethal fluid,
 * so vanilla fluid physics swims the body to the surface.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row, dynamic
 * priority function) and boundary A - the rule never computes
 * swimming: the ASCEND action maps to a held jump, and
 * BotBodyEntity.customServerAiStep routes a held jump in lava to
 * jumpInFluid(LAVA_TYPE) (+0.3/tick upward impulse; the engine owns
 * all motion). Issue 0008 F2: the live pipeline was blind to lava -
 * board.inLethalFluid had zero writers since ADR-0003, and only the
 * crashed-state MinimalReflex read body.isInLava() directly. This
 * rule closes that gap for the normal pipeline.
 *
 * <p>Numeric grounding (decompiled 1.20.1): lava deals 4.0F every
 * tick with no interval (Entity.lavaHurt) plus sets 15 seconds of
 * fire; a full-health body dies in ~5 ticks. Priority 130 outranks
 * every other reflex because 4 HP/tick does not negotiate - the
 * triage table in issue 0008 F9 records this.
 *
 * <p>This is the reflex half only: ascent buys seconds by floating
 * the body to the lava surface, but the exit is horizontal (reach
 * shore) and is mission-shaped. The escape mission (ENGAGE-pattern
 * GotoProcess to nearest non-liquid cell) is deferred per issue 0008
 * D2; ASCEND alone is the survival-gate minimum.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class AscendInLethalFluidRule
        implements ReflexRule, ReflexHysteresis {

    /** Flat firing priority; above SURFACE (110) by design - lava is
     * 4 HP/tick vs drowning's 2 HP/tick after air exhaustion. */
    public static final int LAVA_PRIORITY = 130;

    /**
     * Hold window: once the body leaves lava, keep ascending for
     * this many ticks to clear the edge before releasing. A body
     * that bobs at the lava-air boundary can re-enter on the next
     * tick; the hold absorbs that.
     */
    public static final int LAVA_HOLD_TICKS = 10;

    /**
     * Hysteresis trigger on the inverted signal (0 = in lava,
     * 1 = safe). Signal 0 <= 0.5 fires; signal 1 stays silent.
     */
    private static final float TRIGGER = 0.5f;

    /** Release threshold on the inverted signal; signal 1 > 0.6
     * releases after the hold window. */
    private static final float RELEASE = 0.6f;

    private final int priority;

    /** Creates the rule with the default table's priority. */
    public AscendInLethalFluidRule() {
        this(LAVA_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public AscendInLethalFluidRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException(
                "priority must be positive");
        }
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.inLethalFluid ? priority : -1;
    }

    /** The configured flat priority. */
    public int priority() {
        return priority;
    }

    @Override
    public String name() {
        return "ASCEND_IN_LETHAL_FLUID";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.ASCEND;
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
        // Inverted: 0 = in lava (danger), 1 = safe. The hysteresis
        // state machine fires on "signal <= trigger", so 0 fires
        // and 1 stays silent - same shape as the low-health / low-air
        // rules without a new scheduler branch.
        return board.inLethalFluid ? 0f : 1f;
    }

    @Override
    public int minHoldTicks() {
        return LAVA_HOLD_TICKS;
    }
}
