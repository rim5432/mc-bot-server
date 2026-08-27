package com.mcbot.mcbotserver.core.reflex;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;

/**
 * The only behaviour path allowed while the crash latch is set.
 *
 * <p>Contract: see ADR-0005 D3. Hard-coded, parameter-fed, stateless:
 * no ThreatBlackboard, no rule table, nothing derived — so the
 * probability of this class itself throwing is negligible. Shares zero
 * mutable state with {@code SurvivalReflexLayer}; if it ever throws
 * anyway, the same latch and dual-channel report fire again and the bot
 * stays at the documented floor instead of crashing the server.
 *
 * <p>Issue 0008 F8: the crashed state originally handled only lava
 * jump. A crashed bot standing in deep water would drown. The air
 * check below is one more {@code if} in the same dependency class as
 * the lava flag it already takes — no rule-table dependency, no
 * blackboard, still ADR-0005 D3 compliant.
 */
// contract: see ADR-0005 D3 (MinimalReflex is the crashed-state path)
public final class MinimalReflex {

    /** Claim priority that outranks every behavior claim. */
    public static final int MINIMAL_PRIORITY = 1000;

    /**
     * Air threshold below which a crashed bot holds jump to ascend.
     * Aligned with {@code SurfaceOnLowAirRule.TRIGGER_AIR} (80) but
     * duplicated here to keep this class free of rule-table
     * dependencies (ADR-0005 D3). ~4 seconds of air left at 80/300.
     */
    private static final int AIR_TRIGGER = 80;

    private static final String HOLDER = "minimal-reflex";

    private MinimalReflex() {}

    /**
     * Apply the degraded-mode body policy for one tick: jump while in
     * lethal fluid or while air is critically low, otherwise hold
     * still. Reads only its parameters.
     *
     * @param inLethalFluid whether the body stands in lava or similar
     * @param airSupply     current body air supply (vanilla
     *                      {@code getAirSupply()}, 0..300); used to
     *                      ascend a crashed bot that is drowning
     * @param actor         claim surface; never null
     */
    public static void tick(boolean inLethalFluid, int airSupply, Actor actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        boolean jump = inLethalFluid || airSupply < AIR_TRIGGER;
        actor.submit(new Claim(Channel.MOVE, MINIMAL_PRIORITY, HOLDER, new Intent.Move(0, 0, jump, false)));
    }
}
