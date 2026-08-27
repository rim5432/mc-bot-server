package com.mcbot.mcbotserver.api.process;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * A fishing order carried inside {@link Overrides} - the process
 * tier's way to say "fish here" (issue 0010 section 7). Pure data;
 * casting, bite watching, and reeling belong to the behavior/adapter
 * tiers.
 *
 * <p>Contract: see boundaries.md section B (Directive{Goal,
 * Overrides}) and issue 0010 section 4.3.
 *
 * @param waterCell the water cell to cast toward; never null
 */
// contract: see boundaries.md decision 11 (planner orders, executor owns motion)
public record Fish(CellPos waterCell) {

    /**
     * Creates a validated fishing order.
     *
     * @param waterCell must not be null
     */
    public Fish {
        if (waterCell == null) {
            throw new IllegalArgumentException("waterCell must not be null");
        }
    }
}
