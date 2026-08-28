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
import java.util.Objects;
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
public final class MineCommandHandler extends VerbTaskHandler<MineProcess> {

    /** Default mission budget when the harness omits it (2 minutes). */
    public static final long DEFAULT_TIMEOUT_TICKS = 2400L;

    private final Supplier<CellPos> botPosition;

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
        super(arbiter, events, daySupplier, timeOfDaySupplier);
        this.botPosition = Objects.requireNonNull(botPosition, "botPosition");
    }

    @Override
    protected String verb() {
        return "mine";
    }

    @Override
    protected String validate(BotCommand command) {
        return validateArgs(command) ? null : "mine wants blockType count [timeoutTicks]";
    }

    @Override
    protected MineProcess createMission(String taskId, BotCommand command) {
        Map<String, String> args = command.args();
        String blockType = args.get("blockType");
        int count = Integer.parseInt(args.get("count"));
        long timeout =
                args.containsKey("timeoutTicks") ? Long.parseLong(args.get("timeoutTicks")) : DEFAULT_TIMEOUT_TICKS;
        return new MineProcess(taskId, blockType, count, 50, timeout, botPosition);
    }

    @Override
    protected void beforeSweep() {
        for (MineProcess mission : missions().values()) {
            MineProcess.BlockBreak pending;
            while ((pending = mission.pollBreak()) != null) {
                pushBlockBroken(mission.missionTaskId(), pending);
            }
        }
    }

    private void pushBlockBroken(String taskId, MineProcess.BlockBreak pending) {
        try {
            var attrs = new HashMap<String, String>();
            attrs.put("taskId", taskId);
            attrs.put("posX", String.valueOf(pending.pos().x()));
            attrs.put("posY", String.valueOf(pending.pos().y()));
            attrs.put("posZ", String.valueOf(pending.pos().z()));
            attrs.put("blockId", pending.blockId());
            events().push(new BotEvent(
                    EventKind.BLOCK_BROKEN,
                    daySupplier().getAsLong(),
                    timeOfDaySupplier().getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    taskId + ": broke " + pending.blockId()));
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
