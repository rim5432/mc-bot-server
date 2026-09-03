package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexHysteresis;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.reflex.ThreatSensor;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The survival bypass: sense -> board -> rule table -> one winning
 * reflex decision, evaluated BEFORE the task arbiter every tick.
 *
 * <p>Contract: see ADR-0003 section 2 and boundaries.md section C. A
 * non-null decision means the mission stage is skipped entirely this
 * tick — the layer never competes numerically with the arbiter, it
 * simply outranks the pipeline by call order. Rewriting the whole rule
 * table is invisible to processes; that is the seam's promise.
 *
 * <p>Implementation note: runs on the server tick thread only.
 * Rules implementing ReflexHysteresis are run through a
 * double-threshold + hold-window state machine so a signal that
 * jitters around the trigger does not flip the decision every
 * tick. The absorption lives here in the scheduler, not in the
 * rule itself, so ReflexRule stays pure.
 */
// contract: see ADR-0003 section 2 (reflex first; non-null skips mission)
// contract: see boundaries.md anti-oscillation note (double threshold
//           + hold window absorb signal jitter at the scheduler)
public final class SurvivalReflexLayer {

    /**
     * One fired reflex, resolved for this tick. {@code target} is the
     * action's geometry (the eye block for DIG) and is null for kinds
     * that need none - or when the board carried no position, in which
     * case the controller degrades target-consuming kinds to the
     * freeze hold.
     */
    public record ReflexDecision(
            String ruleName,
            int priority,
            ReflexAction action,
            @Nullable com.mcbot.mcbotserver.api.types.CellPos target) {

        /**
         * Creates a targetless decision (every kind but DIG).
         *
         * @param ruleName originating reflex rule id; never null
         * @param priority scheduler ordering key; higher runs first
         * @param action   what the controller should perform; never null
         */
        public ReflexDecision(String ruleName, int priority, ReflexAction action) {
            this(ruleName, priority, action, null);
        }
    }

    private final ThreatSensor sensor;
    private final ThreatBlackboard blackboard = new ThreatBlackboard();
    private final List<ReflexRule> rules = new ArrayList<>();
    // Per-rule hysteresis state, keyed by rule name. Only rules that
    // implement ReflexHysteresis populate entries here; non-hysteretic
    // rules pass through unchanged. ticksSinceChange starts at the
    // maximum so a fresh layer can fire on its very first tick when
    // the rule wants to.
    private final Map<String, HysteresisState> hysteresisState = new HashMap<>();

    /**
     * Creates a layer over one sensing pass.
     *
     * @param sensor fills the blackboard each tick; never null
     */
    public SurvivalReflexLayer(ThreatSensor sensor) {
        if (sensor == null) {
            throw new IllegalArgumentException("sensor must not be null");
        }
        this.sensor = sensor;
    }

