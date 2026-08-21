package com.mcbot.mcbotserver.api.reflex;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;

/**
 * Per-tick scratchpad between sensors and reflex rules: sensors write,
 * rules read, nothing here outlives one tick of decision-making.
 *
 * <p>Contract: see ADR-0003 section 1 (sensor -> blackboard -> rule
 * table bypass). Deliberately mutable and dumb — the intelligence lives
 * in sensor code and in the rules' dynamic priority functions, never in
 * the board itself. Staleness handling is deferred until pathfinding
 * goes off-thread (boundaries.md reopen table).
 *
 * <p>Implementation note: written and read on the server tick thread
 * only; no synchronization by design.
 */
public final class ThreatBlackboard {

    /** Game day at sensing time; stamped by the sensor. */
    public long day;

    /** Time-of-day ticks at sensing time; stamped by the sensor. */
    public long t;

    /** Server tick counter at sensing time. */
    public long tick;

    /** Body position at sensing time; never null after sensing. */
    public CellPos botPos = new CellPos(0, 0, 0);

    /** Body health at sensing time; vanilla scale 0..20. */
    public float botHealth = 20f;

    /**
     * Nearest threatening entity within sensor range; null when none
     * was sensed this tick.
     */
    public EntitySnapshot nearestThreat;

    /** Distance in blocks to {@link #nearestThreat}; meaningless when null. */
    public double nearestThreatDistance;

    /** True while the body stands in lava or another lethal fluid. */
    public boolean inLethalFluid;

    /** Reset all fields to a fresh-tick baseline before sensing. */
    public void beginTick(long tickCounter, long gameDay,
                          long timeOfDayTicks, CellPos position,
                          float health) {
        this.tick = tickCounter;
        this.day = gameDay;
        this.t = timeOfDayTicks;
        this.botPos = position;
        this.botHealth = health;
        this.nearestThreat = null;
        this.nearestThreatDistance = Double.MAX_VALUE;
        this.inLethalFluid = false;
    }
}
