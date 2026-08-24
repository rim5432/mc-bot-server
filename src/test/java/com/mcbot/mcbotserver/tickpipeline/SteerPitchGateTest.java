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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain-following steer pitch gate (issue 0005 P0): the ROT claim's
 * pitch looks along the path - 0 on flat legs, negative (up) onto
 * JumpUp steps, positive (down) on drops, clamped at
 * {@link PathingBehavior#STEER_PITCH_LIMIT_DEG} for near-vertical
 * legs. Before P0 the pitch was hard-coded 0 and the body read as a
 * device sliding on rails.
 */
class SteerPitchGateTest {

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

    private static MockWorldView worldWithPlatform() {
        MockWorldView world = floorTo(8);
        for (int x = 2; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                world.putBlock(new BlockSnapshot(
                    new CellPos(x, 64, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static Intent.Look lastLookClaim(RecordingActor actor) {
        return actor.submitted.stream()
            .filter(c -> c.channel() == Channel.ROT)
            .map(c -> (Intent.Look) c.intent())
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no ROT claim"));
    }

    /**
     * A flat leg pitches exactly 0 - the pre-P0 behaviour must hold
     * wherever the terrain is level, so the change stays invisible
     * on plains and only shows on vertical structure.
     */
    @Test
    void flatWaypointLooksLevel() {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();

        mover.tick(floorTo(8), Directive.of(
            new GoalBlock(new CellPos(7, 64, 0))), actor);

        assertEquals(0f, lastLookClaim(actor).pitchDeg(), 1e-6,
            "a same-level waypoint must pitch exactly 0");
    }

    /**
     * Steering at a JumpUp waypoint looks UP at it, feet-to-feet
     * geometry: body feet (0.5, 64, 0.5), waypoint cell (2, 65, 0)
     * with its centre 2.0 blocks ahead -> pitch -atan2(1, 2). The
     * exact value pins both the feet-to-feet frame (eye heights
     * cancel) and the engine sign (negative = up).
     */
    @Test
    void jumpUpWaypointLooksUpAtExactAngle()
        throws ReflectiveOperationException {
        Vec3[] position = {new Vec3(0.5, 64, 0.5)};
        PathingBehavior mover = new PathingBehavior("mover",
            () -> position[0], BasicMoves::from);
        RecordingActor actor = new RecordingActor();

        MockWorldView world = worldWithPlatform();
        mover.tick(world, Directive.of(
            new GoalBlock(new CellPos(3, 65, 0))), actor);

        List<CellPos> waypoints = PathingTestAccess.waypoints(mover);
        int jumpUpIndex = waypoints.indexOf(new CellPos(2, 65, 0));
        assertTrue(jumpUpIndex >= 0,
            "plan must route through the plateau edge cell, got "
                + waypoints);

        PathingTestAccess.writeWaypointIndex(mover, jumpUpIndex);
        actor.submitted.clear();
        mover.tick(world, Directive.of(
            new GoalBlock(new CellPos(3, 65, 0))), actor);

        assertEquals((float) -Math.toDegrees(Math.atan2(1.0, 2.0)),
            lastLookClaim(actor).pitchDeg(), 0.01,
            "JumpUp steering pitch must be -atan2(dy=1, dx=2)");
    }

    /**
     * Clamp math without a fabricated plan: a waypoint 3 up and 1
     * across reads -71.6 unclamped and must stop at -60; the mirror
     * case downward clamps at +60 (drops of 3 with the waypoint one
     * cell ahead exist in real plans).
     */
    @Test
    void steepWaypointsClampToLimit() {
        Vec3 position = new Vec3(0.5, 64, 0.5);
        assertEquals(-PathingBehavior.STEER_PITCH_LIMIT_DEG,
            PathingTestAccess.steerPitch(position,
                new CellPos(1, 67, 0)), 0.01,
            "near-vertical ascent clamps at the up limit");
        assertEquals(PathingBehavior.STEER_PITCH_LIMIT_DEG,
            PathingTestAccess.steerPitch(position,
                new CellPos(1, 61, 0)), 0.01,
            "near-vertical descent clamps at the down limit");
        assertEquals(0f,
            PathingTestAccess.steerPitch(position,
                new CellPos(1, 64, 0)), 1e-6,
            "level waypoint reads exactly 0");
    }
}
