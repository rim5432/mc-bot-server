package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Light combat planner per boundaries.md decision 11: picks the
 * nearest hostile inside the engage radius and orders it attacked -
 * everything after that order is the behavior tier's craft. The
 * process stays pure: it reads entity snapshots, names a target,
 * steers locomotion through a {@link GoalNear}, and decides when the
 * mission ended.
 *
 * <p>Contract: see ADR-0002 section 1 and decision 10 (no per-mob
 * processes - the hostile-type set is injected data, one process
 * serves every enemy). Terminal semantics: an area with no hostile at
 * submission time completes immediately; an engaged target that stays
 * absent past the grace window counts as neutralized (SUCCESS);
 * leaving the leash or the tick budget fails the mission.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (light planner output)
public final class DefendProcess implements BotProcess, TerminalMission {

    /** Hostiles closer than this many blocks get engaged. */
    public static final double ENGAGE_RADIUS = 8.0;

    /**
     * An engaged target farther away than this escapes - the fight is
     * over as a failure, not a chase to the world border.
     */
    public static final double LEASH_RADIUS = 12.0;

    /** Ticks a missing target gets before counting as neutralized. */
    public static final int TARGET_GRACE_TICKS = 10;

    /**
     * Chase-goal range around the target cell, in blocks. Deliberately
     * 2, not 1: A* ends the plan on the FIRST popped cell satisfying
     * the predicate - the nearest-to-bot cell of the standoff rim -
     * so the body stops outside swing-adjacent overlap instead of
     * driving into the target's cell, where the aim direction
     * degenerates.
     *
     * <p>Invariant: must stay strictly less than
     * {@link com.mcbot.mcbotserver.core.behavior.CombatBehavior#ATTACK_REACH}
     * so the body can still reach the target for a swing while
     * holding the standoff. If ATTACK_REACH ever drops to 2 or
     * below, raise this constant or the chase will end out of
     * swing range.
     */
    public static final int GOAL_RANGE = 2;

    /** Failure reason: engaged target left the leash radius. */
    public static final String REASON_LOST = "LOST_TARGET";

    /** Failure reason: the mission outlived its tick budget. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Failure reason: reflex preemption invalidated the context. */
    public static final String REASON_PREEMPTED = "PREEMPTED";

    private final String taskId;
    private final int priority;
    private final long timeoutTicks;
    private final Supplier<CellPos> poseSource;
    private final Set<String> hostileTypes;

    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private String targetId;
    private CellPos targetCell;
    private int ticksSinceSeen;
    private long ticksInMission;
    private Directive lastDirective;

    /**
     * Creates a defend mission.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     * @param poseSource   body cell accessor; never null
     * @param hostileTypes entity types worth engaging; never null,
     *                     may be empty (then the mission completes at
     *                     once)
     */
    public DefendProcess(String taskId, int priority, long timeoutTicks,
                         Supplier<CellPos> poseSource,
                         Set<String> hostileTypes) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException(
                "timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
        this.poseSource = Objects.requireNonNull(poseSource,
            "poseSource");
        this.hostileTypes = Set.copyOf(Objects.requireNonNull(
            hostileTypes, "hostileTypes"));
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
        if (!active) {
            return lastDirective;
        }
        ticksInMission++;
        if (ticksInMission >= timeoutTicks) {
            fail(REASON_TIMEOUT);
            return lastDirective;
        }

        CellPos pose = poseSource.get();
        EntitySnapshot nearest = nearestHostile(world, pose);
        if (!engaged()) {
            if (nearest == null) {
                // Nothing to defend against: the mission's purpose is
                // already fulfilled.
                succeed();
                return lastDirective;
            }
            engage(nearest);
            return directiveFor(pose);
        }

        if (nearest != null && nearest.id().equals(targetId)) {
            ticksSinceSeen = 0;
            targetCell = nearest.pos();
            if (pose.distanceTo(targetCell) > LEASH_RADIUS) {
                fail(REASON_LOST);
                return lastDirective;
            }
            return directiveFor(pose);
        }

        // Target not re-seen this tick: grace, then declare it down.
        ticksSinceSeen++;
        if (ticksSinceSeen > TARGET_GRACE_TICKS) {
            succeed();
            return lastDirective;
        }
        return directiveFor(pose);
    }

    @Override
    public void onExecutionReport(
            com.mcbot.mcbotserver.api.behavior.ExecutionReport report) {
        if (!active) {
            // Terminal state is sticky, mirroring GotoProcess: late
            // reports must never flip a decided outcome.
            return;
        }
        switch (report.status()) {
            // Deliberately NO success case here: a SUCCEEDED report
            // means the chase locomotion reached its GoalNear - standing
            // next to the enemy is where the FIGHT starts, never the
            // verdict. Only this process's own scans declare victory.
            //
            // Deliberately NO failure case either: a locomotion FAILED
            // describes the CHASE, not the FIGHT - a blocked path or a
            // fuse trip while closing distance must not abort the
            // engagement (the body may already be within swing range).
            // Terminal authority belongs to scans, leash, and timeout.
            default -> {
                // RUNNING, STUCK, SUCCESS, FAILED - all execution
                // weather; the scans decide.
            }
        }
    }

    @Override
    public void onLostControl(
            com.mcbot.mcbotserver.api.interrupt.InterruptionContext c) {
        // Reflex supremacy (decision 9): being parked does not end the
        // fight by itself; resume() decides whether it may continue.
    }

    @Override
    public boolean resume(
            com.mcbot.mcbotserver.api.interrupt.InterruptionContext c) {
        if (!active) {
            return false;
        }
        // Blind-trust guard: resume() has no world access, so instead
        // of trusting the pre-pause target we spend ALL grace credit
        // here - the very next onTick scan adjudicates. Target gone =>
        // immediate TARGET_DOWN success; present => normal leash and
        // refresh logic. Worst-case stale steering collapses from a
        // full grace window to exactly one tick.
        ticksSinceSeen = TARGET_GRACE_TICKS;
        return true;
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_PREEMPTED);
    }

    @Override
    public String displayName() {
        return "defend:" + taskId;
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
        return failure;
    }

    @Override
    public String missionTaskId() {
        return taskId;
    }

    private boolean engaged() {
        return targetId != null;
    }

    private void engage(EntitySnapshot target) {
        targetId = target.id();
        targetCell = target.pos();
        ticksSinceSeen = 0;
    }

    private Directive directiveFor(CellPos botCell) {
        lastDirective = new Directive(
            new GoalNear(targetCell, GOAL_RANGE),
            new Overrides(new Attack(targetId)));
        return lastDirective;
    }

    private EntitySnapshot nearestHostile(WorldView world,
                                          CellPos center) {
        // Tracking must see farther than engaging: an already-locked
        // target between ENGAGE and LEASH stays visible here, which is
        // what makes the leash break observable at all.
        double scanRadius = engaged()
            ? Math.max(ENGAGE_RADIUS, LEASH_RADIUS + 2)
            : ENGAGE_RADIUS;
        List<EntitySnapshot> hits = world.getEntities(center,
            scanRadius,
            com.mcbot.mcbotserver.api.world.ViewMode.LIVE);
        EntitySnapshot best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntitySnapshot e : hits) {
            if (!hostileTypes.contains(e.type())) {
                continue;
            }
            double dist = center.distanceTo(e.pos());
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    private void succeed() {
        succeeded = true;
        active = false;
    }

    private void fail(String reason) {
        if (active) {
            failure = reason;
            active = false;
        }
    }
}
