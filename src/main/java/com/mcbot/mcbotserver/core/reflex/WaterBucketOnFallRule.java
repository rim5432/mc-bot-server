package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;

/**
 * The MLG water-bucket reflex: while descending over sensed ground
 * with a water bucket in the hotbar, place the water before impact -
 * the source lands above the hit face and vanilla water cancels the
 * fall damage.
 *
 * <p>Contract: see ADR-0003 section 2 (rule-table row, flat priority)
 * and boundary A - the rule never computes placement: the
 * WATER_BUCKET action maps to aim-select-pulse claims and the vanilla
 * POV raycast decides the exact source cell. The trigger is the
 * conjunction the sensor stamps: descending (real downward motion,
 * not standing jitter), a ground cell inside the scan depth (a deeper
 * - or void - drop has no MLG answer and stays silent), and a water
 * bucket in the hotbar. Self-limiting after one placement: the bucket
 * empties, the slot read turns -1, the rule stops.
 *
 * <p>Priority sits between DIG_ON_SUFFOCATION (115) and
 * SURFACE_ON_LOW_AIR (110): falling damage is deferred (landing is
 * what hurts) but certain, and the placement window is a handful of
 * ticks - above the routine rungs, below the immediate-tissue rules
 * (lava burns now; the ground has not been hit yet).
 */
// contract: see ADR-0003 section 2 (rules are data rows over the board)
public final class WaterBucketOnFallRule implements ReflexRule {

    /** Flat firing priority; see the class doc for the rung reasoning. */
    public static final int MLG_PRIORITY = 120;

    private final int priority;

    /** Creates the rule with the default table's priority. */
    public WaterBucketOnFallRule() {
        this(MLG_PRIORITY);
    }

    /**
     * Creates a configured rule - the data-driven shape the rule-table
     * JSON loader produces.
     *
     * @param priority flat firing priority; positive
     */
    public WaterBucketOnFallRule(int priority) {
        if (priority <= 0) {
            throw new IllegalArgumentException("priority must be positive");
        }
        this.priority = priority;
    }

    @Override
    public int computePriority(ThreatBlackboard board) {
        return board.descending && board.groundCell != null && board.waterBucketSlot >= 0 ? priority : -1;
    }

    @Override
    public String name() {
        return "WATER_BUCKET_ON_FALL";
    }

    @Override
    public ReflexAction action() {
        return ReflexAction.WATER_BUCKET;
    }

    @Override
    public com.mcbot.mcbotserver.api.types.CellPos actionTarget(ThreatBlackboard board) {
        return board.groundCell;
    }
}
