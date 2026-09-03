package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.goal.GoalRange;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.combat.RangedLoadouts;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * The harness-directed engagement: one named entity, chased and
 * fought until it stops existing in the scan. The sibling of
 * {@link DefendProcess} with the targeting inverted - defend scans
 * for the nearest threat, attack is HANDED the target, so there is
 * no hostile-type filter and no engagement refusal: the harness
 * chose this entity (a cow, a specific zombie, whatever), and the
 * only honest verdicts are machine-checkable ones. Swing timing stays
 * the behavior tier's craft (CombatBehavior; ledger 37); the
 * engagement RANGE is a process decision shared with defend: a
 * bow-only carrier (no hotbar weapon outranks the bow) opens the
 * fight at the standoff rim and fires while the target closes
 * instead of charging into fist-tier bow clubbing, frozen at first
 * sight exactly like defend's engage-time decision.
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
public final class AttackProcess extends MissionShell {

    /**
     * Entity scan radius. Matches DefendProcess.DETECTION_RADIUS:
     * the directed and reflex-driven fighters share one perception
     * envelope, and a target beyond it is treated as escaped, same
     * as defend.
     */
    public static final double SCAN_RADIUS = 16.0;

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final double LEASH_RADIUS = TargetTracker.LEASH_RADIUS;

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final int TARGET_GRACE_TICKS = TargetTracker.TARGET_GRACE_TICKS;

    /**
     * Chase-goal range around the target cell. Same value and
     * rationale as DefendProcess.GOAL_RANGE: A* ends on the first
     * cell satisfying the predicate, so 2 keeps the body outside
     * swing-adjacent overlap while staying inside
     * CombatBehavior.ATTACK_REACH.
     */
    public static final int GOAL_RANGE = 2;

    /** Failure reason: the target id never appeared in any scan. */
    public static final String REASON_NO_TARGET = MissionShell.REASON_NO_TARGET;

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
    public static final String REASON_ESCAPED = MissionShell.REASON_ESCAPED;

    private final String targetId;
    private final Supplier<CellPos> positionSource;
    private final WeaponCatalog weapons;

    private boolean standoffOpening;

    private final TargetTracker tracker = new TargetTracker();
    private Directive lastDirective;

    /**
     * Creates a directed engagement, read without a weapon catalog: a
     * carried bow then always outranks everything, so bow carriers
     * open at the standoff rim.
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
            String taskId,
            @Nullable String targetId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource) {
        this(taskId, targetId, priority, timeoutTicks, positionSource, WeaponCatalog.none());
    }

    /**
     * Creates a directed engagement with the weapon ranking the
     * standoff decision reads.
     *
     * @param taskId         boundary-D task id for tracing; never
     *                       null or blank
     * @param targetId       {@link EntitySnapshot#id()} of the entity
     *                       to fight; never null or blank
     * @param priority       band-legal priority per PriorityBands
     * @param timeoutTicks   tick budget; positive
     * @param positionSource body cell accessor; never null
     * @param weapons        per-hit melee damage ranking; never null
     */
    public AttackProcess(
            String taskId,
            @Nullable String targetId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            WeaponCatalog weapons) {
        super(taskId, priority, timeoutTicks);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        this.targetId = targetId;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.weapons = Objects.requireNonNull(weapons, "weapons");
        this.lastDirective = Directive.of(new GoalNear(positionSource.get(), 1));
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
        if (!live()) {
            return lastDirective;
        }
        if (budgetExpired()) {
            fail(REASON_TIMEOUT);
            return lastDirective;
        }

        CellPos position = positionSource.get();
        EntitySnapshot target = findNamedEntity(world, position, SCAN_RADIUS, targetId);
        if (target == null) {
            adjudicateAbsentTarget(tracker);
            return lastDirective;
        }
        if (target.health() <= 0f) {
            // The corpse sighting IS the kill verdict: health rides
            // the snapshot and dead entities are not filtered, so
            // waiting for absence would misreport a clean kill as an
            // escape (ledger 40).
            succeed();
            return lastDirective;
        }
        return engageTick(world, target, position);
    }

    /**
     * Sighted-target stage: leash adjudication, the engage-time range
     * freeze, and this tick's directive.
     *
     * @param world    read-only world for the loadout reads; never null
     * @param target   the sighted target snapshot; never null
     * @param position the body cell; never null
     * @return the directive for this tick; never null
     */
    private Directive engageTick(WorldView world, EntitySnapshot target, CellPos position) {
        // Engage-time range freeze, the defend symmetry: the first
        // sighting decides chase-versus-standoff for the whole fight.
        boolean firstSighting = !tracker.engaged();
        tracker.sighted(target.pos());
        if (tracker.leashed(position)) {
            fail(REASON_LOST);
            return lastDirective;
        }
        if (firstSighting
                && RangedLoadouts.hotbarBowSlot(world) >= 0
                && !RangedLoadouts.meleeWeaponBeatsBow(world, weapons)) {
            standoffOpening = true;
        }
        // Standoff opening uses a GoalRange band (8..12, centered on
        // RANGED_STANDOFF) so a closing target triggers a backward replan
        // rather than walking through the rim (issue 0018). Melee stays
        // GoalNear to the swing rim.
        int bandMin = DefendProcess.RANGED_STANDOFF - 2;
        int bandMax = DefendProcess.RANGED_STANDOFF + 2;
        CellPos targetCell = tracker.targetCell();
        if (targetCell == null) {
            fail(REASON_NO_TARGET);
            return lastDirective;
        }
        Goal goal =
                standoffOpening ? new GoalRange(targetCell, bandMin, bandMax) : new GoalNear(targetCell, GOAL_RANGE);
        lastDirective = new Directive(goal, new Overrides(new Attack(targetId)));
        return lastDirective;
    }

    private void succeed() {
        recordStickySuccess();
    }

    /**
     * Sticky termination - the defend guard: once a verdict is
     * recorded, later lifecycle callbacks (preemption after a
     * harness cancel) must not overwrite it.
     *
     * @param reason the machine-readable failure reason
     */
    private void fail(String reason) {
        recordStickyFailure(reason);
    }

    @Override
    public boolean resume(InterruptionContext c) {
        if (!live()) {
            return false;
        }
        // Blind-trust guard, the defend trick: spend all grace
        // credit so the very next scan decides.
        tracker.spendAllGrace();
        return true;
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_PREEMPTED);
    }

    /** Abort hook for harness cancellation; terminal state is sticky. */
    @Override
    public void abort() {
        fail("CANCELLED");
    }

    @Override
    public String displayName() {
        return "attack:" + taskId();
    }

    @Override
    public boolean missionSucceeded() {
        return stickySucceeded();
    }

    @Override
    @Nullable
    public String failureReasonOrNull() {
        return stickyFailureOrNull();
    }

    @Override
    public Map<String, String> verdictAttrs() {
        return Map.of("targetId", targetId);
    }
}
