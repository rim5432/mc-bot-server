package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.DigOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflex claim-shape gate: the non-freeze action kinds map to the
 * correct physical claims - ASCEND is a held jump so vanilla fluid
 * physics surfaces the body (issue 0004 F6(2)), and DIG is held
 * MOVE + aim ROT + INTERACT at the eye block with a targetless
 * degrade to the plain freeze hold (issue 0009).
 *
 * <p>Contract: see ADR-0004 D1 (claim channels) and ledger 25
 * (INTERACT dig).
 */
class TickReflexActionGateTest {

    /**
     * Drowning reflex drives the body up, not to a halt: the ASCEND
     * action kind maps to a held jump so vanilla fluid physics swims
     * the body to the surface (ReflexAction contract, issue 0004
     * F6(2)).
     */
    @Test
    void lowAirReflexHoldsJumpInsteadOfHalting() {
        float[] health = {20f};
        int[] air = {ThreatBlackboard.MAX_AIR_SUPPLY};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> board.airSupply = air[0]);
        layer.addRule(new SurfaceOnLowAirRule());
        TaskArbiter arbiter = new TaskArbiter();
        CountingMission mission = new CountingMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer,
            arbiter, mover, actor,
            new InMemoryEventQueue(() -> 1L, () -> 0L));
        MockWorldView world = TickGateFixtures.flooredWorld();
        controller.onTick(world);

        air[0] = 50;
        actor.submitted.clear();
        controller.onTick(world);

        Claim move = actor.lastClaim(Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "ascend claim must come from the reflex");
        assertTrue(move.intent() instanceof Intent.Move hold
                && hold.jump(),
            "drowning reflex must hold jump, not halt: "
                + move.intent());
    }

    /**
     * Suffocation reflex digs instead of halting: the DIG action maps
     * to a held MOVE + an aim ROT + an INTERACT dig claim at the
     * decision's target cell (issue 0009). A targetless DIG - the
     * board stamped the vital without the position - degrades to the
     * plain freeze hold: missing data must not mint a dig-at-null.
     */
    @Test
    void suffocationReflexSubmitsDigClaimsAtTheEyeBlock() {
        float[] health = {20f};
        boolean[] inWall = {false};
        CellPos[] eyeBlock = {null};
        SurvivalReflexLayer layer = new SurvivalReflexLayer(
            (world, board) -> {
                board.inWall = inWall[0];
                board.suffocationBlock =
                    inWall[0] ? eyeBlock[0] : null;
            });
        layer.addRule(new DigOnSuffocationRule());
        TaskArbiter arbiter = new TaskArbiter();
        PipelineActor actor = new PipelineActor();
        PathingBehavior mover = new PathingBehavior("mover",
            () -> new Vec3(0.5, 64, 0.5), BasicMoves::from);
        var controller = TickGateFixtures.controller(health, layer,
            arbiter, mover, actor,
            new InMemoryEventQueue(() -> 1L, () -> 0L));

        CellPos target = new CellPos(0, 65, 0);
        inWall[0] = true;
        eyeBlock[0] = target;
        controller.onTick(new MockWorldView());

        Claim move = actor.lastClaim(Channel.MOVE);
        assertNotNull(move);
        assertTrue(move.holder().startsWith("reflex:"),
            "hold claim must come from the reflex");
        assertTrue(move.intent() instanceof Intent.Move hold
                && !hold.jump() && hold.forward() == 0,
            "the dig runs standing still: " + move.intent());
        Claim rot = actor.lastClaim(Channel.ROT);
        assertNotNull(rot,
            "the reflex must aim at the dig target");
        assertTrue(rot.intent() instanceof Intent.Look);
        Claim interact = actor.lastClaim(Channel.INTERACT);
        assertNotNull(interact,
            "the reflex must hold the dig claim");
        assertTrue(interact.intent() instanceof Intent.Dig d
                && d.target().equals(target),
            "the dig claim names the eye block: "
                + interact.intent());

        // Targetless degrade: vital without position keeps the hold
        // but must not mint a dig-at-null.
        actor.submitted.clear();
        eyeBlock[0] = null;
        controller.onTick(new MockWorldView());
        assertNotNull(actor.lastClaim(Channel.MOVE));
        assertNull(actor.lastClaim(Channel.INTERACT),
            "a targetless DIG degrades to the freeze hold");
    }
}
