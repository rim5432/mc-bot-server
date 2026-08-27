package com.mcbot.mcbotserver.adapter.sensing;

import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.reflex.ThreatSensor;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The in-game sensing pass: nearest hostile within range, rear
 * included. A full-radius scan is deliberately bearing-blind — the
 * creeper-from-behind scenario is the reason the reflex layer exists,
 * so the sensor must not encode a forward bias.
 *
 * <p>Contract: see ADR-0003 section 1 and workplan Stage 1. Threat
 * classification is data (a hostile-type set); fuse-state refinement
 * lands with the rule table when a rule actually needs it.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see ADR-0003 section 1 (sensor -> blackboard, no decisions)
public final class LevelThreatSensor implements ThreatSensor {

    /** Scan radius in blocks; matches vanilla hostile awareness scale. */
    public static final double THREAT_RANGE = 16.0;

    private static final Set<String> HOSTILE_TYPES = Set.of(
            "minecraft:creeper",
            "minecraft:zombie",
            "minecraft:skeleton",
            "minecraft:spider",
            "minecraft:witch",
            "minecraft:drowned",
            "minecraft:husk",
            "minecraft:stray",
            "minecraft:pillager",
            "minecraft:vindicator");

    /**
     * The shared hostile-type vocabulary. Threat classification is
     * data owned by the sensing side; consumers (combat planner
     * wiring) read it here instead of re-listing mobs.
     *
     * @return unmodifiable hostile type ids; never null or empty
     */
    public static Set<String> hostileTypes() {
        return HOSTILE_TYPES;
    }

    private static final Set<String> RANGED_TYPES =
            Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:pillager", "minecraft:witch");

    /**
     * Hostile types whose optimal tactics (kite and shoot) structurally
     * defeat a melee-only bot: engaging them means eating fire without
     * ever reaching standoff. The combat planner reads this set to
     * REFUSE such engagements honestly instead of chasing to timeout.
     *
     * @return unmodifiable ranged hostile type ids; never null or empty
     */
    public static Set<String> rangedTypes() {
        return RANGED_TYPES;
    }

    private final Supplier<CellPos> bodyPos;
    private final Supplier<Integer> airSupply;
    private final Supplier<Boolean> inLava;
    private final Supplier<Integer> fireTicks;
    private final Supplier<Integer> freezeTicks;
    private final Supplier<Boolean> inWall;
    private final Supplier<CellPos> suffocationBlock;

    /** Body food level (0..20), FoodData semantics. */
    private final Supplier<Integer> foodLevel;

    /** Body saturation (0..foodLevel), FoodData semantics. */
    private final Supplier<Float> saturationLevel;

    /** Best-food hotbar slot or -1, ranked by the assembly's catalog. */
    private final java.util.function.IntSupplier bestFoodSlot;

    /**
     * Creates a sensor scanning around the live body position.
     *
     * @param bodyPos    current body cell supplier; never null
     * @param airSupply  body air supplier (vanilla
     *                   {@code body::getAirSupply}); never null - a
     *                   null result is a wiring bug and must fail
     *                   loudly, not read as "breathing"
     * @param inLava     body lava supplier ({@code body::isInLava});
     *                   never null - writes board.inLethalFluid, the
     *                   field that was dead since ADR-0003 (issue 0008
     *                   F2) until this vitals pass
     * @param fireTicks  body fire supplier ({@code body::getRemainingFireTicks});
     *                   never null
     * @param freezeTicks body freeze supplier ({@code body::getTicksFrozen});
     *                    never null
     * @param inWall     body suffocation supplier ({@code body::isInWall});
     *                   never null
     * @param suffocationBlock solid eye-cell supplier for the DIG
     *                   self-rescue (issue 0009); never null - must
     *                   return null while the eye is clear, so an
     *                   unstamped board degrades to the freeze hold
     *                   rather than a dig-at-null
     * @param foodLevel     body food-level supplier (0..20, FoodData
     *                      semantics); never null
     * @param saturationLevel body saturation supplier; never null
     * @param bestFoodSlot  best-food hotbar slot supplier, -1 when
     *                      the inventory carries no food; never null
     */
    public LevelThreatSensor(
            Supplier<CellPos> bodyPos,
            Supplier<Integer> airSupply,
            Supplier<Boolean> inLava,
            Supplier<Integer> fireTicks,
            Supplier<Integer> freezeTicks,
            Supplier<Boolean> inWall,
            Supplier<CellPos> suffocationBlock,
            Supplier<Integer> foodLevel,
            Supplier<Float> saturationLevel,
            java.util.function.IntSupplier bestFoodSlot) {
        for (Object arg : new Object[] {
            bodyPos,
            airSupply,
            inLava,
            fireTicks,
            freezeTicks,
            inWall,
            suffocationBlock,
            foodLevel,
            saturationLevel,
            bestFoodSlot
        }) {
            if (arg == null) {
                throw new IllegalArgumentException("arguments must not be null");
            }
        }
        this.bodyPos = bodyPos;
        this.airSupply = airSupply;
        this.inLava = inLava;
        this.fireTicks = fireTicks;
        this.freezeTicks = freezeTicks;
        this.inWall = inWall;
        this.suffocationBlock = suffocationBlock;
        this.foodLevel = foodLevel;
        this.saturationLevel = saturationLevel;
        this.bestFoodSlot = bestFoodSlot;
    }

    @Override
    public void sense(WorldView world, ThreatBlackboard board) {
        // Vitals are body state, not world state - they cannot be read
        // through WorldView, so suppliers are the only honest source
        // (same category as botHealth's pipeline accessor).
        board.airSupply = airSupply.get();
        board.foodLevel = foodLevel.get();
        board.saturationLevel = saturationLevel.get();
        board.foodSlot = bestFoodSlot.getAsInt();
        board.inLethalFluid = inLava.get();
        board.fireTicks = fireTicks.get();
        board.freezeTicks = freezeTicks.get();
        board.inWall = inWall.get();
        CellPos eyeBlock = suffocationBlock.get();
        if (eyeBlock != null && !board.inWall) {
            // Position without the vital is stale data, not a rescue
            // target - the pair must travel together or not at all.
            eyeBlock = null;
        }
        board.suffocationBlock = eyeBlock;
        CellPos center = bodyPos.get();
        List<EntitySnapshot> hits =
                world.getEntities(center, THREAT_RANGE, com.mcbot.mcbotserver.api.world.ViewMode.LIVE);
        EntitySnapshot nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (EntitySnapshot e : hits) {
            if (!HOSTILE_TYPES.contains(e.type())) {
                continue;
            }
            double d = e.pos().distanceTo(center);
            if (d < nearestDist) {
                nearest = e;
                nearestDist = d;
            }
        }
        board.nearestThreat = nearest;
        board.nearestThreatDistance = nearest != null ? nearestDist : Double.MAX_VALUE;
    }
}
