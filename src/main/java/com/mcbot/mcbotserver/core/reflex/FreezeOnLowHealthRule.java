package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The Stage-0 minimal rule: freeze locomotion while badly hurt.
 *
 * <p>Contract: see boundaries.md decision ledger item 16 — one real
 * reflex rule ships in Phase 0 so the full sensor -> board -> rule ->
 * preempt -> resume chain runs against mocks before any entity exists.
 *
 * <p>Priority is deliberately flat (no distance gradient): bleeding out
 * is urgent regardless of geometry. The threshold is a constant until
 * the rule-table JSON loader lands (Stage 2); hard-coding it now keeps
 * the gate test honest about mechanism, not configuration.
 */
// contract: see boundaries.md decision 16 (minimal Phase-0 reflex)
public final class FreezeOnLowHealthRule implements ReflexRule {

    /** Health at or below which the rule fires. */
    public static final float FREEZE_THRESHOLD = 10f;

    /** Flat firing priority; outside all task bands by convention. */
    public static final int FREEZE_PRIORITY = 100;

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.botHealth <= FREEZE_THRESHOLD
            ? FREEZE_PRIORITY : -1;
    }

    @Override
    public String name() {
        return "FREEZE_ON_LOW_HEALTH";
    }
}
