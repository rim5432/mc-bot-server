package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;

/**
 * One-block dig mission (issue 0013 R1): a submit-and-wait task, not
 * a synchronous verb - the executor accumulates break progress over
 * many ticks and the INTERACT claim expires every tick, so the driver
 * (the controller's mission-dig path, mirroring preemptDigClaims)
 * must re-issue {@code Intent.Dig} per tick while this process is
 * seated.
 *
 * <p>The directive is {@code GoalNear(target, 2)}: pathing walks the
 * bot into interaction reach first (goto-then-dig for free) and holds
 * it there. Dig progress accrues only once the executor's own reach
 * check passes; out-of-reach claims are ignored honestly by the
 * executor, so the walk and the dig compose without coordination.
 *
 * <p>Completion read: {@code onTick} polls the target cell on the
 * live view and flips terminal when it reads air. The initial block
 * id is captured on the first tick for the BLOCK_BROKEN disclosure.
 */
// contract: see boundaries.md section B (process tier is
//            side-effect-free) + issue 0013 R1 (dig is a task)
public final class DigProcess implements BotProcess, TerminalMission, DigMission {

    private final String taskId;
    private final CellPos target;
    private final int priority;
    private final long timeoutTicks;
    private long ticksInMission;
    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private String initialBlockId;

    /**
     * Creates a dig mission.
     *
     * @param taskId      the boundary-D task id; never null
     * @param target      the world-absolute block cell; never null
     * @param priority    arbiter seat priority (same scale as goto)
     * @param timeoutTicks mission budget; positive
     */
    public DigProcess(String taskId, CellPos target, int priority, long timeoutTicks) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be null or blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.target = target;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
    }

    @Override
    public String displayName() {
        return "dig:" + taskId;
    }

    /** The dig target this process drives claims toward. */
    public CellPos target() {
        return target;
    }

    /** Arbiter seat priority for the mission-dig claim path. */
    public int priority() {
        return priority;
    }

    @Override
    public CellPos digTarget() {
        return target;
    }

    @Override
    public boolean isDigging() {
        // DigProcess is always digging while active: the GoalNear directive
        // holds the bot in range and the controller injects dig claims.
        return active;
    }

    /** Block id captured on the first tick; null before that. */
    public String initialBlockId() {
        return initialBlockId;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public Directive onTick(WorldView world) {
        if (!active) {
            // Terminal hold: the arbiter retires us on the next lap.
            return Directive.of(new GoalNear(target, 2));
        }
        if (initialBlockId == null) {
            BlockSnapshot snap = world.getBlock(target, ViewMode.LIVE);
            if (snap == null) {
                return fail("chunk not loaded");
            }
            initialBlockId = snap.blockId();
            if (BlockSnapshot.AIR.equals(initialBlockId) || BlockSnapshot.UNKNOWN.equals(initialBlockId)) {
                return fail("target is " + initialBlockId);
            }
        }
        if (++ticksInMission >= timeoutTicks) {
            return fail("TIMEOUT");
        }
        BlockSnapshot now = world.getBlock(target, ViewMode.LIVE);
        if (now != null && BlockSnapshot.AIR.equals(now.blockId())) {
            // Mission accomplished: the caller (handler sweep) emits
            // BLOCK_BROKEN; MissionReporter announces TASK_COMPLETED
            // through the generic TerminalMission path.
            succeeded = true;
            active = false;
        }
        return Directive.of(new GoalNear(target, 2));
    }

    @Override
    public void onExecutionReport(com.mcbot.mcbotserver.api.behavior.ExecutionReport report) {
        if (!active) {
            // Terminal state is sticky: a late report from the same
            // tick's pipeline must never flip a terminal state.
            return;
        }
        switch (report.status()) {
            case STUCK -> fail("STUCK: " + reasonOrUnknown(report));
            case FAILED -> fail(reasonOrUnknown(report));
            case SUCCESS -> {
                // PathingBehavior SUCCESS means the bot reached the
                // GoalNear stand-off range — that is the dig position,
                // not mission completion. The block-air read in onTick
                // owns the success decision.
            }
            default -> {
                // RUNNING stays the mover's concern this tick.
            }
        }
    }

    @Override
    public void onLostControl(com.mcbot.mcbotserver.api.interrupt.InterruptionContext ctx) {
        // Keep every logical field intact (boundary B resume
        // contract): the interruption is recorded by the reflex
        // paths; the mission revalidates nothing that a fresh tick
        // does not re-read anyway.
    }

    @Override
    public boolean resume(com.mcbot.mcbotserver.api.interrupt.InterruptionContext ctx) {
        // A single-block dig has no perishable world assumptions
        // beyond the target cell; onTick re-reads it every tick and
        // fails honestly if it turned to air meanwhile. Break
        // progress resetting on eviction is vanilla-parity (release()
        // on claim loss).
        return active;
    }

    @Override
    public void onContextInvalidated() {
        fail("STUCK");
    }

    /** Silent termination for harness cancellation. */
    public void abort() {
        active = false;
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    public String missionTaskId() {
        return taskId;
    }

    @Override
    public String failureReasonOrNull() {
        return failure;
    }

    private Directive fail(String reason) {
        failure = reason;
        active = false;
        return Directive.of(new GoalNear(target, 2));
    }

    private static String reasonOrUnknown(com.mcbot.mcbotserver.api.behavior.ExecutionReport report) {
        return report.reason() != null ? report.reason() : "UNKNOWN";
    }
}
