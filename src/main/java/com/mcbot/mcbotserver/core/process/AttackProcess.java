package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The harness-directed engagement: one named entity, chased and
 * fought until it stops existing in the scan. The sibling of
 * {@link DefendProcess} with the targeting inverted - defend scans
 * for the nearest threat, attack is HANDED the target, so there is
 * no hostile-type filter and no engagement refusal: the harness
 * chose this entity (a cow, a specific zombie, whatever), and the
 * only honest verdicts are machine-checkable ones. Weapon choice,
 * bow standoff, and swing timing stay the behavior tier's craft
 * (CombatBehavior; ledger 37).
 *
 * <p>Terminal semantics mirror DefendProcess deliberately, with one
 * sharpening (ledger 40): death is DIRECTLY machine-judged - the
 * snapshot carries health and dead entities are not filtered from
 * the scan, so a sighting of the target at zero health completes the
 * mission as a confirmed kill instead of being inferred from
 * absence. What remains conservative is the death-UNSEEN case: a
 * target absent past the grace window fails as TARGET_ESCAPED (dead
 * or fled, the bot cannot tell which; the harness re-scans). A
 * target id that never appears in the first scan fails fast as
 * NO_SUCH_ENTITY rather than burning the whole budget staring at
 * empty air. Leash, timeout, and preemption behave as in defend.
 *
 * <p>Contract: see boundaries.md decision 10 (no per-mob processes
 * - the target id is injected data) and decision 18's goto shape
 * (task rides CommandBus; ledger 39 owns the wire row).
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (light planner output)
public final class AttackProcess implements BotProcess, TerminalMission {

    /**
     * Entity scan radius. Matches DefendProcess.DETECTION_RADIUS:
     * the directed and reflex-driven fighters share one perception
     * envelope, and a target beyond it is treated as escaped, same
     * as defend.
     */
    public static final double SCAN_RADIUS = 16.0;

    /** A chased target farther away than this escapes; no world-border chases. */
    public static final double LEASH_RADIUS = 12.0;

    /** Ticks a missing target gets before the ESCAPED verdict. */
    public static final int TARGET_GRACE_TICKS = 10;

    /**
     * Chase-goal range around the target cell. Same value and
     * rationale as DefendProcess.GOAL_RANGE: A* ends on the first
     * cell satisfying the predicate, so 2 keeps the body outside
     * swing-adjacent overlap while staying inside
     * CombatBehavior.ATTACK_REACH.
     */
    public static final int GOAL_RANGE = 2;

    /** Failure reason: the target id never appeared in any scan. */
    public static final String REASON_NO_TARGET = "NO_SUCH_ENTITY";

    /** Failure reason: engaged target left the leash radius. */
    public static final String REASON_LOST = "LOST_TARGET";

    /** Failure reason: the mission outlived its tick budget. */
    public static final String REASON_TIMEOUT = "TIMEOUT";

    /** Failure reason: reflex preemption invalidated the context. */
    public static final String REASON_PREEMPTED = "PREEMPTED";

    /**
     * Failure reason: the target stayed absent from the scan past
     * the grace window - dead or fled, the bot cannot tell which
     * (see the class doc for why the conservative verdict wins).
     */
    public static final String REASON_ESCAPED = "TARGET_ESCAPED";

    private final String taskId;
    private final String targetId;
    private final int priority;
    private final long timeoutTicks;
    private final Supplier<CellPos> positionSource;

    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private boolean engaged;
    private CellPos targetCell;
    private int ticksSinceSeen;
    private long ticksInMission;
    private Directive lastDirective;

