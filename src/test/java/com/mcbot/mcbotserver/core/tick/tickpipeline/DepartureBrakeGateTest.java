package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player-feel claim-timing pins (issue 0005 P2.1/P2.2): the
 * departure hold delays the first drive claim of a new goal by
 * {@link PathingBehavior#DEPARTURE_DELAY_TICKS} ticks, a goal change
 * re-arms it, and the arrival brake scales drive down toward the
 * terminal waypoint without ever dropping below
 * {@link PathingBehavior#ARRIVE_MIN_DRIVE}.
 */
class DepartureBrakeGateTest {

    private static MockWorldView floorTo(int xLen) {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= xLen; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new BlockSnapshot(
                    new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static Intent.Move lastMoveClaim(RecordingActor actor) {
        return actor.submitted.stream()
            .filter(c -> c.channel() == Channel.MOVE)
            .map(c -> (Intent.Move) c.intent())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no MOVE claim"));
    }

    private static boolean hasMoveClaim(RecordingActor actor) {
        return actor.submitted.stream()
            .anyMatch(c -> c.channel() == Channel.MOVE);
    }

    /**
     * The first DEPARTURE_DELAY_TICKS ticks of a mission submit no
     * drive claim; the tick after the hold drains, steering starts.
     * Planning runs during the hold (the plan is adopted on tick 1),
     * so only the claims wait.
     */
    @Test
    void departureHoldDelaysFirstDriveClaim() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        for (int i = 0; i < PathingBehavior.DEPARTURE_DELAY_TICKS; i++) {
            mover.tick(world, directive, actor);
            assertFalse(hasMoveClaim(actor),
                "hold tick " + (i + 1) + " must not steer yet");
        }
        assertEquals(2, PathingTestAccess.waypoints(mover).size(),
            "the smoothed plan is adopted during the hold");

        mover.tick(world, directive, actor);
        assertTrue(hasMoveClaim(actor),
            "the tick after the hold must steer");
    }

    /**
     * A goal change mid-mission re-arms the hold (the player-stops-
     * and-redecides read); ticking the SAME goal does not.
     */
    @Test
    void goalChangeRearmsDepartureHold() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive first = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS; i++) {
            mover.tick(world, first, actor);
        }
        assertTrue(hasMoveClaim(actor));

        actor.submitted.clear();
        mover.tick(world, first, actor);
        assertTrue(hasMoveClaim(actor),
            "same goal: no re-arm, claims keep flowing");

        actor.submitted.clear();
        mover.tick(world, Directive.of(
            new GoalBlock(new CellPos(20, 64, 0))), actor);
        assertFalse(hasMoveClaim(actor),
            "goal change must re-arm the departure hold");
    }

    /**
     * Inside BRAKE_DISTANCE the drive scales with remaining distance
     * (2.6 m to go -> forward 2.6/3); far away it stays exactly 1.0.
     */
    @Test
    void arrivalBrakeScalesDriveWithDistance() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS; i++) {
            mover.tick(world, directive, actor);
        }
        assertEquals(1.0, lastMoveClaim(actor).forward(), 1e-9,
            "far from the goal the stride is full");

        position[0] = new Vec3(37.9, 64, 0.5);
        mover.tick(world, directive, actor);
        assertEquals(2.6 / PathingBehavior.BRAKE_DISTANCE,
            lastMoveClaim(actor).forward(), 1e-6,
            "drive scales with remaining distance inside the brake "
                + "window");
    }

    /**
     * The brake floor: at one block out the scaled drive falls below
     * the ratio and must clamp at ARRIVE_MIN_DRIVE, never zero, or
     * the body would stall short of the goal predicate with nothing
     * pushing it in.
     */
    @Test
    void brakeNeverStallsShortOfGoal() {
        Vec3[] position = {new Vec3(39.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = floorTo(60);
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(40, 64, 0)));

        for (int i = 0; i <= PathingBehavior.DEPARTURE_DELAY_TICKS; i++) {
            mover.tick(world, directive, actor);
        }
        assertEquals(PathingBehavior.ARRIVE_MIN_DRIVE,
            lastMoveClaim(actor).forward(), 1e-9,
            "drive must floor at ARRIVE_MIN_DRIVE near the goal");
    }
}
