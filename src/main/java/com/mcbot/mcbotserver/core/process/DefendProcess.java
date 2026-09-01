package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.capability.Feature;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.goal.GoalRange;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.combat.RangedLoadouts;
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
 * melee-incompatible hostile is refused with ENGAGEMENT_REFUSED -
 * unless the inventory carries a ranged loadout (bow + arrows), in
 * which case the fight is engaged at RANGED_STANDOFF and answered
 * with the bow (ledger 37's USE hold-draw).
 *
 * <p>Engagement is weapon-aware, not only target-aware: a melee-
 * typed hostile is charged to the swing rim only when the hotbar
 * answers melee better than the bow. A bow-only carrier that runs to
 * the swing rim trades full-draw arrows for fist-tier clubbing, so
 * it opens the same fights at the standoff rim instead and fires
 * while the target closes - inside melee reach the behavior tier
 * keeps the bow answering until the fight ends.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md decision 11 (light planner output)
public final class DefendProcess extends MissionShell {

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

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final double LEASH_RADIUS = TargetTracker.LEASH_RADIUS;

    /** Shared envelope; canonical doc in {@link TargetTracker}. */
    public static final int TARGET_GRACE_TICKS = TargetTracker.TARGET_GRACE_TICKS;

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

    /**
     * Center of the standoff band for fights answered with the bow:
     * inside the bow band (CombatBehavior.BOW_RANGE = 15) so the body
     * can answer, beyond ATTACK_REACH so the fight does not collapse
     * into a sword chase of a kiting target. The actual goal is a
     * {@link GoalRange} band of {@code RANGED_STANDOFF ± 2} (8..12):
     * the mover closes to the outer edge and holds; a closing target
     * that pushes the body inside 8 triggers a backward replan to the
     * nearest band edge. This is the issue-0018 keep-range channel.
     *
     * <p>Against a closing melee target the band is a maintained range,
     * not just an opening posture: the lower bound makes the bot back
     * away rather than let the target walk through to point-blank.
     * Point-blank fire still answers if the bot cannot escape (cornered,
     * no path outward), but the planner's first choice is to hold the
     * band.
     */
    public static final int RANGED_STANDOFF = 10;

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

    private final Supplier<CellPos> positionSource;
    private final Set<String> hostileTypes;
    private final Set<String> rangedTypes;
    private final WeaponCatalog weapons;
    private String lastRefusedType;

    private boolean succeeded;
    private String failure;
    private String targetId;
    private boolean targetRanged;
    private final TargetTracker tracker = new TargetTracker();
    private Directive lastDirective;

    /**
     * Creates a defend mission that engages every hostile type, read
     * without a weapon catalog: any carried bow then outranks
     * everything, so bow carriers open melee-target fights at the
     * standoff rim too.
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
        this(taskId, priority, timeoutTicks, positionSource, hostileTypes, Set.of(), WeaponCatalog.none());
    }

    /**
     * Creates a defend mission with an explicit ranged-refusal set,
     * read without a weapon catalog (see the seven-arg form).
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
        this(taskId, priority, timeoutTicks, positionSource, hostileTypes, rangedTypes, WeaponCatalog.none());
    }

    /**
     * Creates a defend mission with an explicit ranged-refusal set and
     * the weapon ranking the engagement decision reads.
     *
     * @param taskId       boundary-D task id for tracing; never null
     * @param priority     band-legal priority per PriorityBands
     * @param timeoutTicks tick budget; positive
     * @param positionSource   body cell accessor; never null
     * @param hostileTypes entity types worth engaging; never null
     * @param rangedTypes  hostile types whose tactics defeat melee -
     *                     these are REFUSED at engage time instead of
     *                     chased; never null
     * @param weapons      per-hit melee damage ranking; never null
     */
    public DefendProcess(
            String taskId,
            int priority,
            long timeoutTicks,
            Supplier<CellPos> positionSource,
            Set<String> hostileTypes,
            Set<String> rangedTypes,
            WeaponCatalog weapons) {
        super(taskId, priority, timeoutTicks);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.hostileTypes = Set.copyOf(Objects.requireNonNull(hostileTypes, "hostileTypes"));
        this.rangedTypes = Set.copyOf(Objects.requireNonNull(rangedTypes, "rangedTypes"));
        this.weapons = Objects.requireNonNull(weapons, "weapons");
    }

    @Feature(
            id = "combat.hostile_acquisition.tactical_engagement",
            face = "combat.hostile_acquisition",
            description = "Tactical engagement decision: nearest hostile in 8-block engage radius. Ranged types are"
                    + " REFUSED without a bow (ENGAGEMENT_REFUSED). Bow-only carriers open at the 10-block standoff"
                    + " rim and fire while the target closes (point-blank bow), instead of charging to 2 and clubbing"
                    + " for fist-tier damage. Melee-typed targets with a better melee weapon charge to 2.",
            vanillaRef = "Hostile mob AI + player combat decision-making (decompiled 1.20.1)",
            deviation = "Bot-specific: weapon-aware engagement routing — a bow-only carrier does not charge to melee"
                    + " range; it keeps firing at standoff. Target switching is explicit (death/leash/policy), never"
                    + " a side effect of nearestHostile ordering.")
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
        if (!engaged()) {
            EntitySnapshot nearest = nearestHostile(world, position);
            if (nearest == null) {
                // Nothing to defend against: the mission's purpose is
                // already fulfilled.
                succeed();
                return lastDirective;
            }
            if (rangedTypes.contains(nearest.type())) {
                // A kiting skeleton is only unwinnable WITHOUT a bow:
                // armed, the fight is answered at standoff - the mover
                // closes to RANGED_STANDOFF, the combat behavior draws
                // inside the bow band, and melee still takes over if
                // the target closes in. Unarmed, refusing NOW beats
                // bleeding to leash or timeout - the harness sees the
                // refusal (with the threat type) and decides.
                if (RangedLoadouts.hotbarBowSlot(world) < 0) {
                    lastRefusedType = nearest.type();
                    fail(REASON_REFUSED);
                    return lastDirective;
                }
                engageRanged(nearest);
                return directiveFor();
            }
            // Melee-typed target: charge the swing rim only when the
            // hotbar answers melee better than the bow. A bow-only
            // carrier that closes to 2 trades full-draw arrows for
            // bow clubbing, so it opens at the standoff rim and fires
            // while the target closes; the behavior tier keeps the
            // bow answering point-blank when the target arrives.
            if (RangedLoadouts.hotbarBowSlot(world) >= 0 && !RangedLoadouts.meleeWeaponBeatsBow(world, weapons)) {
                engageRanged(nearest);
                return directiveFor();
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
            tracker.sighted(current.pos());
            if (tracker.leashed(position)) {
                fail(REASON_LOST);
                return lastDirective;
            }
            if (!rerouteOnLoadoutChange(world, current)) {
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
        // The tracker freezes the sighting-before-absence order
        // (TargetTracker class doc); resume() spends the grace as
        // instant adjudication - a target still present resets the
        // counter and the fight continues, an absent one trips the
        // verdict immediately.
        if (tracker.graceSpent()) {
            fail(REASON_ESCAPED);
            return lastDirective;
        }
        return directiveFor();
    }

    @Override
    public void onExecutionReport(ExecutionReport report) {
        if (!live()) {
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

    @Feature(
            id = "combat.hostile_acquisition.resume_blind_trust",
            face = "combat.hostile_acquisition",
            description = "Resume blind-trust guard: after reflex preemption, resume() spends ALL grace credit so the"
                    + " very next onTick scan adjudicates immediately. Prevents stale steering from a pre-pause"
                    + " target from persisting through the full grace window.",
            vanillaRef = "No direct vanilla counterpart — player-controlled combat has no reflex preemption",
            deviation = "Bot-specific: boundary-C reflex preemption can park a fight mid-engagement; resume must not"
                    + " blindly trust the pre-pause target state.")
    @Override
    public boolean resume(InterruptionContext c) {
        if (!live()) {
            return false;
        }
        // Blind-trust guard: resume() has no world access, so instead
        // of trusting the pre-pause target we spend ALL grace credit
        // here - the very next onTick scan adjudicates.
        tracker.spendAllGrace();
        return true;
    }

    @Override
    public void onContextInvalidated() {
        fail(REASON_PREEMPTED);
    }

    @Override
    public String displayName() {
        return "defend:" + taskId();
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

    private boolean engaged() {
        return targetId != null;
    }

    private void engage(EntitySnapshot target) {
        targetId = target.id();
        targetRanged = false;
        tracker.sighted(target.pos());
    }

    private void engageRanged(EntitySnapshot target) {
        engage(target);
        targetRanged = true;
    }

    /**
     * Mid-fight loadout re-evaluation: the ranged stance is re-derived
     * every engaged tick against the live inventory, so arrows running
     * out or a weapon pickup re-routes the fight instead of steering a
     * decision frozen at engage time. Mirrors the engage-time routing
     * exactly - one decision function, no drift between the two sites.
     *
     * <p>The one direction that refuses instead of re-routing is a
     * ranged-typed target whose loadout vanished mid-fight: an unarmed
     * chase against a kiting ranged mob is the bleed the engage-time
     * refusal exists to prevent, mid-fight included.
     *
     * @param world   read surface for the inventory; never null
     * @param current the engaged target, still sighted; never null
     * @return true when the fight continues (stance possibly flipped)
     */
    @Feature(
            id = "combat.hostile_acquisition.mid_fight_loadout_reroute",
            face = "combat.hostile_acquisition",
            description = "Mid-fight loadout re-route: the ranged-vs-melee stance is re-derived every engaged tick"
                    + " from the live inventory (bow loadout presence, melee-weapon ranking), mirroring the"
                    + " engage-time routing. Arrows running out charges the swing rim; picking up a bow opens the"
                    + " standoff; picking up a better melee weapon closes from standoff. A ranged-typed target"
                    + " whose loadout vanishes fails ENGAGEMENT_REFUSED rather than unarmed-chasing a kiter.",
            vanillaRef = "Hostile mob AI re-selects attack options continuously (decompiled 1.20.1)",
            deviation = "Bot-specific: vanilla mobs have fixed attack repertoires; the bot's loadout is dynamic,"
                    + " so the stance decision must be too. Target identity stays locked - only the stance re-routes.")
    private boolean rerouteOnLoadoutChange(WorldView world, EntitySnapshot current) {
        boolean hasRangedLoadout = RangedLoadouts.hotbarBowSlot(world) >= 0;
        boolean rangedTyped = rangedTypes.contains(current.type());
        boolean shouldRange = rangedTyped
                ? hasRangedLoadout
                : hasRangedLoadout && !RangedLoadouts.meleeWeaponBeatsBow(world, weapons);
        if (shouldRange == targetRanged) {
            return true;
        }
        if (shouldRange) {
            targetRanged = true;
            return true;
        }
        if (rangedTyped) {
            lastRefusedType = current.type();
            fail(REASON_REFUSED);
            return false;
        }
        targetRanged = false;
        return true;
    }

    @Feature(
            id = "combat.hostile_acquisition.engagement_range",
            face = "combat.hostile_acquisition",
            description = "Engagement range selection: melee targets chase to GOAL_RANGE=2 (swing-adjacent, GoalNear)."
                    + " Ranged targets hold a GoalRange band 8..12 around the target (centered on RANGED_STANDOFF=10):"
                    + " the planner paths inward when outside 12 and outward when inside 8, so a closing target is"
                    + " kited rather than walked through. Closing to 2 against a kiter hands the initiative to a mob"
                    + " that backs away shooting.",
            vanillaRef = "Skeleton follow range + shoot AI (decompiled 1.20.1)",
            deviation = "Bot-specific: the standoff band is a deliberate tactical choice, not a pathing limit. A"
                    + " bow-only carrier holds 8-12 blocks to trade full-draw arrows instead of clubbing; the lower"
                    + " bound makes the bot back away when a target closes, which GoalNear could never express.")
    private Directive directiveFor() {
        // Bow-answered fights hold a range band instead of the swing-
        // adjacent chase rim: ranged targets because closing to 2 hands
        // the initiative to a kiter that backs away shooting, bow-only
        // carriers because closing trades full-draw arrows for fist-tier
        // clubbing (ledger 51). GoalRange gives the band a lower bound so
        // a closing target triggers a backward replan rather than walking
        // straight through the rim (issue 0018).
        Goal goal = targetRanged
                ? new GoalRange(tracker.targetCell(), RANGED_STANDOFF - 2, RANGED_STANDOFF + 2)
                : new GoalNear(tracker.targetCell(), GOAL_RANGE);
        lastDirective = new Directive(goal, new Overrides(new Attack(targetId)));
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
    @Feature(
            id = "combat.hostile_acquisition.scan_radius",
            face = "combat.hostile_acquisition",
            description =
                    "Dual scan radius: non-engaged uses DETECTION_RADIUS=16 (full hostile awareness envelope, so a"
                            + " kiting ranged mob at 9-15 blocks is seen and REFUSED); engaged uses"
                            + " max(ENGAGE_RADIUS=8, LEASH_RADIUS+2=14) so an escaping target between engage and"
                            + " leash stays visible. Same hostile-type filter, same ViewMode for both paths.",
            vanillaRef = "Hostile mob follow range (decompiled 1.20.1)",
            deviation = "Bot-specific: the engaged scan must extend past the leash radius to make leash-break"
                    + " observable; the non-engaged scan must cover the full detection envelope so ranged hostiles at"
                    + " standoff are not falsely reported as 'area clear'.")
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
        deactivate();
    }

    private void fail(String reason) {
        if (live()) {
            failure = reason;
            deactivate();
        }
    }
}
