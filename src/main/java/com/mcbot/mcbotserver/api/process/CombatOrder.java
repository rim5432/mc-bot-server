package com.mcbot.mcbotserver.api.process;

/**
 * A combat order carried inside {@link Overrides} - the process tier's
 * ONLY way to say "fight". Pure data; resolving it into swings and
 * damage belongs to the behavior/adapter tiers.
 *
 * <p>Contract: see boundaries.md decision 11 (DefendProcess is a light
 * planner; CombatBehavior owns micro-execution) and decision 10 (no
 * per-mob processes - one order shape serves every hostile).
 */
// contract: see boundaries.md decision 11 (planner orders, executor owns motion)
public sealed interface CombatOrder permits Attack {

    /**
     * The entity this order names, as its {@code EntitySnapshot.id}.
     *
     * @return target identity; never null or blank
     */
    String targetId();
}
