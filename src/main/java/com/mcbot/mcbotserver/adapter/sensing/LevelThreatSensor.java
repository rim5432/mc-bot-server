package com.mcbot.mcbotserver.adapter.sensing;

import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.reflex.ThreatSensor;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.List;

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

    private static final java.util.Set<String> HOSTILE_TYPES =
        java.util.Set.of(
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
    public static java.util.Set<String> hostileTypes() {
        return HOSTILE_TYPES;
    }

    private static final java.util.Set<String> RANGED_TYPES =
        java.util.Set.of(
            "minecraft:skeleton",
            "minecraft:stray",
            "minecraft:pillager",
            "minecraft:witch");

    /**
     * Hostile types whose optimal tactics (kite and shoot) structurally
     * defeat a melee-only bot: engaging them means eating fire without
     * ever reaching standoff. The combat planner reads this set to
     * REFUSE such engagements honestly instead of chasing to timeout.
     *
     * @return unmodifiable ranged hostile type ids; never null or empty
     */
    public static java.util.Set<String> rangedTypes() {
        return RANGED_TYPES;
    }

    private final com.mcbot.mcbotserver.adapter.BindingWorldView view;
    private final java.util.function.Supplier<CellPos> bodyPos;

    /**
     * Creates a sensor scanning around the live body position.
     *
     * @param view    perception read; never null
     * @param bodyPos current body cell supplier; never null
     */
    public LevelThreatSensor(com.mcbot.mcbotserver.adapter.BindingWorldView
                                 view,
                             java.util.function.Supplier<CellPos> bodyPos) {
        if (view == null || bodyPos == null) {
            throw new IllegalArgumentException(
                "arguments must not be null");
        }
        this.view = view;
        this.bodyPos = bodyPos;
    }

    @Override
    public void sense(WorldView world, ThreatBlackboard board) {
        CellPos center = bodyPos.get();
        List<EntitySnapshot> hits = world.getEntities(
            center, THREAT_RANGE,
            com.mcbot.mcbotserver.api.world.ViewMode.LIVE);
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
        board.nearestThreatDistance =
            nearest != null ? nearestDist : Double.MAX_VALUE;
    }
}
