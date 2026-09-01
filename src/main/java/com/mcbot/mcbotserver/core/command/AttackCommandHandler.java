package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.AttackProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import java.util.Map;
import java.util.Objects;
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
public final class AttackCommandHandler extends VerbTaskHandler<AttackProcess> {

    /** Default mission budget when the harness omits it (1 minute). */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    private final Supplier<CellPos> positionSource;
    private final WeaponCatalog weapons;

    /**
     * Creates the handler over the task channel and event stream,
     * read without a weapon catalog (bow carriers always stand off).
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
        this(arbiter, events, daySupplier, timeOfDaySupplier, positionSource, WeaponCatalog.none());
    }

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
     * @param weapons           per-hit melee damage ranking feeding
     *                          the standoff decision; never null
     */
    public AttackCommandHandler(
            TaskArbiter arbiter,
            EventQueue events,
            LongSupplier daySupplier,
            LongSupplier timeOfDaySupplier,
            Supplier<CellPos> positionSource,
            WeaponCatalog weapons) {
        super(arbiter, events, daySupplier, timeOfDaySupplier);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
    }

    @Override
    protected String verb() {
        return "attack";
    }

    @Override
    protected String validate(BotCommand command) {
        return validateArgs(command) ? null : "attack wants a non-blank target id [timeoutTicks]";
    }

    @Override
    protected AttackProcess createMission(String taskId, BotCommand command) {
        Map<String, String> args = command.args();
        long timeout =
                args.containsKey("timeoutTicks") ? Long.parseLong(args.get("timeoutTicks")) : DEFAULT_TIMEOUT_TICKS;
        return new AttackProcess(taskId, args.get("targetId"), 50, timeout, positionSource, weapons);
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
