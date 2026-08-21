package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.world.WorldView;

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
public final class GotoProcess implements BotProcess, TerminalMission {

    /** Why this mission left the FAILED state's door. */
    public enum FailureReason {

        /** The mover reported no displacement over its fuse window. */
        STUCK,

        /** The mission outlived its tick budget. */
        TIMEOUT
    }

    private final String taskId;
    private final Goal goal;
    private final int priority;
    private final long timeoutTicks;
    private long ticksInMission;
    private boolean active = true;
    private boolean succeeded;
    private FailureReason failure;

    /**
     * Creates a goto mission.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param goal         arrival authority; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     */
    public GotoProcess(String taskId, Goal goal, int priority,
                       long timeoutTicks) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (goal == null) {
            throw new IllegalArgumentException("goal must not be null");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException(
                "timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.goal = goal;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Directive onTick(WorldView world) {
        ticksInMission++;
        if (ticksInMission >= timeoutTicks) {
            fail(FailureReason.TIMEOUT);
            return Directive.of(goal);
        }
        return Directive.of(goal);
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        if (!active) {
            // Terminal state is sticky: a late report from the same
            // tick's pipeline must never flip SUCCEEDED into FAILED or
            // vice versa.
            return;
        }
        switch (report.status()) {
            case SUCCESS -> {
                succeeded = true;
                active = false;
            }
            case STUCK -> fail(FailureReason.STUCK);
            default -> {
                // RUNNING and FAILED stay the mover's concern this tick.
            }
        }
    }

    @Override
    public void onLostControl(InterruptionContext context) {
        // Keep everything; resume revalidates through the goal itself.
    }

    @Override
    public boolean resume(InterruptionContext context) {
        // A walk-to goal has no perishable world assumptions beyond the
        // goal cell existing; Stage 1 keeps this trivially true.
        return active;
    }

    @Override
    public void onContextInvalidated() {
        fail(FailureReason.STUCK);
    }

    @Override
    public String displayName() {
        return "goto:" + taskId;
    }

    /**
     * Terminal state query for the command layer's completion events.
     *
     * @return true when the goal predicate passed before any failure
     */
    public boolean isSucceeded() {
        return succeeded;
    }

    /**
     * Terminal state query for the command layer's completion events.
     *
     * @return the failure cause; null while running or when succeeded
     */
    public FailureReason failure() {
        return failure;
    }

    private void fail(FailureReason reason) {
        if (active) {
            failure = reason;
            active = false;
        }
    }

    /**
     * Silent termination for harness cancellation: deactivates without
     * recording a failure, so completion events stay truthful.
     */
    public void abort() {
        active = false;
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    public String failureReasonOrNull() {
        return failure != null ? failure.name() : null;
    }
}
