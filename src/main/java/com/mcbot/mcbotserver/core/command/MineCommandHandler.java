package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.MineProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Boundary-D verb wiring for "mine" (issue 0014): a block type + count
 * becomes a {@link MineProcess} on the arbiter; cancellation aborts the
 * mission; completion events flow through the pipeline's generic
 * TerminalMission path, and BLOCK_BROKEN is emitted once per broken
 * block (issue 0014 section 4.3) by draining the process's break
 * records on this handler's tick sweep.
 *
 * <p>Contract: see boundaries.md decision 28 (verb table grows by issue)
 * + issue 0014 §4 (mine wire surface).
 */
// contract: see boundaries.md decision 28 + issue 0014 §4
public final class MineCommandHandler {

    /** Default mission budget when the harness omits it (2 minutes). */
    public static final long DEFAULT_TIMEOUT_TICKS = 2400L;

    private final TaskArbiter arbiter;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;
    private final Supplier<CellPos> botPosition;
    private final Map<String, MineProcess> missions = new HashMap<>();
    private CommandBus bus;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param arbiter           mission selector; never null
     * @param events            completion/cancellation stream; never null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never null
     * @param botPosition       live body-position accessor passed into
     *                          every mission as the search center;
     *                          never null
     */
    public MineCommandHandler(
            TaskArbiter arbiter,
            EventQueue events,
            LongSupplier daySupplier,
            LongSupplier timeOfDaySupplier,
            Supplier<CellPos> botPosition) {
        if (arbiter == null
                || events == null
                || daySupplier == null
                || timeOfDaySupplier == null
                || botPosition == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.arbiter = arbiter;
        this.events = events;
        this.daySupplier = daySupplier;
        this.timeOfDaySupplier = timeOfDaySupplier;
        this.botPosition = botPosition;
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
        bus.register("mine", new CommandBus.Handler() {
            @Override
            public String validate(BotCommand command) {
                return validateArgs(command) ? null : "mine wants blockType count [timeoutTicks]";
            }

            @Override
            public void execute(BotCommand command, String taskId) {
                Map<String, String> args = command.args();
                String blockType = args.get("blockType");
                int count = Integer.parseInt(args.get("count"));
                long timeout = args.containsKey("timeoutTicks")
                        ? Long.parseLong(args.get("timeoutTicks"))
                        : DEFAULT_TIMEOUT_TICKS;
                MineProcess mission = new MineProcess(taskId, blockType, count, 50, timeout, botPosition);
                missions.put(taskId, mission);
                arbiter.register(mission);
                arbiter.requestControl(mission);
            }
        });
    }

    /**
     * Per-tick sweep: drain pending break records into BLOCK_BROKEN
     * events (one per broken block), then retire finished missions.
     * Called once per server tick by the wiring, same cadence as the
     * dig/goto handlers' sweep.
     */
    public void tick() {
        for (MineProcess mission : missions.values()) {
            MineProcess.BlockBreak pending;
            while ((pending = mission.pollBreak()) != null) {
                pushBlockBroken(mission.missionTaskId(), pending);
            }
        }
        // Retire-before-announce: the bus dedupe window closes first
        // so a harness retry of the same verb+args submits fresh.
        missions.values().removeIf(m -> {
            if (m.isActive()) {
                return false;
            }
            bus.retire(m.missionTaskId());
            return true;
        });
    }

    private void pushBlockBroken(String taskId, MineProcess.BlockBreak pending) {
        try {
            var attrs = new HashMap<String, String>();
            attrs.put("taskId", taskId);
            attrs.put("posX", String.valueOf(pending.pos().x()));
            attrs.put("posY", String.valueOf(pending.pos().y()));
            attrs.put("posZ", String.valueOf(pending.pos().z()));
            attrs.put("blockId", pending.blockId());
            events.push(new BotEvent(
                    EventKind.BLOCK_BROKEN,
                    daySupplier.getAsLong(),
                    timeOfDaySupplier.getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    taskId + ": broke " + pending.blockId()));
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
        MineProcess mission = missions.remove(taskId);
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
                    Map.of("task", "mine:" + taskId, "taskId", taskId),
                    "mine:" + taskId + ": cancelled by harness"));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private static boolean validateArgs(BotCommand command) {
        Map<String, String> args = command.args();
        try {
            String blockType = args.get("blockType");
            if (blockType == null || blockType.isBlank()) {
                return false;
            }
            int count = Integer.parseInt(args.get("count"));
            if (count <= 0) {
                return false;
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
