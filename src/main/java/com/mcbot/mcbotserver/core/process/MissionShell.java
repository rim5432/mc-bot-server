package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.ExecutionReport;

/**
 * Shared mission scaffold for the process family: task identity, seat
 * priority, tick budget, and the live/terminal flag with the lifecycle
 * primitives every mission agrees on. Terminal verdicts stay with each
 * subclass on purpose - the fail/abort shapes differ for real (silent
 * cancellation vs CANCELLED-recorded abort, sticky vs overwrite failure
 * records), and one template would couple unrelated verdict policies.
 *
 * <p>Contract: see ADR-0002 section 1 (fixed BotProcess contract) and
 * boundaries.md section B. Subclasses own onTick, report handling and
 * the resume/revalidate policy; the shell pins what never varies.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
public abstract class MissionShell implements BotProcess, TerminalMission {

    private final String taskId;
    private final int priority;
    private final long timeoutTicks;
    private long ticksInMission;
    private boolean active = true;

    /**
     * Validates and pins the mission identity triple.
     *
     * @param taskId       boundary-D task id; never null or blank
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     */
    protected MissionShell(String taskId, int priority, long timeoutTicks) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be null or blank");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
    }

    @Override
    public final boolean isActive() {
        return active;
    }

    @Override
    public final int priority() {
        return priority;
    }

    @Override
    public final String missionTaskId() {
        return taskId;
    }

    /**
     * Silent termination for harness cancellation: deactivates without
     * recording a failure, so completion events stay truthful.
     * Subclasses with a cancel-verdict policy override this.
     */
    public void abort() {
        active = false;
    }

    /**
     * Advances the mission tick counter and tests the budget.
     *
     * @return true once the mission has consumed its tick budget
     */
    protected final boolean budgetExpired() {
        return ++ticksInMission >= timeoutTicks;
    }

    /**
     * Sticky-terminal guard for report and lifecycle callbacks: a
     * decided mission must never flip its verdict on a late callback.
     *
     * @return true while the mission has not reached a terminal state
     */
    protected final boolean live() {
        return active;
    }

    /**
     * Deactivates the mission; verdict recording (succeeded/failure)
     * stays the caller's policy.
     */
    protected final void deactivate() {
        active = false;
    }

    /**
     * Task identity for subclass display names and diagnostics.
     *
     * @return the task id passed at construction; never null
     */
    protected final String taskId() {
        return taskId;
    }

    /**
     * Report reason or UNKNOWN when the behavior sent none.
     *
     * @param report the behavior verdict; never null
     * @return the recorded reason or the UNKNOWN placeholder; never
     *         null
     */
    protected static String reasonOrUnknown(ExecutionReport report) {
        return report.reason() != null ? report.reason() : "UNKNOWN";
    }
}
