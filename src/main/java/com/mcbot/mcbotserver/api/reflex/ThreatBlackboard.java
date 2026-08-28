package com.mcbot.mcbotserver.api.reflex;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import javax.annotation.Nullable;

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
 *
 * <p>Field hygiene: every field here is read by at least one shipped
 * reflex rule; day/tick/botPos were removed in 2026-08-27 after a
 * SpotBugs UrF pass confirmed zero reads across main and test (they
 * were premature API for time-based rules not yet designed).
 */
public final class ThreatBlackboard {

    /** Body health at sensing time; vanilla scale 0..20. */
    public float botHealth = 20f;

    /**
     * Body food level at sensing time, 0..20 (issue 0010 section 4.1);
     * safe-defaults to 20 so a rig whose sensor never stamps food
     * reads as well-fed - no food data must not mint a hungry reflex.
     */
    public int foodLevel = 20;

    /**
     * Body saturation at sensing time, 0..foodLevel (issue 0010
     * section 4.1); safe-defaults to FoodData's constructor value 5.0.
     */
    public float saturationLevel = 5.0f;

    /**
     * Hotbar slot of the best food in inventory at sensing time, or
     * -1 when none was sensed - the eat reflex's availability gate:
     * hungry with no food is an acquisition/harness problem (issue
     * 0010 section 5), not a body reflex.
     */
    public int foodSlot = -1;

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
    @Nullable
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
    @Nullable
    public CellPos suffocationBlock;

    /**
     * True while the body moves meaningfully downward through air -
     * the MLG window open flag. Standing jitter (gravity's small
     * negative drift) must not read as falling.
     */
    public boolean descending;

    /**
     * First solid cell directly below the feet within the MLG scan
     * depth, null when the drop is deeper than the scan (or the body
     * is over the void) - the water-placement aim target. A body
     * standing on ground still has this stamped (the floor is a
     * solid below); the {@code descending} flag is what keeps the
     * rule silent.
     */
    public CellPos groundCell;

    /**
     * Hotbar slot holding a water bucket, or -1 when the inventory
     * carries none - no MLG loadout must not mint a phantom
     * placement, same contract as {@link #foodSlot}.
     */
    public int waterBucketSlot = -1;

    /**
     * Reset all fields to a fresh-tick baseline before sensing.
     *
     * @param health body health at sensing time, 0..max
     */
    public void beginTick(float health) {
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
