package com.mcbot.mcbotserver.api.process;

import com.mcbot.mcbotserver.api.goal.Goal;

/**
 * The only output a process may produce: where to go, with which
 * behavior-layer overrides. Pure data — carrying it must have zero side
 * effects.
 *
 * <p>Contract: see ADR-0002 section 1 and boundaries.md section B (the
 * process tier stays side-effect-free; execution belongs to behaviors).
 *
 * @param goal      arrival authority for the mission; never null
 * @param overrides behavior-parameter adjustments; never null
 */
public record Directive(Goal goal, Overrides overrides) {

    /**
     * Creates a validated directive.
     *
     * @param goal      must not be null
     * @param overrides must not be null
     */
    public Directive {
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (overrides == null) {
            throw new IllegalArgumentException(
                "overrides must not be null");
        }
    }

    /**
     * Convenience factory for the common no-override case.
     *
     * @param goal the arrival goal; must not be null
     * @return a directive with default overrides
     */
    public static Directive of(Goal goal) {
        return new Directive(goal, new Overrides());
    }
}
