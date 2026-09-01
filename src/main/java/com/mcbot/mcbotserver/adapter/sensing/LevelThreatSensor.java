package com.mcbot.mcbotserver.adapter.sensing;

import com.mcbot.mcbotserver.api.capability.Feature;
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
    @Feature(
            id = "combat.hostile_acquisition.hostile_type_catalog",
            face = "combat.hostile_acquisition",
            description =
                    "Hostile type catalog: 10 mob types (creeper, zombie, skeleton, spider, witch, drowned, husk," +
                    "stray, pillager, vindicator). Threat classification is data owned by the sensing side;" +
                    "consumers read it here instead of re-listing mobs.",
            vanillaRef = "Monster base class + hostile mob registry (decompiled 1.20.1)")
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
    @Feature(
            id = "combat.hostile_acquisition.ranged_type_catalog",
            face = "combat.hostile_acquisition",
            description =
                    "Ranged hostile type catalog: 4 types (skeleton, stray, pillager, witch) whose kite-and-shoot" +
                    " tactics structurally defeat a melee-only bot. The combat planner reads this set to REFUSE" +
                    " such engagements honestly instead of chasing to timeout.",
            vanillaRef = "AbstractSkeleton, Pillager, Witch AI (decompiled 1.20.1)",
            deviation =
                    "Bot-specific: a melee-only bot cannot answer ranged threats, so their type classification" +
                    " drives the ENGAGEMENT_REFUSED verdict — vanilla players can switch to a bow or close distance.")
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

    /** Best-food hotbar slot or -1, ranked by the assembly's catalog. */
    private final java.util.function.IntSupplier bestFoodSlot;

    private final Supplier<Boolean> falling;
    private final Supplier<CellPos> mlgGroundCell;
    private final java.util.function.IntSupplier waterBucketSlot;

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
     * @param bestFoodSlot  best-food hotbar slot supplier, -1 when
     *                      the inventory carries no food; never null
     * @param falling       body descending supplier (real downward
     *                      motion, standing jitter excluded); never null
     * @param mlgGroundCell first solid cell below the feet inside the
     *                      MLG scan depth, null when deeper; never null
     * @param waterBucketSlot water-bucket hotbar slot supplier, -1
     *                      when none; never null
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
            java.util.function.IntSupplier bestFoodSlot,
            Supplier<Boolean> falling,
            Supplier<CellPos> mlgGroundCell,
            java.util.function.IntSupplier waterBucketSlot) {
        for (Object arg : new Object[] {
            bodyPos,
            airSupply,
            inLava,
            fireTicks,
            freezeTicks,
            inWall,
            suffocationBlock,
            foodLevel,
            bestFoodSlot,
            falling,
            mlgGroundCell,
            waterBucketSlot
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
        this.bestFoodSlot = bestFoodSlot;
        this.falling = falling;
        this.mlgGroundCell = mlgGroundCell;
        this.waterBucketSlot = waterBucketSlot;
    }

    @Feature(
            id = "combat.hostile_acquisition.omni_directional_scan",
            face = "combat.hostile_acquisition",
            description =
                    "Bearing-blind 16-block omnidirectional scan for nearest hostile. Includes rear threats — the" +
                    " creeper-from-behind scenario is the reason the reflex layer exists, so the sensor must not" +
                    " encode a forward bias. Matches vanilla hostile awareness scale.",
            vanillaRef = "Hostile mobs follow range + line-of-sight AI (decompiled 1.20.1)",
            deviation =
                    "Acquisition is the hostile mob's own AI (follow range, sight, idle target slot); the bot side" +
                    " only needs a carrier-side presence pass. This sensor is threat detection for the bot's" +
                    " reflexes, not the acquisition mechanism itself.")
    @Override
    public void sense(WorldView world, ThreatBlackboard board) {
        // Vitals are body state, not world state - they cannot be read
        // through WorldView, so suppliers are the only honest source
        // (same category as botHealth's pipeline accessor).
        board.airSupply = airSupply.get();
        board.foodLevel = foodLevel.get();
        board.foodSlot = bestFoodSlot.getAsInt();
        board.descending = falling.get();
        board.groundCell = mlgGroundCell.get();
        board.waterBucketSlot = waterBucketSlot.getAsInt();
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
