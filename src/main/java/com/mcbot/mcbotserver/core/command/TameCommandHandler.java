package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.TameProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Boundary-D verb wiring for "tame": one named animal becomes a
 * {@link TameProcess} on the arbiter; cancellation aborts the
 * mission; the pipeline's generic TerminalMission path emits the
 * verdict (verdict attrs carry the targetId). The task-verb twin of
 * the attack handler - multi-tick execution, CommandBus submission,
 * one row of growth per the decision-28 discipline.
 *
 * <p>Contract: see boundaries.md decision 18's goto shape (seam
 * verbs stay submit/cancel/status; the task rides CommandBus) and
 * decision 28 (the verb table grows by issue).
 */
// contract: see boundaries.md decision 28 (verb table grows by issue)
public final class TameCommandHandler extends VerbTaskHandler<TameProcess> {

    /** Default mission budget when the harness omits it (1 minute). */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    private final Supplier<CellPos> positionSource;

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
    public TameCommandHandler(
            TaskArbiter arbiter,
            EventQueue events,
            LongSupplier daySupplier,
            LongSupplier timeOfDaySupplier,
            Supplier<CellPos> positionSource) {
        super(arbiter, events, daySupplier, timeOfDaySupplier);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
    }

    @Override
    protected String verb() {
        return "tame";
    }

    @Nullable
    @Override
    protected String validate(BotCommand command) {
        return validateArgs(command) ? null : "tame wants a non-blank target id [timeoutTicks]";
    }

    @Override
    protected TameProcess createMission(String taskId, BotCommand command) {
        Map<String, String> args = command.args();
        long timeout =
                args.containsKey("timeoutTicks") ? Long.parseLong(args.get("timeoutTicks")) : DEFAULT_TIMEOUT_TICKS;
        return new TameProcess(taskId, args.get("targetId"), 50, timeout, positionSource);
    }

    private static boolean validateArgs(BotCommand command) {
        Map<String, String> args = command.args();
        String targetId = args.get("targetId");
        if (targetId == null || targetId.isBlank()) {
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
