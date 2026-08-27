package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The lava escape reflex: when the body stands in lethal fluid, submit
 * a reflex-owned rescue mission that paths to the nearest non-liquid
 * shore cell. The first tick parks the current mission (FREEZE hold);
 * from the next tick the rescue GotoProcess runs through the normal
 * mission stage and the pathing behavior handles all movement —
 * ascent through the fluid column plus horizontal swim to shore.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row) and issue
 * 0008 F2/D2. Lava deals 4.0F every tick with no interval
 * (Entity.lavaHurt, decompiled 1.20.1) plus sets 15 seconds of fire;
 * a full-health body dies in ~5 ticks. Priority 130 outranks every
 * other reflex because 4 HP/tick does not negotiate. This rule
 * replaces the earlier AscendInLethalFluidRule (ASCEND-only): the
 * self-rescue half (held jump -> surface) was necessary but not
 * sufficient — a bot floating on the lava surface still burns and
 * goes nowhere horizontally. The ESCAPE handoff unifies ascent and
 * shore-reach under one pathing mission, the same shape ENGAGE uses
 * for combat (ReflexAction#ESCAPE).
 *
 * <p>The rescue mission factory (BotAssembly wiring) reads the body
 * state to find the nearest shore cell; a factory that finds no
 * reachable target returns null and the preemption degrades to the
 * FREEZE hold (park and escalate) — no escape route means the
 * harness must decide. The hysteresis hold window (10 ticks) clears
 * the lava-air boundary before releasing, same as the ASCEND rule it
 * replaces.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class EscapeLavaRule implements ReflexRule, ReflexHysteresis {

    /** Flat firing priority; above every other reflex — lava is the
     * fastest killer in the catalog (4 HP/tick). */
    public static final int LAVA_ESCAPE_PRIORITY = 130;

    /**
     * Hold window: once the body leaves lava, stay preempted briefly
     * so a bobbing/glitching boundary does not flap the rule; the
     * rescue mission is already submitted and runs independently.
     */
    public static final int LAVA_ESCAPE_HOLD_TICKS = 10;

    /**
     * Hysteresis trigger on the inverted signal (0 = in lava,
     * 1 = safe). Signal 0 <= 0.5 fires; signal 1 stays silent.
     */
    private static final float TRIGGER = 0.5f;

    /** Release threshold on the inverted signal; 1 > 0.6 releases. */
    private static final float RELEASE = 0.6f;

    private final int priority;

    /** Creates the rule with the default table's priority. */
    public EscapeLavaRule() {
        this(LAVA_ESCAPE_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public EscapeLavaRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
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
        return "ESCAPE_ON_LAVA";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.ESCAPE;
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
        // Inverted: 0 = in lava (danger), 1 = safe. Same shape as
        // the ASCEND rule this replaces; no new scheduler branch.
        return board.inLethalFluid ? 0f : 1f;
    }

    @Override
    public int minHoldTicks() {
        return LAVA_ESCAPE_HOLD_TICKS;
    }
}
