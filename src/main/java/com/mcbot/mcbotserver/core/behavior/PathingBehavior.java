package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.Goals;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.pathing.AStarPathFinder;
import com.mcbot.mcbotserver.core.pathing.MoveGraph;
import com.mcbot.mcbotserver.core.pathing.PlanSmoother;
import com.mcbot.mcbotserver.core.pathing.PlanWorker;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Waypoint follower over the A* move graph: plans through the finder,
 * steers toward the next waypoint, and replans when the plan runs out,
 * drifts away, or the progress fuse fires.
 *
 * <p>Contract: see boundaries.md decisions 7/8 and ADR-0004 D3/D4 -
 * SUCCESS comes only from the goal predicate evaluated on the bot cell;
 * STUCK is declared here but only when a recovery replan also failed -
 * a frozen body with a fresh, usable route stays silent and simply waits
 * out the replan cooldown with claims flowing. Replan triggers: plan
 * exhausted, body drifted beyond {@value #REPLAN_DISTANCE} of the active
 * waypoint, or {@value PlanProgressFuse#STUCK_WINDOW} ticks without plan-progress.
 *
 * <p>Decomposition: this class is the orchestration shell - it owns the
 * tuning constants, the constructor surface, steering, and the
 * STUCK / NO_PATH verdicts; each concern's state machine lives beside
 * it in the package:
 * <ul>
 *   <li>{@link WaypointCursor} - the active plan chain and cursor</li>
 *   <li>{@link PlanProgressFuse} - ledger 20 progress
 *       criteria, the accumulator immune to replan triggers,
 *       and the repeat-report latch</li>
 *   <li>{@link ReplanGate} - cooldown rationing plus the ledger-20
 *       vertical gate and landing-edge bypass</li>
 *   <li>{@link PlanLifecycle} - search request, synchronous compute,
 *       and the Chebyshev freshness guard (decision 17b)</li>
 * </ul>
 * Those class docs carry the load-bearing invariant language; change
 * behaviour there and here in lockstep.
 *
 * <p>Implementation note: runs on the server tick thread only. With a
 * {@link PlanWorker} injected, searches execute off-thread over an
 * immutable snapshot and are adopted only when still fresh (goal
 * unchanged, start cell still ours) - the staleness guard of decision
 * 17b, enforced inside {@link PlanLifecycle}. All behavior state stays
 * tick-thread-local; the future is the sole cross-thread object.
 */
// contract: see ADR-0004 D3 + numen-notes.md section 18 (replan ladder)
public final class PathingBehavior implements Behavior {

    /**
     * Horizontal reach that counts as "waypoint touched", in metres.
     * Frame: XZ only (`Math.hypot(dx, dz)` in
     * {@link WaypointCursor#advance} and {@code distanceToSegment}).
     * Y is intentionally ignored - a position directly above a waypoint
     * is treated as "not at the waypoint" for reach purposes; the
     * goal predicate is the 3D cell-equality authority for arrival.
     */
    public static final double WAYPOINT_REACH = 0.8;

    /**
     * Drift from the walked segment (previous to current waypoint)
     * that forces a fresh plan, in metres. Frame: XZ only. Before
     * smoothing, this measured distance to the current waypoint -
     * equivalent while waypoints were one cell apart. Smoothing
     * (issue 0005 P1.2) makes mid-segment waypoint distances large
     * and legitimate, so the drift signal moved to the segment the
     * body is actually walking. Y drift is intentionally ignored;
     * the three-branch failure mode documented in issue 0001 §3
     * rides on this XZ-only choice. Pinned by
     * {@code PathingBehaviorFrameGateTest.replanDriftIsXZToSegment}.
     */
    public static final double REPLAN_DISTANCE = 3.0;

    /**
     * Soft wall-clock cap for one off-thread search. The node budget
     * is the safety net; this is the CPU throttle the worker owns
     * (numen-notes section 17).
     *
     * <p>Must stay generous enough for a bounded arena's exhaustive
     * no-route search to CONCLUDE: a budget-cut unreachable-goal
     * search returns a best-effort partial, which reads as a real
     * route and converts an honest NO_PATH into a late STUCK (the
     * failscleanly gametest pins this). 50ms was enough before the
     * swim vocabulary grew the edge set; the worker runs off the
     * tick thread, so the larger cap costs tick latency nothing.
     */
    public static final long PLAN_WALL_CLOCK_MS = 200;

    /**
     * Steer pitch limit, in degrees. Presentation only (issue 0005
     * P0): the look pitch toward the steer waypoint follows terrain
     * so the head reads as going somewhere, but a waypoint almost
     * straight overhead (deep-water surface ascent) must not pin the
     * view at -90; past this limit the glance stops climbing. Frame:
     * degrees, symmetric up/down, engine sign (negative = up).
     */
    public static final float STEER_PITCH_LIMIT_DEG = 60f;

    /**
     * Ticks a fresh (or re-targeted) mission holds before the first
     * drive claim (issue 0005 P2.1). Zero-to-full motion on the
     * directive tick is the device-read the issue diagnoses; a short
     * stand-still reads as deciding-then-going. Planning still runs
     * during the hold, so the plan is ready the moment the body
     * moves.
     */
    public static final int DEPARTURE_DELAY_TICKS = 4;

    /**
     * Remaining XZ distance to the plan's terminal waypoint at which
     * the arrival brake starts scaling drive down (issue 0005
     * P2.2). Friction plus reduced drive replaces the brake-free
     * full-speed stop.
     */
    public static final double BRAKE_DISTANCE = 3.0;

    /**
     * Floor for the scaled drive near the goal - never zero, or the
     * body could stall short of the goal predicate with no claim
     * pushing it in (issue 0005 P2.2).
     */
    public static final double ARRIVE_MIN_DRIVE = 0.35;

    private final String name;
    private final BodyPositionSource positionSource;
    private final OnGroundSource onGroundSource;
    private final PlanLifecycle lifecycle;
    private final WaypointCursor cursor = new WaypointCursor();
    private final PlanProgressFuse fuse = new PlanProgressFuse();
    private final ReplanGate gate = new ReplanGate();
    private final NoPathEscalator noPath = new NoPathEscalator(AStarPathFinder.DEFAULT_NODE_BUDGET);
    private int ticksSinceAdoption;
    private Goal lastGoal;
    private int departHoldTicks;
    /**
     * Body contact state accessor. Drives the vertical gate in
     * {@link #tick} (issue 0001 fix 6): trigger evaluation is
     * suppressed when the body is airborne and bypasses the replan
     * cooldown on the landing edge.
     *
     * <p>For real entities the wiring is {@code () -> body.onGround()};
     * for offline tests the two-argument and three-argument
     * constructors use {@code () -> true} (always grounded), which
     * is correct because the rig's position is stationary.
     */
    @FunctionalInterface
    public interface OnGroundSource {

        /**
         * Whether the body's feet are on a solid surface this tick.
         *
         * @return true iff the body is in ground contact
         */
        boolean isOnGround();
    }

    /**
     * Creates a follower over one move graph, planning synchronously
     * on the tick thread, assuming the body is always grounded.
     * Offline tests and tiny graphs only - real bots use the worker
     * constructor (or the four-/five-argument variants below).
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param positionSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     */
    public PathingBehavior(String name, BodyPositionSource positionSource, MoveGraph graph) {
        this(name, positionSource, () -> true, graph, null);
    }

    /**
     * Creates a follower that plans off-thread through the given
     * worker, assuming the body is always grounded. Offline tests
     * and tiny graphs only - real bots use the five-argument
     * constructor below.
     *
     * @param name       stable identity for claims and diagnostics;
     *                   never null or blank
     * @param positionSource body position accessor; never null
     * @param graph      edge supplier for planning; never null
     * @param worker     search executor; never null
     */
    public PathingBehavior(String name, BodyPositionSource positionSource, MoveGraph graph, PlanWorker worker) {
        this(name, positionSource, () -> true, graph, worker);
    }

    /**
     * Creates a follower over one move graph, planning synchronously
     * on the tick thread, with an explicit ground-state accessor.
     * Offline tests and tiny graphs only - real bots use the
     * five-argument constructor.
     *
     * @param name            stable identity for claims and
     *                        diagnostics; never null or blank
     * @param positionSource      body position accessor; never null
     * @param onGroundSource  body contact-state accessor; never null
     * @param graph           edge supplier for planning; never null
     */
    public PathingBehavior(
            String name, BodyPositionSource positionSource, OnGroundSource onGroundSource, MoveGraph graph) {
        this(name, positionSource, onGroundSource, graph, null);
    }

    /**
     * Creates a follower that plans off-thread through the given
     * worker, with an explicit ground-state accessor. Searches
     * read an immutable snapshot captured here on the tick thread;
     * results are adopted only when still fresh (goal unchanged,
     * start cell still ours) - decision 17b's staleness guard.
     *
     * @param name            stable identity for claims and
     *                        diagnostics; never null or blank
     * @param positionSource      body position accessor; never null
     * @param onGroundSource  body contact-state accessor; never null
     * @param graph           edge supplier for planning; never null
     * @param worker          search executor; never null
     */
    public PathingBehavior(
            String name,
            BodyPositionSource positionSource,
            OnGroundSource onGroundSource,
            MoveGraph graph,
            PlanWorker worker) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.positionSource = positionSource;
        this.onGroundSource = onGroundSource;
        // Null worker is the deliberate synchronous-planning mode
        // used by offline tests; see the two-, three-, and
        // four-argument constructors above.
        this.lifecycle = new PlanLifecycle(graph, worker);
    }

    @Override
    public ExecutionReport tick(WorldView world, Directive directive, Actor actor) {
        if (directive == null) {
            lastGoal = null;
            departHoldTicks = 0;
            return ExecutionReport.running();
        }
        Vec3 position = positionSource.get();
        ticksSinceAdoption++;
        if (!directive.goal().equals(lastGoal)) {
            lastGoal = directive.goal();
            departHoldTicks = DEPARTURE_DELAY_TICKS;
            // A new goal retires all unreachability evidence: partial
            // witnesses are only comparable within one goal.
            noPath.reset();
        }

        // Vertical gate (ledger 20): the gate owns ground
        // sampling, the landing edge, and cooldown rationing. Mid-air
        // ticks still steer toward the current waypoint (the bot moves
        // through the air on its trajectory), but they do not score
        // progress, do not accumulate the fuse, and do not request
        // replans - those would all be measured against a snapshot
        // the freshness check will discard on landing.
        var window = gate.beginTick(onGroundSource.isOnGround());
        boolean evaluateTriggers = window.evaluateTriggers();

        CellPos cell = floorOf(position);
        if (directive.goal().isInGoal(cell)) {
            // Forget the goal so an immediately re-issued goto to the
            // same cell is a new departure and re-arms the P2.1 hold.
            lastGoal = null;
            resetPlan();
            return ExecutionReport.success();
        }

        // Adopt finished searches before trigger evaluation so a
        // fresh route is usable in the same tick it arrived.
        // Adoption is not gated by the vertical gate: a plan that
        // completes during flight is still adopted (the next
        // ground tick immediately has a usable plan), and the
        // freshness check is the cell-based guard regardless of
        // ground state.
        applyAdoption(lifecycle.adoptIfReady(directive.goal(), cell), world, directive.goal());
        if (noPath.tripped()) {
            resetPlan();
            return ExecutionReport.failed("NO_PATH");
        }

        if (evaluateTriggers) {
            ExecutionReport verdict = triggerVerdict(world, directive.goal(), position, cell, window);
            if (verdict != null) {
                return verdict;
            }
        }

        // Vertical climb segments use Y-aware cursor advance: the XZ-only
        // reach in WaypointCursor.advance would consume a directly-overhead
        // waypoint on tick one (horizontal ~0 <= WAYPOINT_REACH), exhausting
        // the plan before the body has climbed. The climb gate requires the
        // floor cell to be climbable — a vertical waypoint over open air is
        // not a climb and keeps the XZ-only contract.
        if (isVerticalClimb(world, cell, cursor)) {
            cursor.advanceClimb(cell);
        } else {
            cursor.advance(position);
        }
        if (cursor.isEmpty()) {
            // No usable plan yet (a search is still in flight after
            // a reset): nothing to steer toward this tick.
            return ExecutionReport.running();
        }
        if (departHoldTicks > 0) {
            // Departure hold (issue 0005 P2.1): planning and cursor
            // bookkeeping above already ran; only the drive claims
            // wait. A body standing briefly before moving is the
            // point.
            departHoldTicks--;
            return ExecutionReport.running();
        }
        // No exhausted early-return: steerTarget() clamps to the
        // last waypoint once the cursor runs past it, so a brake
        // undershoot keeps pushing into the goal cell instead of
        // idling one cell short until the mission budget dies.
        steerTowardCurrentWaypoint(world, position, actor);
        return ExecutionReport.running();
    }

    /**
     * Plan-health evaluation for one trigger-eligible (ground)
     * tick: fuse scoring, the replan decision, and the emptiness
     * verdicts. Every verdict below this layer - cursor advance,
     * departure hold, steering - belongs to a tick that still has
     * a usable plan.
     *
     * @param world   read-only perception for the replan search;
     *                never null
     * @param goal    the active goal; never null
     * @param position current body position; never null
     * @param cell    the floored position cell; never null
     * @param window  the vertical gate's verdict for this tick;
     *                never null
     * @return a report to return from this tick (failed NO_PATH /
     *         STUCK, running while a search is in flight, stuck
     *         once between replan cooldowns); null when the tick
     *         should proceed to cursor advance
     */
    private ExecutionReport triggerVerdict(
            WorldView world, Goal goal, Vec3 position, CellPos cell, ReplanGate.TickWindow window) {
        evaluatePlanProgress(position, goal);
        boolean fuseCondition = fuse.shouldFire();
        boolean offPath = !cursor.exhausted()
                && distanceToSegment(position, cursor.previous(), cursor.current()) > REPLAN_DISTANCE;
        boolean exhausted = cursor.exhausted();

        // Replan cooldown bypassed on the landing edge: contact
        // is the first tick the body's position is meaningful for
        // pathing, and a stale plan from before take-off must
        // be replaced immediately rather than after the
        // cooldown elapses.
        if ((fuseCondition || offPath || exhausted) && gate.mayRequest(window)) {
            gate.onRequest();
            applyAdoption(lifecycle.request(world, cell, goal, noPath.nextNodeBudget()), world, goal);
            if (noPath.tripped()) {
                resetPlan();
                return ExecutionReport.failed("NO_PATH");
            }
        }
        return settleCursorVerdict(fuseCondition);
    }

    /**
     * Plan-progress score (ledger 20, Path A).
     *
     * <p>Three OR criteria; any one counts as progress and
     * resets the accumulator. External replan (exhaustion,
     * offPath, freshness drop) MUST NOT clear the
     * accumulator - only real plan-progress or the fuse
     * itself does. The previous motion detector is gone:
     * it was strictly weaker than plan-progress for the
     * "moving but not progressing" case (limbo body in
     * free fall - large per-tick 3D displacement, zero
     * waypoint-progress).
     */
    private void evaluatePlanProgress(Vec3 position, Goal goal) {
        if (fuse.evaluate(cursor, position, Goals.cellOf(goal))) {
            fuse.onProgress();
        } else {
            fuse.onStall();
        }
    }

    /**
     * Verdict for a tick where the cursor holds nothing new: a search
     * in flight reports RUNNING rather than inventing NO_PATH for a
     * question in flight; a definitive no-path fails cleanly; a fuse
     * firing while frozen between replan cooldowns latches and
     * reports STUCK once.
     *
     * @param fuseCondition whether the progress fuse is currently firing
     * @return the terminal/interim report, or null when cursor advance
     *         should proceed
     */
    private ExecutionReport settleCursorVerdict(boolean fuseCondition) {
        if (cursor.isEmpty()) {
            // A search still running is not an answer: report
            // RUNNING rather than inventing NO_PATH for a
            // question in flight.
            if (lifecycle.inFlight()) {
                return ExecutionReport.running();
            }
            // Definitive no-path right now: fail cleanly; the
            // mission process owns the terminal transition
            // from here. A fuse-triggered failure reports
            // STUCK (physically immovable), a planning-side
            // one reports NO_PATH.
            if (fuseCondition) {
                fuse.latch();
                return ExecutionReport.failed("STUCK");
            }
            return ExecutionReport.failed("NO_PATH");
        }
        if (fuseCondition && !fuse.latched()) {
            // Fresh-plan grace expired while the body stayed
            // frozen and the replan cooldown is still blocking
            // the next attempt. Report once, latch, keep
            // claims flowing; movement or a later successful
            // plan clears the latch.
            fuse.latch();
            return ExecutionReport.stuck("frozen between replan cooldowns");
        }
        return null;
    }

    /**
     * Apply an adoption verdict to the follower's collaborators.
     * Both arrival paths funnel here - the synchronous answer from
     * {@code request} and the asynchronous one from {@code
     * adoptIfReady} - so the application side effects (cursor install,
     * adoption-age reset, fuse latch refresh) exist in exactly one
     * place.
     *
     * @param adoption the verdict to apply; never null
     * @param world    read-only perception for smoothing; never null
     * @param goal     the goal the adoption answers; never null
     */
    private void applyAdoption(PlanLifecycle.Adoption adoption, WorldView world, Goal goal) {
        switch (adoption.outcome()) {
            case ADOPTED -> {
                // Skip index 0: it is the cell we are standing in.
                // Smoothing runs here (issue 0005 P1.2/P1.3) so both
                // adoption paths (sync and worker) get the simplified
                // chain, and the corridor checks read the CURRENT
                // world, not the search snapshot.
                cursor.set(PlanSmoother.smooth(world, adoption.plan()));
                ticksSinceAdoption = 0;
                fuse.onAdopted(cursor.index());
                // Complete routes retire the unreachability ledger;
                // partials become witnesses (see NoPathEscalator).
                if (goal.isInGoal(cursor.last())) {
                    noPath.onRouteComplete();
                } else {
                    noPath.onAdopted(goal, cursor.last());
                }
            }
            case NO_ROUTE -> cursor.clear();
            case STALE -> {
                // The discarded answer was for a question we no longer
                // ask. Prime the ladder so THIS tick's trigger
                // evaluation immediately requests a plan for the
                // current goal - the cooldown guards executing routes,
                // not unanswered goals, and reporting NO_PATH without
                // asking would be a lie.
                gate.primeLadder();
            }
            case NOT_READY -> {}
        }
    }

    private void resetPlan() {
        cursor.clear();
        gate.reset();
        lifecycle.discardPending();
        noPath.reset();
    }

    /**
     * Snapshot of the plan-progress state for the keepalive event
     * (issue 0001 fix 2). The harness reads this on every KEEPALIVE
     * emission to distinguish "alive and progressing" from "alive
     * but the planner is silent". Zero behaviour change to the
     * pipeline - the snapshot is read at the BotController emission
     * point and never feeds back into PathingBehavior.
     *
     * <p>Why this method, not getters: a single {@code Map} return
     * keeps the emission point in BotController free of cast /
     * null-bridges per field. The map keys are stable strings; the
     * caller copies them into a {@code BotEvent} attrs map.
     *
     * @param position     the body's current position (sampled by the caller
     *                 in the same tick); never null
     * @param goalCell the active goal cell (sampled by the caller
     *                 from the directive); may be null if no
     *                 directive is active this tick
     * @return a fresh {@code LinkedHashMap} of attribute strings;
     *         never null
     */
    public Map<String, String> keepaliveAttrs(Vec3 position, CellPos goalCell) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("pose", position.x() + "," + position.y() + "," + position.z());
        attrs.put("waypointIndex", String.valueOf(cursor.index()));
        attrs.put("waypointsTotal", String.valueOf(cursor.size()));
        // Renamed from "ticksSinceProgress" in PR-2. The field
        // now counts ticks without PLAN-PROGRESS (one of the
        // three Ruling (a) criteria), not ticks without MOTION.
        // The key name change is intentional and the S-F
        // verifier-side impact is "ignore unknown attrs" (issue
        // 0001 §7 fix-2 contract).
        attrs.put("ticksSincePlanProgress", String.valueOf(fuse.ticksWithoutProgress()));
        attrs.put("ticksSincePlan", String.valueOf(gate.ticksSinceRequest()));
        attrs.put("planAge", String.valueOf(ticksSinceAdoption));
        // Unreachability evidence: how many consecutive partial
        // adoptions stopped approaching the goal (NoPathEscalator).
        // WITNESS_LIMIT of these precede a NO_PATH verdict - a
        // harness watching a crawl can see the verdict coming.
        attrs.put("noPathWitnesses", String.valueOf(noPath.witnesses()));
        if (goalCell != null) {
            attrs.put("goalCell", goalCell.x() + "," + goalCell.y() + "," + goalCell.z());
        }
        return attrs;
    }

    /**
     * XZ distance from the body to the walked segment (previous to
     * current waypoint), clamped to the segment ends. Replaces the
     * pre-smoothing distance-to-waypoint drift measure: with merged
     * segments, a body mid-leg is legitimately far from the current
     * waypoint, and only lateral departure from the segment means
     * the plan no longer describes reality. Frame: XZ only, same
     * convention as {@code WAYPOINT_REACH}.
     *
     * @param position the body's current fine position; never null
     * @param from     the segment origin; never null
     * @param to       the segment's far end; never null
     * @return non-negative distance in metres
     */
    static double distanceToSegment(Vec3 position, CellPos from, CellPos to) {
        double ax = from.x() + 0.5;
        double az = from.z() + 0.5;
        double abx = to.x() + 0.5 - ax;
        double abz = to.z() + 0.5 - az;
        double lenSq = abx * abx + abz * abz;
        double t = lenSq == 0 ? 0 : ((position.x() - ax) * abx + (position.z() - az) * abz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(position.x() - (ax + abx * t), position.z() - (az + abz * t));
    }

    /**
     * Whether the active waypoint is a pure-vertical climb leg: same
     * XZ as the body's floor cell, strictly higher, and the floor cell
     * carries the climbable trait. Drives two execution overrides:
     * (1) the cursor advances Y-aware (see {@link WaypointCursor#advanceClimb})
     * instead of XZ-only; (2) the steer yaw points into the ladder
     * wall (see {@link #steerTowardCurrentWaypoint}) rather than the
     * atan2(0,0)=0 fallback that walks away from the rungs.
     *
     * @param world  read-only perception for the climbable trait; never null
     * @param floor  the body's current floor cell; never null
     * @param cursor the active plan cursor; never null
     * @return true iff the current waypoint is a vertical climb leg
     */
    static boolean isVerticalClimb(WorldView world, CellPos floor, WaypointCursor cursor) {
        if (cursor.isEmpty() || cursor.exhausted()) {
            return false;
        }
        CellPos wp = cursor.current();
        return wp.x() == floor.x()
                && wp.z() == floor.z()
                && wp.y() > floor.y()
                && world.getBlockTraits(floor, ViewMode.LIVE).climbable();
    }

    private void steerTowardCurrentWaypoint(WorldView world, Vec3 position, Actor actor) {
        CellPos wp = cursor.steerTarget();
        CellPos floor = floorOf(position);
        boolean climbing = isVerticalClimb(world, floor, cursor);
        double dx = wp.x() + 0.5 - position.x();
        double dz = wp.z() + 0.5 - position.z();
        // Climb yaw override: a pure-vertical waypoint sits directly
        // overhead, so atan2(-dx, dz) degenerates to 0 (south). The
        // ladder's flush-plate shape tells us which wall the rungs are
        // on; steering into that wall sets horizontalCollision, which
        // vanilla onClimbable needs to lift the body. A non-climbable
        // cell or a non-flush shape falls back to the normal derivation.
        float yaw;
        if (climbing) {
            double plateYaw = world.getCollisionShape(floor, ViewMode.LIVE).flushPlateYaw();
            yaw = Double.isNaN(plateYaw) ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : (float) plateYaw;
        } else {
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        // Jump only when the current waypoint sits above the body's
        // floor cell AND this is not a vertical climb. On a ladder the
        // climbable gate in travel() supplies upward velocity once
        // horizontalCollision is set; an explicit jump would add 0.42
        // on top and can eject the body from the ladder column.
        boolean jumpForWaypoint = wp.y() > floor.y() && !climbing;
        // Sneak is the execution half of a SwimDown edge only: the
        // waypoint below a body already standing in liquid. In water
        // the shift flag maps to downward thrust (BotBodyEntity
        // applyWaterDescent). The medium check is load-bearing here,
        // not the executor's concern alone - on land the same flag
        // engages the sneak edge guard (maybeBackOffFromEdge), which
        // pins every Drop and step-down leg at the ledge, the
        // corridor-STUCK regression this gate closes.
        boolean sneakForWaypoint = sneakForLeg(wp, floor, liquid(world, floor));
        // Arrival brake (issue 0005 P2.2): scale drive down as the
        // terminal waypoint closes, floored at ARRIVE_MIN_DRIVE so
        // the body always creeps into the goal predicate.
        CellPos end = cursor.last();
        double endDist = Math.hypot(end.x() + 0.5 - position.x(), end.z() + 0.5 - position.z());
        double forward = Math.min(1.0, Math.max(ARRIVE_MIN_DRIVE, endDist / BRAKE_DISTANCE));
        actor.submit(new Claim(Channel.MOVE, 10, name, new Intent.Move(forward, 0, jumpForWaypoint, sneakForWaypoint)));
        actor.submit(new Claim(Channel.ROT, 10, name, new Intent.Look(yaw, steerPitch(position, wp))));
    }

    /**
     * Whether the steering leg down to {@code wp} earns the sneak
     * flag: only a SwimDown edge does - the waypoint below a floor
     * cell that is itself liquid. {@code BasicMoves.SwimDown}
     * viability demands a liquid source, so the gate matches the
     * edge type exactly and land legs (Drop, step-down) never hold
     * shift; a sneaking body cannot leave a ledge (the edge guard),
     * so a land sneak would deadlock every descending leg.
     *
     * @param wp             the steer target cell; never null
     * @param floor          the cell containing the body's feet; never
     *                       null
     * @param floorIsLiquid  whether the floor cell carries the liquid
     *                       trait
     * @return true only for a water descent leg
     */
    static boolean sneakForLeg(CellPos wp, CellPos floor, boolean floorIsLiquid) {
        return wp.y() < floor.y() && floorIsLiquid;
    }

    private static boolean liquid(WorldView world, CellPos cell) {
        return world.getBlockTraits(cell, ViewMode.LIVE).liquid();
    }

    /**
     * Terrain-following look pitch toward the steer waypoint, engine
     * sign convention (negative = up). Computed feet-to-feet: the
     * waypoint's standable floor sits at {@code wp.y()} and the body's
     * feet at {@code position.y()}, so eye heights cancel on both ends
     * and the pitch says "how much vertical travel the next leg has".
     * A flat leg reads exactly 0. Clamped to
     * {@link #STEER_PITCH_LIMIT_DEG}; presentation only (issue 0005
     * P0) - no physics consumes this value.
     *
     * @param position the body's current fine position; never null
     * @param wp       the steer target cell; never null
     * @return pitch in degrees within
     *         {@code [-STEER_PITCH_LIMIT_DEG, +STEER_PITCH_LIMIT_DEG]}
     */
    static float steerPitch(Vec3 position, CellPos wp) {
        double dy = wp.y() - position.y();
        double horizontal = Math.hypot(wp.x() + 0.5 - position.x(), wp.z() + 0.5 - position.z());
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Math.max(-STEER_PITCH_LIMIT_DEG, Math.min(STEER_PITCH_LIMIT_DEG, pitch));
    }

    static CellPos floorOf(Vec3 position) {
        return new CellPos(
                (int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()));
    }
}
