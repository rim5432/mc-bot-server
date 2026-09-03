package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.world.WorldView;
import javax.annotation.Nullable;

/**
 * Walk-to-a-goal mission: the first vertical-slice process. Holds a
 * goal and a tick budget, consumes behavior reports, and terminates on
 * SUCCESS, STUCK, or budget exhaustion — never by reading the world.
 *
 * <p>Contract: see boundaries.md decision 18 (GotoCommand semantics)
 * and ADR-0004 D3/D4 (SUCCESS via goal predicate; timeoutTicks enforced
 * in the behavior layer's clock, which for missions is this process's
 * tick counter). State machine: APPROACH while active; terminal states
 * SUCCEEDED / FAILED deactivate the process, which the arbiter then
 * retires.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 18 (GotoCommand minimal semantics)
public final class GotoProcess extends MissionShell {

    /** Failure reason: mover fuse tripped with no recovery route. */
    public static final String REASON_STUCK = "STUCK";

    /** Failure reason: mission outlived its tick budget. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Failure reason: planner found no route to the goal. */
    public static final String REASON_NO_PATH = "NO_PATH";

    private final Goal goal;
    private boolean succeeded;

    @Nullable
    private String failure;

    /**
     * Creates a goto mission.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param goal         arrival authority; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     */
    public GotoProcess(String taskId, Goal goal, int priority, long timeoutTicks) {
        super(taskId, priority, timeoutTicks);
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        this.goal = goal;
    }

    @Override
    public Directive onTick(WorldView world) {
        if (budgetExpired()) {
            fail(REASON_TIMEOUT);
        }
        return Directive.of(goal);
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        if (!live()) {
            // Terminal state is sticky: a late report from the same
            // tick's pipeline must never flip SUCCEEDED into FAILED or
            // vice versa.
            return;
        }
        switch (report.status()) {
            case SUCCESS -> {
                succeeded = true;
                deactivate();
            }
            case STUCK -> fail(REASON_STUCK);
            case FAILED -> fail(reasonOrUnknown(report));
            default -> {
                // RUNNING stays the mover's concern this tick.
            }
        }
    }

    @Override
    public boolean resume(InterruptionContext context) {
        // A walk-to goal has no perishable world assumptions beyond the
        // goal cell existing; Stage 1 keeps this trivially true.
        return live();
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_STUCK);
    }

    @Override
    public String displayName() {
        return "goto:" + taskId();
    }

    private void fail(String reason) {
        if (live()) {
            failure = reason;
            deactivate();
        }
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Nullable
    @Override
    public String failureReasonOrNull() {
        return failure;
    }
}