    /**
     * Append a rule to the table. Order in the list is irrelevant —
     * winners are decided per tick by computed priority.
     *
     * @param rule the rule to add; must not be null
     * @return this layer, for fluent setup
     */
    public SurvivalReflexLayer addRule(ReflexRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must not be null");
        }
        rules.add(rule);
        return this;
    }

    /**
     * Swap the whole rule table at once - the datapack reload path.
     * The seam's promise made literal: running processes never see the
     * rewrite; the next tick simply evaluates the new table.
     *
     * @param replacement rules to install from now on; never null;
     *                    may be empty (layer then never fires)
     */
    public void replaceRules(List<ReflexRule> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        // Copy: the caller's list stays mutable and none of our
        // business; a single reference swap keeps tick reads atomic.
        this.rules.clear();
        this.rules.addAll(replacement);
        // Hysteresis state is keyed by rule name, so a same-named
        // replacement would inherit the previous instance's
        // active/lastPriority/ticksSinceChange and fire stale
        // decisions for up to FREEZE_HOLD_TICKS ticks. Clear on
        // reload so the new table starts at a known baseline.
        this.hysteresisState.clear();
    }

    /**
     * The shared blackboard, exposed for tests and diagnostics only.
     * Production code should treat it as layer-internal.
     *
     * @return the board this layer senses into
     */
    public ThreatBlackboard blackboard() {
        return blackboard;
    }

    /**
     * Run one reflex pass. Fires at most one rule — the highest
     * computed priority among positive results.
     *
     * @param world        read-only perception; never null
     * @param botHealth    body health for the board stamp
     * @return the winning decision, or null when no rule fired and the
     *         mission stage may proceed
     */
    @Nullable
    public ReflexDecision tick(WorldView world, float botHealth) {
        Objects.requireNonNull(world, "world");
        blackboard.beginTick(botHealth);
        sensor.sense(world, blackboard);

        ReflexDecision winner = null;
        for (ReflexRule rule : rules) {
            int rawPriority = rule.computePriority(blackboard);
            boolean shouldFire;
            int effectivePriority;
            if (rule instanceof ReflexHysteresis h) {
                HysteresisState s = decideHysteresis(h, rule.name(), rawPriority);
                shouldFire = s.active;
                // When held, the rule still reports as firing but the
                // raw priority is negative (signal above trigger) -
                // use the last positive priority we saw so the
                // arbiter sees a stable number across the hold.
                effectivePriority = shouldFire ? (rawPriority > 0 ? rawPriority : s.lastPriority) : -1;
            } else {
                shouldFire = rawPriority > 0;
                effectivePriority = rawPriority;
            }
            if (shouldFire && (winner == null || effectivePriority > winner.priority())) {
                winner = new ReflexDecision(
                        rule.name(), effectivePriority, rule.action(), rule.actionTarget(blackboard));
            }
        }
        return winner;
    }

    /**
     * State machine for a hysteretic rule: tracks the current
     * should-fire decision and the ticks since the last change.
     * The transition is gated by both the signal thresholds
     * (current state dictates which threshold applies) and a
     * per-rule hold window.
     *
     * <p>Returns the live state object so the caller can read the
     * state's recorded priority ({@link HysteresisState#lastPriority})
     * without a second map lookup — the
     * computeIfAbsent here IS the non-null guarantee, and returning
     * the state turns a temporal invariant (call order) into a
     * data dependency (the caller consumes what this method returns).
     *
     * @param h            the hysteretic rule
     * @param ruleName     stable name for state lookup
     * @param rawPriority  the rule's raw computed priority this tick;
     *                     positive when the rule's base condition
     *                     fires
     * @return the live hysteresis state for this rule; never null
     */
    private HysteresisState decideHysteresis(ReflexHysteresis h, String ruleName, int rawPriority) {
        HysteresisState s = hysteresisState.computeIfAbsent(ruleName, k -> new HysteresisState());
        if (rawPriority > 0) {
            s.lastPriority = rawPriority;
        }
        float signal = h.signalValue(blackboard);
        // Activation uses <= so the documented "at or below the
        // trigger" is exactly true at the boundary value; release
        // keeps strict < so the deadband spans (trigger, release).
        // computePriority's <= and this state machine must agree -
        // a mismatch would shift the effective trigger by one tick.
        boolean wantsActive = s.active ? signal < h.releaseThreshold() : signal <= h.triggerThreshold();
        if (wantsActive == s.active) {
            return s;
        }
        // Wants to change state: gate by the hold window. Increment
        // first so a single wants-change tick that arrives exactly
        // at the window boundary still has to wait one more tick.
        if (s.ticksSinceChange < h.minHoldTicks()) {
            s.ticksSinceChange++;
            return s;
        }
        s.active = wantsActive;
        s.ticksSinceChange = 0;
        return s;
    }

    /**
     * Per-rule hysteresis memory. {@code active} is the current
     * should-fire decision; {@code ticksSinceChange} counts ticks
     * since the last flip and gates the next one. {@code lastPriority}
     * is the most recent positive priority the rule produced - used
     * during the hold window when raw priority has dropped to -1
     * but the rule should still report as firing.
     */
    private static final class HysteresisState {
        boolean active;
        int ticksSinceChange = Integer.MAX_VALUE;
        int lastPriority = -1;
    }
}
