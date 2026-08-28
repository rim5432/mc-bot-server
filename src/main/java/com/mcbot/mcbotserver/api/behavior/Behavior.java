package com.mcbot.mcbotserver.api.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.world.WorldView;
import javax.annotation.Nullable;

/**
 * The execution half of boundary B: the only code allowed to claim
 * Actor channels. Behaviors turn a directive into physical intents and
 * report execution state back — processes stay blind to both.
 *
 * <p>Contract: see ADR-0004 D1 stage 3 and D3; boundaries.md section B.
 * A null directive means no mission ran this tick: the behavior must
 * submit nothing (claims expire anyway) and answer RUNNING-idle.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
public interface Behavior {

    /**
     * Execute one tick of the given directive.
     *
     * @param world     read-only perception; never null
     * @param directive what to execute; may be null when no mission is
     *                  active, in which case no claims are submitted
     * @param actor     the claim surface; never null
     * @return this tick's verdict; never null
     */
    ExecutionReport tick(WorldView world, @Nullable Directive directive, Actor actor);

    /**
     * Stable identity for diagnostics and claim holder names.
     *
     * @return short name; never null or blank
     */
    String name();
}
