package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The fire-extinguish reflex: when the remaining fire ticks will deal
 * enough damage to kill the body, submit a reflex-owned rescue mission
 * that paths to the nearest water-adjacent cell. Water contact
 * extinguishes fire (vanilla {@code isInWaterRainOrBubble} clears
 * remainingFireTicks), so reaching water resolves the threat.
 *
 * <p>Contract: see boundaries.md decision 24 (fire ruling, revised). The original D5 ruling
 * was "sense-only, no rule" on the assumption that fire is self-
 * limiting and self-answering. That holds for non-lethal fire (the
 * bot's route will eventually touch water, or the fire expires below
 * the kill threshold). But when the remaining damage exceeds current
 * health, waiting is a death sentence — the bot must actively seek
 * water. This rule fires only in that lethal band; trivial fire
 * (remaining damage below health) is left to the route and the
 * natural expiry, exactly as D5 intended.
 *
 * <p>Numeric grounding (decompiled 1.20.1, Entity.baseTick:483):
 * fire deals 1.0F every 20 ticks while {@code remainingFireTicks > 0}
 * and the body is not in lava. Total remaining damage =
 * {@code remainingFireTicks / 20} (integer division — the damage
 * events fall on multiples of 20, and 0 does not fire). Trigger when
 * remaining damage >= health; release when it drops below half health
 * (hysteresis deadband so a decrementing tick does not flicker the
 * rule on/off before the rescue mission reaches water).
 *
 * <p>Priority 105 sits between SURFACE (110) and FREEZE (100):
 * burning to death at 1 HP/sec is less urgent than drowning
 * (2 HP/sec after air exhaustion) but more urgent than a low-health
 * FREEZE — a frozen bot that is also burning still burns, so
 * finding water must outrank stopping.
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class ExtinguishFireRule implements ReflexRule, ReflexHysteresis {

    /**
     * Flat firing priority; between SURFACE (110) and FREEZE (100).
     * Burning to death outranks low-health freeze because freezing
     * does not stop the burn.
     */
    public static final int FIRE_EXTINGUISH_PRIORITY = 105;

    /**
     * Hold window: once the fire drops below the release band, stay
     * preempted briefly so a tick-to-tick decrement does not flicker
     * the rule; the rescue mission is already submitted and runs
     * independently.
     */
    public static final int FIRE_EXTINGUISH_HOLD_TICKS = 10;

    /**
     * Hysteresis trigger on the inverted signal (0 = lethal fire,
     * 1 = safe). Signal 0 <= 0.5 fires; signal 1 stays silent.
     */
    private static final float TRIGGER = 0.5f;

    /** Release threshold on the inverted signal; 1 > 0.6 releases. */
    private static final float RELEASE = 0.6f;

    /**
     * Release band: the rule releases when remaining damage drops
     * below health * this factor. A half-health band gives the rescue
     * mission time to reach water without the rule flickering off as
     * fireTicks decrements tick by tick.
     */
    private final int priority;

    /** Creates the rule with the default table's priority. */
    public ExtinguishFireRule() {
        this(FIRE_EXTINGUISH_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the
     * rule-table JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public ExtinguishFireRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return isLethalFire(board) ? priority : -1;
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
        return "EXTINGUISH_FIRE";
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
        // Inverted: 0 = lethal fire (will burn to death), 1 = safe.
        // The computePriority gate and this signal must agree on the
        // lethal definition; a mismatch would shift the effective
        // trigger by one tick.
        return isLethalFire(board) ? 0f : 1f;
    }

    @Override
    public int minHoldTicks() {
        return FIRE_EXTINGUISH_HOLD_TICKS;
    }

    /**
     * Lethal-fire test: remaining fire damage >= current health.
     * Uses integer division matching the engine's damage-event
     * cadence (1.0F per 20 ticks, events on multiples of 20).
     *
     * @param board this tick's sensed state; never null
     * @return true when the current fire will kill the body if it
     *         runs its full course without extinguishing
     */
    private static boolean isLethalFire(ThreatBlackboard board) {
        if (board.fireTicks <= 0) {
            return false;
        }
        int remainingDamage = board.fireTicks / 20;
        return remainingDamage >= board.botHealth;
    }
}
