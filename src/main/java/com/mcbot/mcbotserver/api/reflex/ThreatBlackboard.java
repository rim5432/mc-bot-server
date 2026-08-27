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
     * Vanilla body air supply at sensing time, 0..MAX_AIR_SUPPLY.
     * Drains 1/tick while the eyes are submerged and regenerates
     * 4/tick in air (LivingEntity#decreaseAirSupply /
     * #increaseAirSupply, decompiled 1.20.1); at zero the body takes
     * drowning damage every second. beginTick resets to full so a
     * rig whose sensor never stamps air reads as breathing - no air
     * data must not mint a drowning reflex.
     */
    public int airSupply = MAX_AIR_SUPPLY;

    /** Vanilla ceiling on {@link #airSupply} (Entity#getMaxAirSupply). */
    public static final int MAX_AIR_SUPPLY = 300;

    /**
     * Nearest threatening entity within sensor range; null when none
     * was sensed this tick.
     */
    public EntitySnapshot nearestThreat;

    /** Distance in blocks to {@link #nearestThreat}; meaningless when null. */
    public double nearestThreatDistance;

    /** True while the body stands in lava or another lethal fluid. */
    public boolean inLethalFluid;

    /**
     * Remaining fire ticks at sensing time; 0 when not burning.
     * Vanilla {@code Entity.getRemainingFireTicks()}; damage is 1.0 per
     * 20 ticks while positive (decompiled 1.20.1, Entity.baseTick).
     * beginTick resets to 0 so an un-stamped rig reads as not burning.
     */
    public int fireTicks;

    /**
     * Freeze progress at sensing time, 0..140. Vanilla
     * {@code Entity.getTicksFrozen()}; at 140 the body is fully frozen
     * and takes 1.0 per 40 ticks (LivingEntity.baseTick "freezing"
     * phase). beginTick resets to 0.
     */
    public int freezeTicks;

    /**
     * True while the body's eye is inside a solid block (suffocation).
     * Vanilla {@code LivingEntity.isInWall()}; deals 1.0 per tick with
     * no interval. beginTick resets to false.
     */
    public boolean inWall;

    /**
     * The solid cell containing the eye while {@link #inWall} is
     * true - the block the suffocation self-rescue digs out (issue
     * 0009). beginTick resets to null, and a rig that stamps inWall
     * without a position reads as targetless: the DIG action then
     * degrades to the freeze hold instead of digging at a guess.
     */
    public CellPos suffocationBlock;

    /** Reset all fields to a fresh-tick baseline before sensing. */
    public void beginTick(long tickCounter, long gameDay, long timeOfDayTicks, CellPos position, float health) {
        this.tick = tickCounter;
        this.day = gameDay;
        this.t = timeOfDayTicks;
        this.botPos = position;
        this.botHealth = health;
        this.airSupply = MAX_AIR_SUPPLY;
        this.nearestThreat = null;
        this.nearestThreatDistance = Double.MAX_VALUE;
        this.inLethalFluid = false;
        this.fireTicks = 0;
        this.freezeTicks = 0;
        this.inWall = false;
        this.suffocationBlock = null;
    }
}
