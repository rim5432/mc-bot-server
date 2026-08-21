package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stage-0 stuck-fuse behavior of the mover shell: one STUCK verdict
 * when the displacement window goes flat, no duplicate trips while
 * latched, re-arm on real movement, SUCCESS still decided by the goal
 * predicate alone.
 *
 * <p>Contract: see ADR-0004 D3 (stuck detection lives inside behaviors).
 */
class StuckFuseTest {

    private static final MockWorldView WORLD = new MockWorldView();

    /**
     * Flat window trips exactly once; movement re-arms; arrival wins.
     */
    @Test
    void flatWindowTripsOnceThenReArms() {
        CellPos[] pose = {new CellPos(0, 64, 0)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0]);
        ChannelArbiter actor = new ChannelArbiter();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(50, 64, 0)));

        ExecutionReport.Status last = null;
        int stuckReports = 0;
        for (int i = 1; i <= PathingBehavior.STUCK_WINDOW + 3; i++) {
            ExecutionReport r = mover.tick(WORLD, directive, actor);
            last = r.status();
            if (last == ExecutionReport.Status.STUCK) {
                stuckReports++;
            }
        }
        assertEquals(ExecutionReport.Status.RUNNING, last,
            "after tripping once the shell keeps trying");
        assertEquals(1, stuckReports,
            "the fuse must not machine-gun STUCK every tick");

        // Real movement resumes: latch clears on the next tick.
        pose[0] = new CellPos(10, 64, 0);
        assertEquals(ExecutionReport.Status.RUNNING,
            mover.tick(WORLD, directive, actor).status());
        assertEquals(ExecutionReport.Status.RUNNING,
            mover.tick(WORLD, directive, actor).status(),
            "moving bot must stay unstuck");

        // Arrival overrides everything: predicate decides SUCCESS.
        pose[0] = new CellPos(50, 64, 0);
        assertEquals(ExecutionReport.Status.SUCCESS,
            mover.tick(WORLD, directive, actor).status());
    }

    /**
     * While executing, the mover owns MOVE and ROT claims each tick.
     */
    @Test
    void moverClaimsMoveAndRotChannels() {
        CellPos[] pose = {new CellPos(0, 64, 0)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0]);
        ChannelArbiter actor = new ChannelArbiter();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(30, 64, 0)));

        mover.tick(WORLD, directive, actor);
        var winners = actor.flush();
        assertEquals("mover", winners.get(Channel.MOVE).holder());
        assertEquals("mover", winners.get(Channel.ROT).holder());
    }

    /**
     * Push-off recovery, stage-1 slice acceptance: an external shove
     * inside the window is displacement, not stalling — the fuse must
     * stay silent and the mover keeps claiming toward the goal.
     */
    @Test
    void shoveInsideWindowDoesNotTripFuse() {
        CellPos[] pose = {new CellPos(0, 64, 0)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> pose[0]);
        ChannelArbiter actor = new ChannelArbiter();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(50, 64, 0)));

        // Walk a little, get shoved back, walk again - repeatedly,
        // across more than one full window of ticks.
        for (int round = 0; round < 3; round++) {
            int base = round * 2;
            pose[0] = new CellPos(base, 64, 0);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(WORLD, directive, actor).status());
            pose[0] = new CellPos(base + 1, 64, 0);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(WORLD, directive, actor).status());
            // Shove back to the round's start before the next round.
            pose[0] = new CellPos(base, 64, 0);
            assertEquals(ExecutionReport.Status.RUNNING,
                mover.tick(WORLD, directive, actor).status(),
                "a shove is displacement, never STUCK");
        }
    }
}
