package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-2 stuck semantics of the planner-follower: the fuse is a
 * RECOVERY trigger - a successful replan keeps the report RUNNING and
 * claims flowing; STUCK is reported only when recovery planning also
 * fails.
 *
 * <p>Contract: see ADR-0004 D3 and workplan Stage 2 (replan ladder).
 */
class StuckFuseTest {

    private static MockWorldView floorTo(int xLen) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new com.mcbot.mcbotserver.api.world
                    .BlockSnapshot(new CellPos(x, 63, z),
                        "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static Vec3 at(double x) {
        return new Vec3(x, 64, 0.5);
    }

    /** Delegating actor that records every submission this tick. */
    /**
     * Frozen body trips the fuse; the recovery replan finds a fresh
     * route; reports stay RUNNING and claims keep flowing until real
     * arrival - which flips to SUCCESS via the goal predicate alone.
     */
    @Test
    void recoveryReplanKeepsMissionAlive() {
        Vec3[] position = {at(0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        int moveClaimTicks = 0;
        for (int i = 1; i <= 25; i++) {
            int before = actor.submitted.size();
            mover.tick(world, directive, actor);
            boolean hasMove = actor.submitted.subList(before,
                actor.submitted.size()).stream()
                .anyMatch(c -> c.channel() == Channel.MOVE);
            if (hasMove) {
                moveClaimTicks++;
            }
        }
        assertTrue(moveClaimTicks > 0,
            "a frozen-but-reachable stall keeps claiming movement");

        // Arrival overrides everything: predicate decides SUCCESS.
        position[0] = at(40.5);
        assertEquals(ExecutionReport.Status.SUCCESS,
            mover.tick(world, directive, actor).status());
    }

    /**
     * Fuse fires on a frozen body; recovery replan fails because the
     * route ahead was sealed; the shell reports FAILED with reason
     * STUCK exactly once per trip.
     */
    @Test
    void failedRecoveryReportsStuck() {
        Vec3[] position = {at(0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(50, 64, 0)));

        // Walk a little so the window holds motion history.
        for (int i = 1; i <= 10; i++) {
            position[0] = at(0.5 + i * 0.05);
            mover.tick(world, directive, actor);
        }
        // Seal the corridor far ahead: remaining route impossible.
        for (int x = 30; x <= 60; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new com.mcbot.mcbotserver.api.world
                    .BlockSnapshot(new CellPos(x, 63, z),
                        com.mcbot.mcbotserver.api.world.BlockSnapshot.AIR));
            }
        }
        // Freeze through the window so the fuse trips on stale motion.
        int stuckReports = 0;
        for (int i = 1; i <= 45; i++) {
            ExecutionReport r = mover.tick(world, directive, actor);
            if (r.status() == ExecutionReport.Status.FAILED
                && "STUCK".equals(r.reason())) {
                stuckReports++;
            }
        }
        assertEquals(1, stuckReports,
            "exactly one FAILED(STUCK) when recovery fails");
        assertTrue(actor.flush() != null);
    }

    /**
     * While executing, the mover owns MOVE and ROT claims each tick.
     * Ticks past the P2.1 departure hold first - the pin is about
     * steady execution, not the first tick.
     */
    @Test
    void moverClaimsMoveAndRotChannels() {
        Vec3[] position = {at(0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(30, 64, 0)));

        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS;
             i++) {
            mover.tick(world, directive, actor);
        }
        var winners = actor.flush();
        assertTrue(winners.containsKey(Channel.MOVE));
        assertEquals("mover", winners.get(Channel.MOVE).holder());
        assertEquals("mover", winners.get(Channel.ROT).holder());
    }

    /**
     * Push-off recovery, stage-1 slice acceptance carried forward: an
     * external shove inside the window is displacement, not stalling -
     * the fuse stays silent and the mover keeps claiming.
     */
    @Test
    void shoveInsideWindowDoesNotTripFuse() {
        Vec3[] position = {at(0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(50, 64, 0)));

        for (int round = 0; round < 3; round++) {
            int base = round * 2;
            position[0] = at(base + 0.5);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(world, directive, actor).status());
            position[0] = at(base + 1.5);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(world, directive, actor).status());
            position[0] = at(base + 0.5);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(world, directive, actor).status(),
                "a shove is displacement, never STUCK");
        }
    }
}
