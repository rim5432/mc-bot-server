package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Reflective access to PathingBehavior's private mid-drive state
 * for the tickpipeline integration gates - and nothing else.
 * Collaborator-level unit tests live same-package under
 * core.behavior and call package-private members directly
 * (code-health.md H-R1): a moved class must surface as a compile
 * error, never as a runtime ClassNotFoundException from an FQN
 * string. Routing through this file (two-hop: mover, then
 * collaborator field, then target) is reserved for assertions
 * that must inspect mid-drive state from outside the package.
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
}
