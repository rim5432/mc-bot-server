package com.mcbot.mcbotserver.api.process;

/**
 * Behavior-layer parameter adjustments attached to a directive. The
 * frozen contract named the field; combat is its first real component
 * - a directive whose {@code combat} order is non-null tells the
 * behavior tier to fight while the goal steers locomotion.
 *
 * <p>Contract: see ADR-0002 section 1 (Directive{Goal, Overrides}) and
 * boundaries.md decision 11.
 *
 * @param combat active combat order, or null for a purely locomotive
 *               directive; when non-null, behaviors that cannot fight
 *               must stay out of the way (return RUNNING without
 *               claims) so the combat behavior owns USE/ROT
 */
// contract: see ADR-0002 section 1 + boundaries.md decision 11
public record Overrides(CombatOrder combat) {

    /**
     * Creates the no-combat override used by every locomotive task.
     */
    public Overrides() {
        this(null);
    }
}
