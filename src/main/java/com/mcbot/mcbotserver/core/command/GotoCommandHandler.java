package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Boundary-D verb wiring for decision 18: "goto" turns a target cell
 * into a GotoProcess on the arbiter, cancellation aborts the mission,
 * and completion events flow through the pipeline's transition
 * detector.
 *
 * <p>Contract: see boundaries.md decision 18 (GotoCommand{target,
 * tolerance, timeoutTicks}; seam verbs stay submit/cancel/status).
 * Default budget is one minute at 20 tps; tolerance zero means exact
 * GoalBlock, positive means Chebyshev GoalNear.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 18 (first boundary-D semantics)
public final class GotoCommandHandler {

    /** Default mission budget in ticks when the harness omits it. */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    private final TaskArbiter arbiter;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private final Map<String, GotoProcess> missions = new HashMap<>();

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param arbiter           mission selector; never null
     * @param events            completion/cancellation stream; never null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never null
     */
    public GotoCommandHandler(TaskArbiter arbiter, EventQueue events,
                              LongSupplier daySupplier,
                              LongSupplier timeOfDaySupplier) {
        if (arbiter == null || events == null || daySupplier == null
            || timeOfDaySupplier == null) {
            throw new IllegalArgumentException(
                "arguments must not be null");
        }
        this.arbiter = arbiter;
        this.events = events;
        this.daySupplier = daySupplier;
        this.timeOfDaySupplier = timeOfDaySupplier;
    }

    /**
     * Wire the verb and its cancel hook onto the bus.
     *
     * @param bus the command channel to attach to; never null
     */
    public void attach(CommandBus bus) {
        if (bus == null) {
            throw new IllegalArgumentException("bus must not be null");
        }
        bus.setCancelListener(this::onCancel);
        bus.register("goto", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                try {
                    parse(command);
                    return null;
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                ParsedGoto parsed = parse(command);
                GotoProcess mission = new GotoProcess(taskId,
                    parsed.goal(), 50, parsed.timeoutTicks());
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    private void onCancel(String taskId, String verb) {
        GotoProcess mission = missions.remove(taskId);
        if (mission == null) {
            return;
        }
        mission.abort();
        try {
            events.push(new BotEvent(EventKind.TASK_CANCELLED,
                daySupplier.getAsLong(), timeOfDaySupplier.getAsLong(),
                false, Map.of("task", mission.displayName()),
                mission.displayName() + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private ParsedGoto parse(BotCommand command) {
        Map<String, String> args = command.args();
        int x = requireInt(args, "x");
        int y = requireInt(args, "y");
        int z = requireInt(args, "z");
        int tolerance = optionalInt(args, "tolerance", 0);
        if (tolerance < 0) {
            throw new IllegalArgumentException(
                "tolerance must not be negative");
        }
        long timeout = optionalLong(args, "timeoutTicks",
            DEFAULT_TIMEOUT_TICKS);
        if (timeout <= 0) {
            throw new IllegalArgumentException(
                "timeoutTicks must be positive");
        }
        CellPos target = new CellPos(x, y, z);
        Goal goal = tolerance == 0
            ? new GoalBlock(target)
            : new GoalNear(target, tolerance);
        return new ParsedGoto(goal, timeout);
    }

    private int requireInt(Map<String, String> args, String key) {
        String raw = args.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("missing arg: " + key);
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "arg " + key + " is not an integer: " + raw);
        }
    }

    private int optionalInt(Map<String, String> args, String key,
                            int fallback) {
        String raw = args.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "arg " + key + " is not an integer: " + raw);
        }
    }

    private long optionalLong(Map<String, String> args, String key,
                              long fallback) {
        String raw = args.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "arg " + key + " is not an integer: " + raw);
        }
    }

    private record ParsedGoto(Goal goal, long timeoutTicks) {
    }
}