    /**
     * Creates a directed engagement.
     *
     * @param taskId         boundary-D task id for tracing; never
     *                       null or blank
     * @param targetId       {@link EntitySnapshot#id()} of the entity
     *                       to fight; never null or blank
     * @param priority       band-legal priority per PriorityBands
     * @param timeoutTicks   tick budget; positive
     * @param positionSource body cell accessor; never null
     */
    public AttackProcess(
            String taskId, String targetId, int priority, long timeoutTicks, Supplier<CellPos> positionSource) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.targetId = targetId;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
    }

    /**
     * The entity this mission was pointed at.
     *
     * @return the handed target id; never null or blank
     */
    public String targetId() {
        return targetId;
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

        CellPos position = positionSource.get();
        EntitySnapshot target = findById(world, position);
        if (target != null && target.health() <= 0f) {
            // The corpse sighting IS the kill verdict: health rides
            // the snapshot and dead entities are not filtered, so
            // waiting for absence would misreport a clean kill as an
            // escape (ledger 40).
            succeed();
            return lastDirective;
        }
        if (target == null) {
            if (!engaged) {
                // The id the harness named has never been in the
                // scan: refuse NOW instead of budget-staring. A
                // despawn between ls and submit is
                // indistinguishable from a typo - both deserve the
                // same fast, honest no.
                fail(REASON_NO_TARGET);
                return lastDirective;
            }
            // ORDER IS LOAD-BEARING, same as defend: a seen target
            // resets the grace counter above before this branch can
            // fire, which resume() spends as instant adjudication.
            ticksSinceSeen++;
            if (ticksSinceSeen > TARGET_GRACE_TICKS) {
                fail(REASON_ESCAPED);
            }
            return lastDirective;
        }

        engaged = true;
        ticksSinceSeen = 0;
        targetCell = target.pos();
        if (position.distanceTo(targetCell) > LEASH_RADIUS) {
            fail(REASON_LOST);
            return lastDirective;
        }
        lastDirective = new Directive(new GoalNear(targetCell, GOAL_RANGE), new Overrides(new Attack(targetId)));
        return lastDirective;
    }

    /**
     * Resolve the named entity inside the shared scan envelope.
     *
     * @param world    read-only world; never null
     * @param position scan center (the body cell); never null
     * @return the snapshot, or null when absent from the scan
     */
    private EntitySnapshot findById(WorldView world, CellPos position) {
        List<EntitySnapshot> found = world.getEntities(position, SCAN_RADIUS, ViewMode.LIVE);
        for (EntitySnapshot e : found) {
            if (e.id().equals(targetId)) {
                return e;
            }
        }
        return null;
    }

    private void succeed() {
        active = false;
        succeeded = true;
    }

    /**
     * Sticky termination - the defend guard: once a verdict is
     * recorded, later lifecycle callbacks (preemption after a
     * harness cancel) must not overwrite it.
     *
     * @param reason the machine-readable failure reason
     */
    private void fail(String reason) {
        if (active) {
            active = false;
            succeeded = false;
            failure = reason;
        }
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        // Execution weather, exactly as in defend: a SUCCEEDED chase
        // report means the body is next to the target - where the
        // FIGHT starts, not a verdict. Scans, leash, and timeout own
        // every terminal decision.
    }

    @Override
    public void onLostControl(InterruptionContext c) {
        // Reflex supremacy (decision 9): parking does not end the
        // fight; resume() adjudicates on the next scan.
    }

    @Override
    public boolean resume(InterruptionContext c) {
        if (!active) {
            return false;
        }
        // Blind-trust guard, the defend trick: spend all grace
        // credit so the very next scan decides - present target
        // resets the counter, absent one trips the verdict at once.
        ticksSinceSeen = TARGET_GRACE_TICKS;
        return true;
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_PREEMPTED);
    }

    /** Abort hook for harness cancellation; terminal state is sticky. */
    public void abort() {
        fail("CANCELLED");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String displayName() {
        return "attack:" + taskId;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean missionSucceeded() {
        return succeeded;
    }

    @Override
    public String failureReasonOrNull() {
        return active ? null : failure;
    }

    @Override
    public String missionTaskId() {
        return taskId;
    }

    @Override
    public Map<String, String> verdictAttrs() {
        return Map.of("targetId", targetId);
    }
}
