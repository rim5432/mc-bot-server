package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;

import java.lang.reflect.Field;
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

    /** Cursor position within the plan. */
    static int waypointIndex(PathingBehavior mover) {
        return (int) cursorField(mover, "waypointIndex");
    }

    /** Force the cursor to a cell - climb-waypoint steering tests. */
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
        return (int) hostField(mover, "ticksSincePlan");
    }

    /** Ticks since the last plan-progress criterion fired. */
    static int ticksSincePlanProgress(PathingBehavior mover) {
        return (int) fuseField(mover, "ticksSincePlanProgress");
    }

    private static Object fuseField(PathingBehavior mover,
                                    String name) {
        try {
            Field f = PathingBehavior.class.getDeclaredField("fuse");
            f.setAccessible(true);
            Object fuse = f.get(mover);
            Field inner = fuse.getClass().getDeclaredField(name);
            inner.setAccessible(true);
            return inner.get(fuse);
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
