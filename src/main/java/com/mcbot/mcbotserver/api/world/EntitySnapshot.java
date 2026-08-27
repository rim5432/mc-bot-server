package com.mcbot.mcbotserver.api.world;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Read-only copy of a living entity's model-relevant facts.
 *
 * <p>Contract: see boundaries.md section A; decision 17. This is the
 * shape the threat sensor and combat planner consume; it deliberately
 * carries no engine handles, so snapshots stay valid across ticks and
 * safe to hand to off-thread consumers in Stage 2.
 *
 * @param id        stable identity of the entity (its UUID string);
 *                  never null
 * @param type      entity type key ("minecraft:creeper" style); never
 *                  null
 * @param pos       block cell the entity occupies; never null
 * @param health    current health, 0 or greater
 * @param maxHealth maximum health, positive
 */
public record EntitySnapshot(String id, String type, CellPos pos, float health, float maxHealth) {

    /**
     * Creates a validated snapshot.
     *
     * @param id        must not be null or blank
     * @param type      must not be null or blank
     * @param pos       must not be null
     * @param health    must not be negative
     * @param maxHealth must be positive
     */
    public EntitySnapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (health < 0f) {
            throw new IllegalArgumentException("health must not be negative");
        }
        if (maxHealth <= 0f) {
            throw new IllegalArgumentException("maxHealth must be positive");
        }
    }
}
