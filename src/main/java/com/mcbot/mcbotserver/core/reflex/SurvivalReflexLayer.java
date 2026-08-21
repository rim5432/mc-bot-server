package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.reflex.ThreatSensor;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 */
// contract: see ADR-0003 section 2 (reflex first; non-null skips mission)
public final class SurvivalReflexLayer {

    /** One fired reflex, resolved for this tick. */
    public record ReflexDecision(String ruleName, int priority) {
    }

    private final ThreatSensor sensor;
    private final ThreatBlackboard blackboard = new ThreatBlackboard();
    private final List<ReflexRule> rules = new ArrayList<>();

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
     * @param tickCounter  current pipeline tick stamp
     * @param gameDay      game day for the board stamp
     * @param timeOfDayTicks time-of-day ticks for the board stamp
     * @param botPos       body cell for the board stamp; never null
     * @param botHealth    body health for the board stamp
     * @return the winning decision, or null when no rule fired and the
     *         mission stage may proceed
     */
    public ReflexDecision tick(WorldView world, long tickCounter,
                               long gameDay, long timeOfDayTicks,
                               com.mcbot.mcbotserver.api.types.CellPos botPos,
                               float botHealth) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(botPos, "botPos");
        blackboard.beginTick(tickCounter, gameDay, timeOfDayTicks,
            botPos, botHealth);
        sensor.sense(world, blackboard);

        ReflexDecision winner = null;
        for (ReflexRule rule : rules) {
            int priority = rule.computePriority(blackboard);
            if (priority > 0
                && (winner == null || priority > winner.priority())) {
                winner = new ReflexDecision(rule.name(), priority);
            }
        }
        return winner;
    }
}
