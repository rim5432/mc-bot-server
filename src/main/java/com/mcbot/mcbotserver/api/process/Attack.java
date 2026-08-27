package com.mcbot.mcbotserver.api.process;

import java.util.Objects;

/**
 * One combat order: attack a named entity until it stops being a
 * problem. The planner names the target; how to close distance, aim,
 * and time the swings is the behavior tier's craft.
 *
 * <p>Contract: see boundaries.md decision 11 and decision 10 (no
 * per-mob processes).
 *
 * @param targetId identity of the entity to attack ({@code
 *                 EntitySnapshot.id}); never null or blank
 */
// contract: see boundaries.md decision 11 (light planner output)
public record Attack(String targetId) implements CombatOrder {

    /**
     * Creates a validated order.
     *
     * @param targetId must not be null or blank
     */
    public Attack {
        Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
    }
}
