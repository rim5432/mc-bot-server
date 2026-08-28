package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.AttackProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Boundary-D verb wiring for "attack": one named entity becomes an
 * {@link AttackProcess} on the arbiter; cancellation aborts the
 * mission; the pipeline's generic TerminalMission path emits the
 * verdict (verdict attrs carry the targetId). The task-verb twin of
 * the dig handler - multi-tick execution, CommandBus submission,
 * one row of growth per the decision-28 discipline.
 *
 * <p>Contract: see boundaries.md decision 18's goto shape (seam
 * verbs stay submit/cancel/status; the task rides CommandBus) and
 * ledger 39 (the harness-directed engagement row).
 */
// contract: see boundaries.md decision 28 (verb table grows by issue)
public final class AttackCommandHandler {

    /** Default mission budget when the harness omits it (1 minute). */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    private final TaskArbiter arbiter;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private final Supplier<CellPos> positionSource;
    private final Map<String, AttackProcess> missions = new HashMap<>();
    private CommandBus bus;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param arbiter           mission selector; never null
     * @param events            completion/cancellation stream; never
     *                          null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never
     *                          null
     * @param positionSource    body cell accessor feeding the scan
     *                          center; never null
     */
    public AttackCommandHandler(
            TaskArbiter arbiter,
            EventQueue events,
            LongSupplier daySupplier,
            LongSupplier timeOfDaySupplier,
            Supplier<CellPos> positionSource) {
        CommandHandlerGuards.requireNonNullArgs(arbiter, events, daySupplier, timeOfDaySupplier);
        if (positionSource == null) {
            throw new IllegalArgumentException("positionSource must not be null");
        }
        this.arbiter = arbiter;
        this.events = events;
        this.daySupplier = daySupplier;
        this.timeOfDaySupplier = timeOfDaySupplier;
        this.positionSource = positionSource;
    }

    /**
     * Wire the verb and its cancel hook onto the bus.
     *
     * @param bus the command channel to attach to; never null
     */
    public void attach(CommandBus bus) {
        CommandHandlerGuards.requireBus(bus);
        this.bus = bus;
        bus.setCancelListener(this::onCancel);
        bus.register("attack", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return validateArgs(command) ? null : "attack wants a non-blank target id [timeoutTicks]";
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                Map<String, String> args = command.args();
                long timeout = args.containsKey("timeoutTicks")
                        ? Long.parseLong(args.get("timeoutTicks"))
                        : DEFAULT_TIMEOUT_TICKS;
                AttackProcess mission = new AttackProcess(taskId, args.get("targetId"), 50, timeout, positionSource);
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    /**
     * Retire finished missions. Called once per server tick by the
     * wiring, same cadence as the dig handler's sweep.
     */
    public void tick() {
        // Retire before the bus dedupe window closes so a harness
        // retry of the same verb+args submits fresh; no extra
        // disclosure event - the pipeline's generic terminal path
        // carries the verdict attrs (targetId).
        missions.values().removeIf(m -> {
            if (m.isActive()) {
                return false;
            }
            bus.retire(m.missionTaskId());
            return true;
        });
    }

    /**
     * Cancel hook, routed by the McBotServer cancel router (the bus
     * has ONE listener slot; every verb handler's cancel method is
     * public and self-guards by its missions map).
     *
     * @param taskId identifier of the mission to tear down; unknown
     *               ids are ignored, never null
     * @param verb   wire name of the cancelled command as reported by
     *               the dispatcher; kept for callback-signature
     *               symmetry
     */
    public void onCancel(String taskId, String verb) {
        AttackProcess mission = missions.remove(taskId);
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
                    Map.of("task", "attack:" + taskId, "taskId", taskId),
                    "attack:" + taskId + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private static boolean validateArgs(BotCommand command) {
        Map<String, String> args = command.args();
        if (!args.containsKey("targetId")
                || args.get("targetId") == null
                || args.get("targetId").isBlank()) {
            return false;
        }
        if (args.containsKey("timeoutTicks")) {
            try {
                return Long.parseLong(args.get("timeoutTicks")) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
