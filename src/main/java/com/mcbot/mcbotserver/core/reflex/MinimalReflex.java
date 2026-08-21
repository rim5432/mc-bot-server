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
 */
// contract: see ADR-0005 D3 (MinimalReflex is the crashed-state path)
public final class MinimalReflex {

    /** Claim priority that outranks every behavior claim. */
    public static final int MINIMAL_PRIORITY = 1000;

    private static final String HOLDER = "minimal-reflex";

    private MinimalReflex() {
    }

    /**
     * Apply the degraded-mode body policy for one tick: jump while in
     * lethal fluid, otherwise hold still. Reads only its parameters.
     *
     * @param inLethalFluid whether the body stands in lava or similar
     * @param actor         claim surface; never null
     */
    public static void tick(boolean inLethalFluid, Actor actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor must not be null");
        }
        actor.submit(new Claim(Channel.MOVE, MINIMAL_PRIORITY, HOLDER,
            new Intent.Move(0, 0, inLethalFluid, false)));
    }
}
