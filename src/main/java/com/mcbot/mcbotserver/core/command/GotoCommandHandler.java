package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

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
public final class GotoCommandHandler extends VerbTaskHandler<GotoProcess> {

    /** Default mission budget in ticks when the harness omits it. */
    public static final long DEFAULT_TIMEOUT_TICKS = 1200L;

    /**
     * Creates the handler over the task channel and event stream.
     *
     * @param wiring the assembly's verb wiring bundle; never null
     */
    public GotoCommandHandler(VerbWiring wiring) {
        super(wiring);
    }

    @Override
    protected String verb() {
        return "goto";
    }

    @Nullable
    @Override
    protected String validate(BotCommand command) {
        try {
            parse(command);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Override
    protected GotoProcess createMission(String taskId, BotCommand command) {
        ParsedGoto parsed = parse(command);
        return new GotoProcess(taskId, parsed.goal(), 50, parsed.timeoutTicks());
    }

    /**
     * One-line workload summary for the state snapshot channel.
     *
     * @return display name of one live mission, or "idle" when none;
     *         never null
     */
    public String activeTaskSummary() {
        var any = missions().values().iterator();
        return any.hasNext() ? any.next().displayName() : "idle";
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
    private <N> N numeric(Map<String, String> args, String key, @Nullable N fallback, Function<String, N> parse) {
        String raw = args.get(key);
        if (raw != null) {
            try {
                return parse.apply(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("arg " + key + " is not an integer: " + raw);
            }
        }
        if (fallback == null) {
            throw new IllegalArgumentException("missing arg: " + key);
        }
        return fallback;
    }

    private record ParsedGoto(Goal goal, long timeoutTicks) {}
}
