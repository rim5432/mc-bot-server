package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.AttackProcess;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;

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
     * @param wiring        the assembly's verb wiring bundle; never
     *                       null
     * @param positionSource body cell accessor feeding the scan
     *                       center; never null
     */
    public AttackCommandHandler(VerbWiring wiring, Supplier<CellPos> positionSource) {
        this(wiring, positionSource, WeaponCatalog.none());
    }

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param wiring        the assembly's verb wiring bundle; never
     *                       null
     * @param positionSource body cell accessor feeding the scan
     *                       center; never null
     * @param weapons        per-hit melee damage ranking feeding
     *                       the standoff decision; never null
     */
    public AttackCommandHandler(VerbWiring wiring, Supplier<CellPos> positionSource, WeaponCatalog weapons) {
        super(wiring);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
    }

    @Override
    protected String verb() {
        return "attack";
    }

    @Nullable
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
