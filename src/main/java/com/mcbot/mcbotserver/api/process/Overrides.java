package com.mcbot.mcbotserver.api.process;

import javax.annotation.Nullable;

/**
 * Behavior-layer parameter adjustments attached to a directive. The
 * frozen contract named the field; combat was its first real
 * component, fishing (issue 0010 section 7) the second, taming the
 * third - a non-null order tells the behavior tier to run that
 * activity while the goal steers locomotion.
 *
 * <p>Contract: see ADR-0002 section 1 (Directive{Goal, Overrides}) and
 * boundaries.md decision 11.
 *
 * @param combat active combat order, or null for a purely locomotive
 *               directive; when non-null, behaviors that cannot fight
 *               must stay out of the way (return RUNNING without
 *               claims) so the combat behavior owns USE/ROT
 * @param fish   active fishing order, or null; when non-null the fish
 *               behavior owns the USE edges (cast and reel) and the
 *               combat behavior must stay out of the way
 * @param tame   active taming order, or null; when non-null the tame
 *               behavior owns the INTERACT edges (use-on-entity
 *               presses) and the combat behavior must stay out of the
 *               way
 */
// contract: see ADR-0002 section 1 + boundaries.md decision 11
public record Overrides(
        @Nullable CombatOrder combat,
        @Nullable Fish fish,
        @Nullable Tame tame) {

    /**
     * Creates the no-combat override used by every locomotive task.
     */
    public Overrides() {
        this(null, null, null);
    }

    /**
     * Combat-only override, the shape every pre-fish call site uses.
     *
     * @param combat the combat order; may be null
     */
    public Overrides(@Nullable CombatOrder combat) {
        this(combat, null, null);
    }

    /**
     * Combat-plus-fishing override, the pre-taming call-site shape.
     *
     * @param combat the combat order; may be null
     * @param fish   the fishing order; may be null
     */
    public Overrides(@Nullable CombatOrder combat, @Nullable Fish fish) {
        this(combat, fish, null);
    }
}
