package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.DigProcess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

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
public final class DigCommandHandler extends VerbTaskHandler<DigProcess> {

    /** Default mission budget when the harness omits it (1 minute). */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param wiring the assembly's verb wiring bundle; never null
     */
    public DigCommandHandler(VerbWiring wiring) {
        super(wiring);
    }

    @Override
    protected String verb() {
        return "dig";
    }

    @Nullable
    @Override
    protected String validate(BotCommand command) {
        return validateArgs(command) ? null : "dig wants integer args x y z [timeoutTicks]";
    }

    @Override
    protected DigProcess createMission(String taskId, BotCommand command) {
        Map<String, String> args = command.args();
        long timeout =
                args.containsKey("timeoutTicks") ? Long.parseLong(args.get("timeoutTicks")) : DEFAULT_TIMEOUT_TICKS;
        return new DigProcess(
                taskId,
                new CellPos(
                        Integer.parseInt(args.get("x")),
                        Integer.parseInt(args.get("y")),
                        Integer.parseInt(args.get("z"))),
                50,
                timeout);
    }

    @Override
    protected void onRetired(DigProcess mission) {
        if (mission.missionSucceeded()) {
            pushBlockBroken(mission);
        }
    }

    private void pushBlockBroken(DigProcess mission) {
        try {
            var attrs = new HashMap<String, String>();
            attrs.put("taskId", mission.missionTaskId());
            attrs.put("posX", String.valueOf(mission.target().x()));
            attrs.put("posY", String.valueOf(mission.target().y()));
            attrs.put("posZ", String.valueOf(mission.target().z()));
            attrs.put("blockId", String.valueOf(mission.initialBlockId()));
            events().push(new BotEvent(
                    EventKind.BLOCK_BROKEN,
                    daySupplier().getAsLong(),
                    timeOfDaySupplier().getAsLong(),
                    false,
                    Map.copyOf(attrs),
                    mission.missionTaskId() + ": broke " + mission.initialBlockId()));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    private static boolean validateArgs(BotCommand command) {
        Map<String, String> args = command.args();
        try {
            for (String key : List.of("x", "y", "z")) {
                String value = args.get(key);
                if (value == null) {
                    return false;
                }
                Integer.parseInt(value);
            }
            if (args.containsKey("timeoutTicks") && Long.parseLong(args.get("timeoutTicks")) <= 0) {
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
