package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DigProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Boundary-D verb wiring for "dig" (issue 0013 R1): one block cell
 * becomes a {@link DigProcess} on the arbiter; cancellation aborts
 * the mission; completion events flow through the pipeline's generic
 * TerminalMission path plus the BLOCK_BROKEN disclosure this handler
 * emits on its retirement sweep (absorbing 0009 F3).
 *
 * <p>Contract: see boundaries.md decision 18's goto shape (seam verbs
 * stay submit/cancel/status; the task rides CommandBus) and issue
 * 0013 R1 (dig is a task because the executor is multi-tick and the
 * INTERACT claim expires every tick).
 */
// contract: see boundaries.md decision 28 (verb table grows by issue)
//            + issue 0013 R1 (dig task semantics)
public final class DigCommandHandler {

    /** Default mission budget when the harness omits it (1 minute). */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    private final TaskArbiter arbiter;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private final Map<String, DigProcess> missions = new HashMap<>();
    private CommandBus bus;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param arbiter           mission selector; never null
     * @param events            completion/cancellation stream;
     *                          never null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never null
     */
    public DigCommandHandler(
            TaskArbiter arbiter, EventQueue events, LongSupplier daySupplier, LongSupplier timeOfDaySupplier) {
        if (arbiter == null || events == null || daySupplier == null || timeOfDaySupplier == null) {
            throw new IllegalArgumentException("arguments must not be null");
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
        this.bus = bus;
        // Single-handler setups self-register here; BotAssembly
        // installs the combined router after both handlers attach,
        // which overrides this for the full pipeline.
        bus.setCancelListener(this::onCancel);
        bus.register("dig", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return validateArgs(command) ? null : "dig wants integer args x y z [timeoutTicks]";
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                Map<String, String> args = command.args();
                long timeout = args.containsKey("timeoutTicks")
                        ? Long.parseLong(args.get("timeoutTicks"))
                        : DEFAULT_TIMEOUT_TICKS;
                DigProcess mission = new DigProcess(
                        taskId,
                        new CellPos(
                                Integer.parseInt(args.get("x")),
                                Integer.parseInt(args.get("y")),
                                Integer.parseInt(args.get("z"))),
                        50,
                        timeout);
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    /**
     * Retire finished missions and announce BLOCK_BROKEN for
     * completed digs. Called once per server tick by the wiring, same
     * cadence as the goto handler's sweep.
     */
    public void tick() {
        // Retire-before-announce: the bus dedupe window closes first
        // so a harness retry of the same verb+args submits fresh.
        missions.values().removeIf(m -> {
            if (m.isActive()) {
                return false;
            }
            bus.retire(m.missionTaskId());
            if (m.missionSucceeded()) {
                pushBlockBroken(m);
            }
            return true;
        });
    }

    private void pushBlockBroken(DigProcess mission) {
        try {
            var attrs = new HashMap<String, String>();
            attrs.put("taskId", mission.missionTaskId());
            attrs.put("posX", String.valueOf(mission.target().x()));
            attrs.put("posY", String.valueOf(mission.target().y()));
            attrs.put("posZ", String.valueOf(mission.target().z()));
            attrs.put("blockId", String.valueOf(mission.initialBlockId()));
            events.push(new BotEvent(
                    EventKind.BLOCK_BROKEN,
                    daySupplier.getAsLong(),
                    timeOfDaySupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    mission.missionTaskId() + ": broke " + mission.initialBlockId()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    /**
     * Cancel hook, routed by the McBotServer cancel router (the bus
     * has ONE listener slot; every verb handler's cancel method is
     * public and self-guards by its missions map).
     *
     * @param taskId identifier of the mission to tear down; unknown ids
     *               are ignored, never null
     * @param verb   wire name of the cancelled command as reported by the
     *               dispatcher; kept for callback-signature symmetry
     */
    public void onCancel(String taskId, String verb) {
        DigProcess mission = missions.remove(taskId);
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
                    Map.of("task", "dig:" + taskId, "taskId", taskId),
                    "dig:" + taskId + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private static boolean validateArgs(BotCommand command) {
        Map<String, String> args = command.args();
        try {
            for (String key : List.of("x", "y", "z")) {
                Integer.parseInt(args.get(key));
            }
            if (args.containsKey("timeoutTicks") && Long.parseLong(args.get("timeoutTicks")) <= 0) {
                return false;
            }
            return true;
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }
}
