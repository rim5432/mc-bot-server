package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.types.CellPos;
import javax.annotation.Nullable;

/**
 * The sighted/leashed/graced tracking state machine shared by the two
 * fighter processes (DefendProcess, AttackProcess): refresh on
 * sighting with the leash verdict, grace counting on absence with the
 * ESCAPED verdict, and the resume blind-trust guard.
 *
 * <p>ORDER IS LOAD-BEARING, per the defend implementation this
 * generalizes: a sighting resets the grace counter to zero BEFORE the
 * absence branch can fire, which is what lets {@link #spendAllGrace}
 * (the resume trick) work - a target still present on the first
 * post-resume tick resets the counter and the fight continues; an
 * absent one trips the grace verdict immediately. Callers must run
 * the sighting path before the absence path every tick.
 *
 * <p>The scan semantics stay with the callers (nearest-hostile by
 * type vs find-by-id); this class owns only the tracking bookkeeping
 * and the shared envelope constants.
 *
 * <p>Implementation note: runs on the server tick thread.
 */
public final class TargetTracker {

    /**
     * An engaged target farther away than this escapes - the fight is
     * over as a failure, not a chase to the world border.
     */
    public static final double LEASH_RADIUS = 12.0;

    /** Ticks an absent target gets before counting as escaped/dead. */
    public static final int TARGET_GRACE_TICKS = 10;

    @Nullable
    private CellPos targetCell;

    private int ticksSinceSeen;
    private boolean engaged;

    /**
     * Whether a target has ever been sighted this engagement.
     *
     * @return true once {@link #sighted} has run at least once
     */
    public boolean engaged() {
        return engaged;
    }

    /**
     * The last sighted target cell.
     *
     * @return the cell; null before the first sighting
     */
    @Nullable
    public CellPos targetCell() {
        return targetCell;
    }

    /**
     * Records a sighting this tick: refreshes the cell, resets the
     * grace counter, marks the engagement live. Run this BEFORE the
     * absence path - see the class doc for why the order freezes.
     *
     * @param cell the target's current cell; never null
     */
    public void sighted(CellPos cell) {
        this.targetCell = cell;
        this.ticksSinceSeen = 0;
        this.engaged = true;
    }

    /**
     * Leash verdict for this tick's sighting.
     *
     * @param from the body's current cell; never null
     * @return true when the target is beyond the leash radius
     */
    public boolean leashed(CellPos from) {
        CellPos cell = targetCell;
        if (cell == null) {
            return false;
        }
        return from.distanceTo(cell) > LEASH_RADIUS;
    }

    /**
     * One absence tick: spend a grace tick and report whether the
     * window is exhausted.
     *
     * @return true when the target has been absent longer than the
     *         grace window
     */
    public boolean graceSpent() {
        ticksSinceSeen++;
        return ticksSinceSeen > TARGET_GRACE_TICKS;
    }

    /**
     * The resume blind-trust guard: resume() has no world access, so
     * instead of trusting the pre-pause target, spend ALL grace
     * credit - the very next onTick scan adjudicates. Target gone =>
     * immediate escape verdict; present => normal refresh. Worst-case
     * stale steering collapses from a full grace window to exactly
     * one tick.
     */
    public void spendAllGrace() {
        ticksSinceSeen = TARGET_GRACE_TICKS;
    }
}
