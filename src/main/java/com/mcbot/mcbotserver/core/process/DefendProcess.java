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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * submission time completes immediately as SUCCESS; an engaged target
 * that stays absent from the hostile scan past the grace window fails
 * as TARGET_ESCAPED — "absent" includes both death and escape beyond
 * scan range, and the bot cannot distinguish the two (EntitySnapshot
 * carries no death flag), so the conservative failure verdict is
 * reported; the harness re-scans to confirm. Leaving the leash or the
 * tick budget fails the mission with LOST_TARGET or TIMEOUT; a
 * melee-incompatible hostile is refused with ENGAGEMENT_REFUSED.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (light planner output)
public final class DefendProcess implements BotProcess, TerminalMission {

    /** Hostiles closer than this many blocks get engaged. */
    public static final double ENGAGE_RADIUS = 8.0;

    /**
     * Non-engaged threat detection radius. Before a target is locked,
     * the process scans this far to decide whether there is anything
     * worth engaging or refusing. Matches LevelThreatSensor.THREAT_RANGE
     * (16.0): the reflex layer and the combat planner share the same
     * perception envelope. Kept as a local constant rather than
     * referencing the adapter-layer value because core/ must not
     * import adapter/ (layer direction is adapter → core, never the
     * reverse). A hostile beyond this radius is not the bot's immediate
     * concern; an empty scan inside it means the area is genuinely clear.
     */
    public static final double DETECTION_RADIUS = 16.0;

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

    /**
     * Failure reason: the nearest hostile's tactics structurally
     * defeat melee-only engagement (kite-and-shoot) - refusing is the
     * honest move, chasing to timeout just bleeds. The refused type
     * travels in event attrs as {@code threatType}.
     */
    public static final String REASON_REFUSED = "ENGAGEMENT_REFUSED";

    /**
     * Failure reason: an engaged target stayed absent from the hostile
     * scan past the grace window. The bot cannot distinguish death from
     * escape (EntitySnapshot carries no death flag; health=0 is not
     * filtered by the sensor), so it reports the conservative verdict:
     * a failure rather than SUCCESS. A false ESCAPED costs the harness
     * one re-scan; a false SUCCESS costs a missed threat. See issue
     * 0006 for the scan-radius / leash gap that motivated this.
     */
    public static final String REASON_ESCAPED = "TARGET_ESCAPED";

    private final String taskId;
    private final int priority;
    private final long timeoutTicks;
    private final Supplier<CellPos> positionSource;
    private final Set<String> hostileTypes;
    private final Set<String> rangedTypes;
    private String lastRefusedType;

    private boolean active = true;
    private boolean succeeded;
    private String failure;
    private String targetId;
    private CellPos targetCell;
    private int ticksSinceSeen;
    private long ticksInMission;
    private Directive lastDirective;

    /**
     * Creates a defend mission that engages every hostile type.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     * @param positionSource   body cell accessor; never null
     * @param hostileTypes entity types worth engaging; never null,
     *                     may be empty (then the mission completes at
     *                     once)
     */
    public DefendProcess(
            String taskId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            Set<String> hostileTypes) {
        this(taskId, priority, timeoutTicks, positionSource, hostileTypes, Set.of());
    }

    /**
     * Creates a defend mission with an explicit ranged-refusal set.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     * @param positionSource   body cell accessor; never null
     * @param hostileTypes entity types worth engaging; never null
     * @param rangedTypes  hostile types whose tactics defeat melee -
     *                     these are REFUSED at engage time instead of
     *                     chased; never null
     */
    public DefendProcess(
            String taskId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            Set<String> hostileTypes,
            Set<String> rangedTypes) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.taskId = taskId;
        this.priority = priority;
        this.timeoutTicks = timeoutTicks;
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.hostileTypes = Set.copyOf(Objects.requireNonNull(hostileTypes, "hostileTypes"));
        this.rangedTypes = Set.copyOf(Objects.requireNonNull(rangedTypes, "rangedTypes"));
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

        CellPos position = positionSource.get();
        if (!engaged()) {
            EntitySnapshot nearest = nearestHostile(world, position);
            if (nearest == null) {
                // Nothing to defend against: the mission's purpose is
                // already fulfilled.
                succeed();
                return lastDirective;
            }
            if (rangedTypes.contains(nearest.type())) {
                // Structural mismatch: melee-only cannot answer kite-
                // and-shoot. Refusing NOW beats bleeding to leash or
                // timeout - the harness sees the refusal and decides
                // (retreat, reroute, or accept the exposure).
                lastRefusedType = nearest.type();
                fail(REASON_REFUSED);
                return lastDirective;
            }
            engage(nearest);
            return directiveFor();
        }

        EntitySnapshot current = findTarget(world, position, targetId);
        if (current != null) {
            // Target still in the hostile scan: refresh, leash-check,
            // keep fighting. A nearer hostile does NOT drop this target —
            // switching is an explicit decision (death / leash / future
            // policy), never a side effect of nearestHostile ordering.
            //
            // Leash is checked on the resolved target regardless of
            // whether it is the nearest hostile — the old code nested
            // this check inside nearest.id().equals(targetId), which let
            // a closer hostile shield an escaping target from the leash
            // break (A at 13 behind B at 7 → old code skipped leash,
            // fell into grace → false SUCCESS).
            ticksSinceSeen = 0;
            targetCell = current.pos();
            if (position.distanceTo(targetCell) > LEASH_RADIUS) {
                fail(REASON_LOST);
                return lastDirective;
            }
            return directiveFor();
        }

