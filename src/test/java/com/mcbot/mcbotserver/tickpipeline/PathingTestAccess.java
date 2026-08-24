package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Centralised reflective access to PathingBehavior's private state
 * for the tickpipeline gates. Every field move inside the follower
 * (state extracted into collaborators) retargets exactly this file,
 * not a dozen tests.
 *
 * <p>Package-private test infrastructure; not part of any boundary.
 */
final class PathingTestAccess {

    private PathingTestAccess() {
    }

    /** The active plan chain. */
    static List<CellPos> waypoints(PathingBehavior mover) {
        return (List<CellPos>) cursorField(mover, "waypoints");
    }

    /**
     * Steer pitch toward one waypoint - invokes the package-private
     * static directly so the clamp math is unit-pinnable without
     * fabricating a plan shape the planner may not produce.
     */
    static float steerPitch(Vec3 position, CellPos wp) {
        try {
            Method m = PathingBehavior.class.getDeclaredMethod(
                "steerPitch", Vec3.class, CellPos.class);
            m.setAccessible(true);
            return (Float) m.invoke(null, position, wp);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cursor position within the plan. */
    static int waypointIndex(PathingBehavior mover) {
        return (int) cursorField(mover, "waypointIndex");
    }

    /** Force the cursor to a cell - JumpUp-waypoint steering tests. */
    static void writeWaypointIndex(PathingBehavior mover, int value) {
        try {
            Field f = cursorOf(mover).getClass()
                .getDeclaredField("waypointIndex");
            f.setAccessible(true);
            f.set(cursorOf(mover), value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ticks since the last replan request. */
    static int ticksSincePlan(PathingBehavior mover) {
        return (int) collaboratorField(mover, "gate", "ticksSincePlan");
    }

    /** Ticks since the last plan-progress criterion fired. */
    static int ticksSincePlanProgress(PathingBehavior mover) {
        return (int) collaboratorField(mover, "fuse",
            "ticksSincePlanProgress");
    }

    private static Object collaboratorField(PathingBehavior mover,
                                            String holderField,
                                            String name) {
        try {
            Field hf = PathingBehavior.class.getDeclaredField(holderField);
            hf.setAccessible(true);
            Object holder = hf.get(mover);
            Field f = holder.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(holder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object cursorField(PathingBehavior mover,
                                      String name) {
        try {
            Field f = cursorOf(mover).getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(cursorOf(mover));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object cursorOf(PathingBehavior mover) {
        try {
            Field f = PathingBehavior.class.getDeclaredField("cursor");
            f.setAccessible(true);
            return f.get(mover);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object hostField(PathingBehavior mover,
                                    String name) {
        try {
            Field f = PathingBehavior.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(mover);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
