package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.core.process.MissionShell;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Shared lifecycle scaffold for the task-verb handlers (goto, dig,
 * mine, attack): the missions map keyed by boundary-D task id, the bus
 * registration, the retire-before-announce sweep, the stop-all sweep,
 * and the cancel hook with its TASK_CANCELLED push. Argument parsing,
 * validation, per-verb default budgets, and extra disclosures (the dig
 * BLOCK_BROKEN events, the mine break drain) stay with each subclass.
 *
 * <p>Contract: see boundaries.md decision 18 (task verbs ride the
 * CommandBus; submit/cancel/status seams) and decision 28 (verb table
 * grows by issue). Cancel routing is the wiring's job: the bus holds
 * ONE listener slot, so the composition root installs one router
 * fanning out to every handler's {@code onCancel}; single-handler
 * wirings install their handler directly.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 18 (task verbs ride CommandBus)
public abstract class VerbTaskHandler<P extends MissionShell> {

    private final TaskArbiter arbiter;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private final Map<String, P> missions = new HashMap<>();
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
    protected VerbTaskHandler(
            TaskArbiter arbiter, EventQueue events, LongSupplier daySupplier, LongSupplier timeOfDaySupplier) {
        CommandHandlerGuards.requireNonNullArgs(arbiter, events, daySupplier, timeOfDaySupplier);
        this.arbiter = arbiter;
        this.events = events;
        this.daySupplier = daySupplier;
        this.timeOfDaySupplier = timeOfDaySupplier;
    }

    /**
     * Wire name of the verb this handler owns.
     *
     * @return the Brigadier-literal verb string; never null or blank
     */
    protected abstract String verb();

    /**
     * Argument verdict for a submit attempt.
     *
     * @param command the incoming command; never null
     * @return null when the arguments parse, else the wire rejection
     *         reason
     */
    protected abstract String validate(BotCommand command);

    /**
     * Builds one mission from (pre-validated) command arguments.
     *
     * @param taskId  boundary-D task id from the bus; never null
     * @param command the incoming command; never null
     * @return the registered mission; never null
     */
    protected abstract P createMission(String taskId, BotCommand command);

    /**
     * Wire the verb onto the bus. Cancel routing is NOT installed
     * here - the composition root owns the one listener slot (see
     * class doc).
     *
     * @param bus the command channel to attach to; never null
     */
    public void attach(CommandBus bus) {
        CommandHandlerGuards.requireBus(bus);
        this.bus = bus;
        bus.register(verb(), new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return VerbTaskHandler.this.validate(command);
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                P mission = createMission(taskId, command);
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    /**
     * Per-tick sweep: run subclass pre-sweep work (pending break
     * drains), then retire finished missions. Terminal processes are
     * tiny, but a long-running harness submits thousands of tasks -
     * without this sweep the map grows forever.
     *
     * <p>Retire-before-announce: a mission that died via the arbiter's
     * retirement lap (TIMEOUT / STUCK) had its verdict announced
     * outside the bus, so the bus's dedupe window would otherwise hold
     * the dead id forever and replay every harness retry.
     */
    public void tick() {
        beforeSweep();
        missions.values().removeIf(m -> {
            if (m.isActive()) {
                return false;
            }
            bus.retire(m.missionTaskId());
            onRetired(m);
            return true;
        });
    }

    /**
     * Subclass hook running before the retirement sweep each tick.
     */
    protected void beforeSweep() {}

    /**
     * Subclass hook for per-mission disclosures on retirement, called
     * after the bus dedupe window closes.
     *
     * @param mission the finished mission leaving the map; never null
     */
    protected void onRetired(P mission) {}

    /**
     * Aborts and forgets the mission registered under {@code taskId},
     * then reports {@code TASK_CANCELLED} on the event stream. Unknown
     * task ids are ignored.
     *
     * @param taskId identifier of the mission to tear down; never null
     * @param verb   wire name of the cancelled command as reported by
     *               the dispatcher; kept for callback-signature
     *               symmetry and not read here
     */
    public void onCancel(String taskId, String verb) {
        P mission = missions.remove(taskId);
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
                    Map.of("task", mission.displayName(), "taskId", mission.missionTaskId()),
                    mission.displayName() + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
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
     * Live missions still tracked by this handler.
     *
     * @return the task-id-keyed mission map; never null
     */
    protected final Map<String, P> missions() {
        return missions;
    }

    /**
     * The event stream for subclass disclosures.
     *
     * @return the wired event queue; never null
     */
    protected final EventQueue events() {
        return events;
    }

    /**
     * Game-day stamp accessor for subclass disclosures.
     *
     * @return the wired day supplier; never null
     */
    protected final LongSupplier daySupplier() {
        return daySupplier;
    }

    /**
     * Time-of-day stamp accessor for subclass disclosures.
     *
     * @return the wired time-of-day supplier; never null
     */
    protected final LongSupplier timeOfDaySupplier() {
        return timeOfDaySupplier;
    }
}