        // Target genuinely absent from the hostile scan: grace, then
        // TARGET_ESCAPED. "Absent" = dead OR out of scan range — the
        // bot cannot tell which, so it reports the conservative failure
        // verdict (issue 0006). The harness re-scans to confirm; a
        // false ESCAPED is cheap, a false SUCCESS is a missed threat.
        //
        // ORDER IS LOAD-BEARING: the seen-branch above resets
        // ticksSinceSeen to zero BEFORE this check runs. resume()
        // exploits that by spending all grace credit (sets
        // ticksSinceSeen = TARGET_GRACE_TICKS): a target still present
        // on the first post-resume tick resets the counter and the fight
        // continues; an absent one trips 11 > 10 immediately. Reordering
        // these two blocks would unconditionally kill every resumed fight
        // whose target is still present — the absent-target case is
        // unaffected (both orders → TARGET_ESCAPED).
        ticksSinceSeen++;
        if (ticksSinceSeen > TARGET_GRACE_TICKS) {
            fail(REASON_ESCAPED);
            return lastDirective;
        }
        return directiveFor();
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        if (!active) {
            // Terminal state is sticky, mirroring GotoProcess: late
            // reports must never flip a decided outcome.
            return;
        }
        // Every report status is execution weather here - RUNNING,
        // STUCK, SUCCESS, FAILED alike; the scans decide.
        //
        // Deliberately NO success case: a SUCCEEDED report means the
        // chase locomotion reached its GoalNear - standing next to
        // the enemy is where the FIGHT starts, never the verdict.
        // Only this process's own scans declare victory.
        //
        // Deliberately NO failure case either: a locomotion FAILED
        // describes the CHASE, not the FIGHT - a blocked path or a
        // fuse trip while closing distance must not abort the
        // engagement (the body may already be within swing range).
        // Terminal authority belongs to scans, leash, and timeout.
    }

    @Override
    public void onLostControl(InterruptionContext c) {
        // Reflex supremacy (decision 9): being parked does not end the
        // fight by itself; resume() decides whether it may continue.
    }

    @Override
    public boolean resume(InterruptionContext c) {
        if (!active) {
            return false;
        }
        // Blind-trust guard: resume() has no world access, so instead
        // of trusting the pre-pause target we spend ALL grace credit
        // here - the very next onTick scan adjudicates. Target gone =>
        // immediate TARGET_ESCAPED failure; present => normal leash and
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
    public Map<String, String> verdictAttrs() {
        if (REASON_REFUSED.equals(failure) && lastRefusedType != null) {
            return Map.of("threatType", lastRefusedType);
        }
        return Map.of();
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

    private Directive directiveFor() {
        lastDirective = new Directive(new GoalNear(targetCell, GOAL_RANGE), new Overrides(new Attack(targetId)));
        return lastDirective;
    }

    /**
     * Single scan path for both target selection and target lookup:
     * same radius, same hostile-type filter, same ViewMode. Callers
     * differ only in how they pick from the returned list — nearest
     * for initial engagement, id-match for engaged refresh.
     *
     * @param world  perception surface; never null
     * @param center search origin; never null
     * @return hostile-typed entities within the active scan radius;
     *         never null, possibly empty
     */
    private List<EntitySnapshot> scanHostiles(WorldView world, CellPos center) {
        // Engaged: tracking must see farther than engaging — an already-
        // locked target between ENGAGE and LEASH stays visible here, which
        // is what makes the leash break observable at all.
        // Non-engaged: use the full detection envelope (16) so a ranged
        // hostile kiting at 9–15 blocks is seen and REFUSED, not missed
        // and reported as SUCCESS ("area clear").
        double scanRadius = engaged() ? Math.max(ENGAGE_RADIUS, LEASH_RADIUS + 2) : DETECTION_RADIUS;
        List<EntitySnapshot> hits = world.getEntities(center, scanRadius, ViewMode.LIVE);
        List<EntitySnapshot> out = new ArrayList<>();
        for (EntitySnapshot e : hits) {
            if (hostileTypes.contains(e.type())) {
                out.add(e);
            }
        }
        return out;
    }

    private EntitySnapshot nearestHostile(WorldView world, CellPos center) {
        EntitySnapshot best = null;
        double bestDist = Double.MAX_VALUE;
        for (EntitySnapshot e : scanHostiles(world, center)) {
            double dist = center.distanceTo(e.pos());
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    /**
     * Resolve the engaged target by identity within the hostile scan.
     * Used by the engaged branch so that a nearer hostile does not
     * make the current target "disappear" — the scan is the same
     * {@link #scanHostiles} call, the selector is id-match instead
     * of min-distance.
     *
     * @param world  perception surface; never null
     * @param center search origin; never null
     * @param id     engaged target identity; never null
     * @return the matching snapshot, or {@code null} if absent from
     *         the scan radius
     */
    private EntitySnapshot findTarget(WorldView world, CellPos center, String id) {
        for (EntitySnapshot e : scanHostiles(world, center)) {
            if (id.equals(e.id())) {
                return e;
            }
        }
        return null;
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
