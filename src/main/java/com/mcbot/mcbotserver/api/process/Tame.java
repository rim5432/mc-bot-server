package com.mcbot.mcbotserver.api.process;

import java.util.Objects;

/**
 * A taming order carried inside {@link Overrides} - the process
 * tier's way to say "tame this animal". Pure data; pacing the
 * use-on-entity presses belongs to the behavior tier and the vanilla
 * chain to the adapter. The order carries the species key because
 * the process only emits it after a verified sighting, so the
 * behavior can pick the tame item without a second world scan - the
 * fish order's waterCell pattern, one fact further.
 *
 * <p>Contract: see boundaries.md decision 11 (planner orders,
 * executor owns motion) and decision 28 (task verbs grow by issue).
 *
 * @param targetId identity of the animal to tame ({@code
 *                 EntitySnapshot.id}); never null or blank
 * @param typeKey  entity type key of the animal ({@code
 *                 EntitySnapshot.type()}); never null or blank, and
 *                 always an item-tameable species by construction
 */
// contract: see boundaries.md decision 11 (planner orders, executor owns motion)
public record Tame(String targetId, String typeKey) {

    /**
     * Creates a validated order.
     *
     * @param targetId must not be null or blank
     * @param typeKey  must not be null or blank
     */
    public Tame {
        Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        Objects.requireNonNull(typeKey, "typeKey");
        if (typeKey.isBlank()) {
            throw new IllegalArgumentException("typeKey must not be blank");
        }
    }
}
