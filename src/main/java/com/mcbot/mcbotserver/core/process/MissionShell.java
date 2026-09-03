package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import javax.annotation.Nullable;

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
     * Resolve a named entity inside the shared scan envelope: a
     * LIVE-mode entity scan centered on the body cell, matched by the
     * harness-assigned id. The scan shapes agree across the directed
     * missions (attack/tame) - only their verdict policies differ.
     *
     * @param world      read-only world; never null
     * @param position   scan center (the body cell); never null
     * @param scanRadius scan radius in blocks; positive
     * @param targetId   the entity id to resolve; never null
     * @return the snapshot, or null when absent from the scan
     */
    // Sticky verdict state for the opt-in defend-guard shape
    // (AttackProcess/TameProcess): once a verdict is recorded, later
    // lifecycle callbacks must not overwrite it. Unconditional-record
    // shapes (DigProcess) keep their own local fields on purpose.

    private boolean stickySucceededFlag;

    @Nullable
    private String stickyFailure;

    /**
     * Records success and deactivates, sticky-guarded as a pair with
     * {@link #recordStickyFailure(String)}.
     */
    protected final void recordStickySuccess() {
        deactivate();
        stickySucceededFlag = true;
    }

    /**
     * Sticky termination - the defend guard: once a verdict is
     * recorded, later lifecycle callbacks (preemption after a harness
     * cancel) must not overwrite it.
     *
     * @param reason the machine-readable failure reason
     */
    protected final void recordStickyFailure(String reason) {
        if (active) {
            deactivate();
            stickySucceededFlag = false;
            stickyFailure = reason;
        }
    }

    /** Reads the sticky success flag recorded by this subclass. */
    protected final boolean stickySucceeded() {
        return stickySucceededFlag;
    }

    /** Reads the sticky failure reason; null while the mission lives. */
    protected final @Nullable String stickyFailureOrNull() {
        return live() ? null : stickyFailure;
    }

    protected static @Nullable EntitySnapshot findNamedEntity(
            WorldView world, CellPos position, double scanRadius, String targetId) {
        for (EntitySnapshot e : world.getEntities(position, scanRadius, ViewMode.LIVE)) {
            if (e.id().equals(targetId)) {
                return e;
            }
        }
        return null;
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
