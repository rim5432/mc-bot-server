package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    // Set in attach(); the lifecycle sweep needs the bus to close
    // dedupe windows for deaths announced outside it.
    private CommandBus bus;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param arbiter           mission selector; never null
     * @param events            completion/cancellation stream; never null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never null
     */
    public GotoCommandHandler(
            TaskArbiter arbiter, EventQueue events, LongSupplier daySupplier, LongSupplier timeOfDaySupplier) {
        CommandHandlerGuards.requireNonNullArgs(arbiter, events, daySupplier, timeOfDaySupplier);
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
        CommandHandlerGuards.requireBus(bus);
        this.bus = bus;
        // Single-handler setups self-register here; BotAssembly
        // installs the combined router after both handlers attach,
        // which overrides this for the full pipeline.
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
                GotoProcess mission = new GotoProcess(taskId, parsed.goal(), 50, parsed.timeoutTicks());
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    /**
     * Retire finished missions from the tracking map. Terminal
     * processes are tiny, but a long-running harness submits thousands
     * of gotos — without this sweep the map grows forever.
     *
     * <p>Called once per server tick by the wiring (McBotServer), or
     * directly by tests; safe to call at any cadence.
     */
    public void tick() {
        // Retire-before-sweep: a mission that died via the arbiter's
        // retirement lap (TIMEOUT / STUCK) had its verdict announced
        // outside the bus, so the bus's dedupe window would otherwise
        // hold the dead id forever and replay every harness retry.
        missions.values().removeIf(m -> {
            if (m.isActive()) {
                return false;
            }
            bus.retire(m.missionTaskId());
            return true;
        });
    }

    /**
     * One-line workload summary for the state snapshot channel.
     *
     * @return display name of one live mission, or "idle" when none;
     *         never null
     */
    public String activeTaskSummary() {
        var any = missions.values().iterator();
        return any.hasNext() ? any.next().displayName() : "idle";
    }

    /**
     * Cancels every live mission through the bus, so each cancel
     * closes its idempotency window exactly like a targeted
     * {@code /bot cancel} does. Backs the human-facing
     * {@code /bot stop}.
     *
     * @return number of missions actually cancelled
     */
    public int stopAll() {
        List<String> ids = List.copyOf(missions.keySet());
        int cancelled = 0;
        for (String id : ids) {
            if (bus.cancel(id)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Aborts and forgets the goto mission registered under {@code taskId},
     * then reports {@code TASK_CANCELLED} on the event stream. Unknown
     * task ids are ignored.
     *
     * @param taskId identifier of the mission to tear down; never null
     * @param verb   wire name of the cancelled command as reported by the
     *               dispatcher; kept for callback-signature symmetry and
     *               not read here
     */
    public void onCancel(String taskId, String verb) {
        GotoProcess mission = missions.remove(taskId);
        if (mission == null) {
            return;
        }
        mission.abort();
        try {
            events.push(new BotEvent(
                    EventKind.TASK_CANCELLED,
                    daySupplier.getAsLong(),
                    timeOfDaySupplier.getAsLong(),
                    false,
                    Map.of(
                            "task", mission.displayName(),
                            "taskId", mission.missionTaskId()),
                    mission.displayName() + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private ParsedGoto parse(BotCommand command) {
        Map<String, String> args = command.args();
        int x = numeric(args, "x", null, Integer::parseInt);
        int y = numeric(args, "y", null, Integer::parseInt);
        int z = numeric(args, "z", null, Integer::parseInt);
        int tolerance = numeric(args, "tolerance", 0, Integer::parseInt);
        if (tolerance < 0) {
            throw new IllegalArgumentException("tolerance must not be negative");
        }
        long timeout = numeric(args, "timeoutTicks", DEFAULT_TIMEOUT_TICKS, Long::parseLong);
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        CellPos target = new CellPos(x, y, z);
        Goal goal = tolerance == 0 ? new GoalBlock(target) : new GoalNear(target, tolerance);
        return new ParsedGoto(goal, timeout);
    }

    /**
     * Parse one numeric argument. A {@code null} fallback marks the
     * argument required: absence rejects the command instead of
     * defaulting.
     *
     * @param args     the validated command arguments; never null
     * @param key      argument name used verbatim in error text
     * @param fallback value returned when the key is absent, or
     *                 {@code null} to make the argument mandatory
     * @param parse    conversion applied to the trimmed raw string;
     *                 must throw {@code NumberFormatException} on bad
     *                 input
     * @return the parsed number, never null when fallback is non-null
     * @throws IllegalArgumentException when a required arg is missing
     *         or the raw value does not parse
     */
    private <N> N numeric(Map<String, String> args, String key, N fallback, Function<String, N> parse) {
        String raw = args.get(key);
        if (raw == null) {
            if (fallback == null) {
                throw new IllegalArgumentException("missing arg: " + key);
            }
            return fallback;
        }
        try {
            return parse.apply(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("arg " + key + " is not an integer: " + raw);
        }
    }

    private record ParsedGoto(Goal goal, long timeoutTicks) {}
}
