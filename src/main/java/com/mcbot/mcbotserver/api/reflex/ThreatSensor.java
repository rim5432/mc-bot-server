package com.mcbot.mcbotserver.api.reflex;

import com.mcbot.mcbotserver.api.world.WorldView;

/**
 * One sensing pass: read the world once, write the blackboard once.
 *
 * <p>Contract: see ADR-0003 section 1. Sensors are pure reads over
 * {@link WorldView} plus blackboard writes — no decisions here, no
 * Actor access, no arbiter knowledge. Scenario composition happens in
 * rule-table data, not by adding sensor branches.
 */
@FunctionalInterface
public interface ThreatSensor {

    /**
     * Fill the board for this tick. Implementations must call
     * {@link ThreatBlackboard#beginTick} first or stamp equivalently.
     *
     * @param world read-only perception; never null
     * @param board the tick's scratchpad; never null
     */
    void sense(WorldView world, ThreatBlackboard board);
}
