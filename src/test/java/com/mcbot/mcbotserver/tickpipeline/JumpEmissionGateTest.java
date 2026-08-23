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

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Climb-execution gate: the MOVE claim carries jump=true exactly when
 * the current waypoint sits above the body's floor cell - the executor
 * half of A*'s ClimbUp edges. Auto-step cannot clear a full block;
 * without this flag every planned climb stalls into a progress-fuse
 * STUCK at the step face while the plan says the route is viable.
 */
class JumpEmissionGateTest {

    private static MockWorldView worldWithPlatform() {
        MockWorldView world = new MockWorldView();
        for (int x = 0; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new BlockSnapshot(
                    new CellPos(x, 63, z), "minecraft:smooth_stone"));
            }
        }
        // One-block plateau at x=2..3: its walk surface is y=65, one
        // full block above the floor lane, reachable only via a
        // ClimbUp edge.
        for (int x = 2; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new BlockSnapshot(
                    new CellPos(x, 64, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static PathingBehavior newMover(Vec3[] position) {
        return new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
    }

    private static Intent.Move lastMoveClaim(RecordingActor actor) {
        return actor.submitted.stream()
            .filter(c -> c.channel() == Channel.MOVE)
            .map(c -> (Intent.Move) c.intent())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no MOVE claim"));
    }

    /**
     * Steering a flat waypoint never jumps - the flag must stay
     * silent on level ground or every slab carpet would hop.
     */
    @Test
    void flatWaypointSteersWithoutJump()
        throws ReflectiveOperationException {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = newMover(position);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(7, 64, 0)));

        mover.tick(worldWithPlatform(), directive, actor);

        assertFalse(lastMoveClaim(actor).jump(),
            "a same-level waypoint must not set the jump flag");
    }

    /**
     * The plan onto the plateau contains a ClimbUp cell; steering at
     * that waypoint raises the jump flag. Pins the executor half of
     * the climb vocabulary against silent regression to jump=false.
     */
    @Test
    void climbWaypointSteersWithJump()
        throws ReflectiveOperationException {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = newMover(position);
        RecordingActor actor = new RecordingActor();
        Directive directive = Directive.of(
            new GoalBlock(new CellPos(3, 65, 0)));

        MockWorldView world = worldWithPlatform();
        mover.tick(world, directive, actor);
        assertFalse(lastMoveClaim(actor).jump(),
            "the approach waypoint is flat; no jump yet");

        List<CellPos> waypoints = PathingTestAccess.waypoints(mover);
        int climbIndex = waypoints.indexOf(new CellPos(2, 65, 0));
        assertTrue(climbIndex >= 0,
            "plan must route through the plateau edge cell, got "
            + waypoints);

        PathingTestAccess.writeWaypointIndex(mover, climbIndex);
        actor.submitted.clear();
        mover.tick(world, directive, actor);

        assertTrue(lastMoveClaim(actor).jump(),
            "steering at the climb waypoint must raise the jump flag");
        assertEquals(1.0f, lastMoveClaim(actor).forward(),
            "climb keeps full forward drive");
    }
}
