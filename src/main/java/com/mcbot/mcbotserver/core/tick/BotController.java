package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.actor.ToolCatalog;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.Goals;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.MinimalReflex;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer.ReflexDecision;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The single tick entry: fixed four-stage pipeline plus the ADR-0005
 * exception state machine wrapped around all of it.
 *
 * <p>Contract: see ADR-0004 D1 (reflex -> arbiter -> behaviors ->
 * actor.flush, one tick latency accepted) and ADR-0005 D1-D5 (one
 * try-catch around the whole pipeline; sticky latch; clear intents
 * before reporting; dual-channel best-effort report; MinimalReflex-only
 * degraded mode; reset vs respawn recovery asymmetry). Reordering the
 * stages is a boundary violation.
 *
 * <p>Body facts (position, health, clock, lethal-fluid flag) arrive
 * through constructor-injected accessors — the controller never imports
 * engine types; the adapter's BotAssembly is the production supplier.
 *
 * <p>Implementation note: runs on the server tick thread only; the
 * Forge server-tick subscription that calls {@link #onTick} lives in
 * the mod entry class — the one place allowed to hold MC-aware wiring.
 */
// contract: see ADR-0004 D1 + ADR-0005 D1 (pipeline order and catch frame)
public final class BotController {

    /** Body-position accessor, block-cell granularity. */
    @FunctionalInterface
    public interface CellPositionSource {

        /**
         * Current block cell of the body.
         *
         * @return the position; never null
         */
        CellPos get();
    }

    /** Body-health accessor, vanilla 0..20 scale. */
    @FunctionalInterface
    public interface HealthSource {

        /**
         * Current body health.
         *
         * @return health points, zero or greater
         */
        float get();
    }

    /** Game-time accessor for event stamping. */
    public interface GameClock {

        /**
         * Current in-game day counter.
         *
         * @return non-negative day
         */
        long day();

        /**
         * Current time-of-day ticks, 0..23999.
         *
         * @return non-negative ticks
         */
        long timeOfDayTicks();
    }

    private final SurvivalReflexLayer reflex;
    private final TaskArbiter arbiter;
    private final List<Behavior> behaviors;
    private final Actor actor;
    private final CellPositionSource positionSource;
    private final HealthSource healthSource;
    private final GameClock clock;
    private final EventQueue events;
    private final CrashReporter crashReporter;
    /**
     * Reflex-owned engage mission factory; null degrades ENGAGE
     * decisions to the freeze hold (rigs without combat wiring park
     * the mission instead of fighting blind).
     */
    private final Supplier<BotProcess> engageMissionFactory;
    /** Mission TASK_* emission and hand-off edge detection. */
    private final MissionReporter missions;
    /** Reflex-owned ENGAGE fight seat bookkeeping. */
    /** Engage-seat resubmit cadence (fights resolve fast). */
    private static final int ENGAGE_RESUBMIT_COOLDOWN = 40;

    /** Pathing-seat resubmit cadence (rescue and forage missions). */
    private static final int PATHING_RESUBMIT_COOLDOWN = 80;

    private final ReflexMissionSeat engageSeat;
    /**
     * Reflex-owned ESCAPE rescue mission factory; null degrades ESCAPE
     * decisions to the freeze hold (rigs without rescue wiring park
     * the mission instead of routing to safety).
     */
    private final Supplier<BotProcess> rescueMissionFactory;
    /** Supplies one fresh forage mission per acquire submission. */
    private final Supplier<BotProcess> hungryMissionFactory;
    /** Reflex-owned ESCAPE rescue seat bookkeeping. */
    private final ReflexMissionSeat rescueSeat;

    /** Reflex-owned forage mission seat (ledger 34, HungryProcess). */
    private final ReflexMissionSeat hungrySeat;

    private long tickCounter;
    private final CrashLatch crashLatch;
    private boolean inLethalFluid;
    private int airSupply = ThreatBlackboard.MAX_AIR_SUPPLY;

    /** Reflex claim construction (aim-and-dig, eat select-and-use). */
    private final ReflexClaimInjector claimInjector;

    /** Mission dig tool auto-selection (hotbar scan + SLOT claim). */
    private final ToolSelector toolSelector;

    /** Reflex park/resume choreography (boundaries.md §C machinery). */
    private final ReflexPreemption preemption;

    private int ticksSinceKeepalive;

    /**
     * How often the keepalive event fires. Matches
     * {@code PlanProgressFuse.STUCK_WINDOW} so a keepalive arrives
     * on the same tick a STUCK would have, were it to fire. Issue
     * 0001 fix 2: not a contract - the S-F verifier ignores unknown
     * kinds, so this can be tuned later without breaking replay.
     */
    private static final int KEEPALIVE_INTERVAL = 20;

    /**
     * Assembles the pipeline without combat, rescue, or forage
     * wiring and without dig tool selection: ENGAGE, ESCAPE and
     * FORAGE reflex decisions degrade to the freeze hold, and mission
     * digs run at vanilla speed. Delegates to the canonical
     * constructor.
     *
     * @param reflex        survival bypass; never null
     * @param arbiter       task-channel winner selector; never null
     * @param behaviors     execution tier; never null, may be empty
     * @param actor         claim surface; never null
     * @param positionSource    body position accessor; never null
     * @param healthSource  body health accessor; never null
     * @param clock         game-time accessor; never null
     * @param events        event stream for the primary crash channel;
     *                      never null
     * @param crashReporter fallback reporter; never null
     */
    public BotController(
            SurvivalReflexLayer reflex,
            TaskArbiter arbiter,
            List<Behavior> behaviors,
            Actor actor,
            CellPositionSource positionSource,
            HealthSource healthSource,
            GameClock clock,
            EventQueue events,
            CrashReporter crashReporter) {
        this(
                reflex,
                arbiter,
                behaviors,
                actor,
                positionSource,
                healthSource,
                clock,
                events,
                crashReporter,
                ToolCatalog.none(),
                null,
                null,
                null);
    }

    /**
     * Assembles the pipeline with combat, rescue, and forage wiring.
     * All collaborators stay plain constructor arguments - no service
     * lookup, no optional wiring.
     *
     * @param reflex        survival bypass; never null
     * @param arbiter       task-channel winner selector; never null
     * @param behaviors     execution tier; never null, may be empty
     * @param actor         claim surface; never null
     * @param positionSource body position accessor; never null
     * @param healthSource  body health accessor; never null
     * @param clock         game-time accessor; never null
     * @param events        event stream for the primary crash channel;
     *                      never null
     * @param crashReporter fallback reporter; never null
     * @param toolCatalog dig-speed seam for mission tool selection;
     *                      never null
     * @param engageMissionFactory supplies one fresh defend mission
     *                      per reflex engage submission; may be null
     * @param rescueMissionFactory supplies one fresh rescue mission
     *                      per reflex escape submission; may be null
     * @param hungryMissionFactory supplies one fresh forage mission
     *                      (HungryProcess) per acquire-food reflex
     *                      submission; may be null - FORAGE then
     *                      degrades to the freeze hold
     */
    public BotController(
            SurvivalReflexLayer reflex,
            TaskArbiter arbiter,
            List<Behavior> behaviors,
            Actor actor,
            CellPositionSource positionSource,
            HealthSource healthSource,
            GameClock clock,
            EventQueue events,
            CrashReporter crashReporter,
            ToolCatalog toolCatalog,
            Supplier<BotProcess> engageMissionFactory,
            Supplier<BotProcess> rescueMissionFactory,
            Supplier<BotProcess> hungryMissionFactory) {
        this.reflex = Objects.requireNonNull(reflex, "reflex");
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.behaviors = List.copyOf(behaviors);
        this.actor = Objects.requireNonNull(actor, "actor");
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.healthSource = Objects.requireNonNull(healthSource, "healthSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.crashReporter = Objects.requireNonNull(crashReporter, "crashReporter");
        this.engageMissionFactory = engageMissionFactory;
        this.rescueMissionFactory = rescueMissionFactory;
        this.hungryMissionFactory = hungryMissionFactory;
        this.missions = new MissionReporter(arbiter, events);
        this.engageSeat = new ReflexMissionSeat(arbiter, ENGAGE_RESUBMIT_COOLDOWN);
        this.rescueSeat = new ReflexMissionSeat(arbiter, PATHING_RESUBMIT_COOLDOWN);
        this.hungrySeat = new ReflexMissionSeat(arbiter, PATHING_RESUBMIT_COOLDOWN);
        this.claimInjector = new ReflexClaimInjector(actor, positionSource::get);
        this.toolSelector = new ToolSelector(Objects.requireNonNull(toolCatalog, "toolCatalog"));
        this.preemption = new ReflexPreemption(
                this.arbiter,
                this.missions,
                this.actor,
                this.claimInjector,
                List.of(this.engageSeat, this.rescueSeat, this.hungrySeat),
                positionSource::get);
        this.crashLatch = new CrashLatch(events, crashReporter, clock, positionSource, this::activeName, this.actor);
    }

    /**
     * Feed the lava flag for this tick; read by MinimalReflex only.
     *
     * @param value true while the body stands in lethal fluid
     */
    public void setInLethalFluid(boolean value) {
        this.inLethalFluid = value;
    }

    /**
     * Feed the air supply for this tick; read by MinimalReflex only
     * (the normal pipeline derives air through the reflex sensor).
     * Defaults to full air so a rig that never calls this reads as
     * breathing — missing data must not mint a crashed-state ascend.
     *
     * @param value current body air supply (vanilla
     *              {@code getAirSupply()}, 0..300)
     */
    public void setAirSupply(int value) {
        this.airSupply = value;
    }

    /**
     * Feed the eat reflex's execution slot: the hotbar slot holding
     * the best food, or -1 when none. Defaults to -1 inside the
     * claim injector, so an unwired rig degrades an EAT decision to
     * the plain freeze hold - stale slot data must not mint a
     * phantom bite.
     *
     * @param supplier best-food hotbar slot 0..8, or -1; never null
     */
    public void setEatSlotSupplier(java.util.function.IntSupplier supplier) {
        this.claimInjector.setEatSlotSupplier(supplier);
    }

    /**
     * Feed the MLG reflex's execution slot: the water-bucket hotbar
     * slot, or -1 when none. Same degradation contract as the eat
     * slot - an unwired rig stays silent instead of placing phantom
     * water.
     *
     * @param supplier water-bucket hotbar slot 0..8, or -1; never null
     */
    public void setMlgBucketSlotSupplier(java.util.function.IntSupplier supplier) {
        this.claimInjector.setMlgBucketSlotSupplier(supplier);
    }

    /**
     * Whether the crash latch is currently set.
     *
     * @return true from first caught RuntimeException until a full
     *         reset or a respawn
     */
    public boolean isCrashed() {
        return crashLatch.isCrashed();
    }

    /**
     * Crashes since the last harness reset — preserved across respawns
     * so pathological rates stay visible to the harness (ADR-0005 5b).
     *
     * @return monotonic within one reset cycle
     */
    public int crashCounter() {
        return crashLatch.crashCounter();
    }

    /**
     * Ticks run since assembly; diagnostics only.
     *
     * @return monotonic tick count within one harness reset cycle
     */
    public long tickCounter() {
        return tickCounter;
    }

    /**
     * Emergency latch from OUTSIDE the four-stage pipeline. Use this
     * when an exception escapes through a different path - the tick
     * harness wrapping onServerTick, for example - that would
     * otherwise bypass ADR-0005 D2. Idempotent in latch state, but
     * the counter still increments so a second outside failure is
     * visible to the harness and the dual-channel report fires
     * again.
     *
     * <p>Mirrors the latch + clear + dual-channel report of
     * {@link #handleCrash} so outside-the-pipeline failures and
     * in-pipeline failures reach the same observable state. The
     * try-catch in {@link #onTick} is still the primary defense;
     * this is the last-ditch seam for everything between the
     * Forge listener entry and onTick itself.
     *
     * @param cause the exception that triggered the outside failure;
     *              never null
     */
    // invariant: see ADR-0005 D2 (latch -> snapshot -> clear -> report)
    public void emergencyLatch(RuntimeException cause) {
        Objects.requireNonNull(cause, "cause");
        crashLatch.latch(cause, tickCounter);
    }

    /**
     * Harness reset: clears latch and counter; the next tick runs the
     * full pipeline (ADR-0005 5a). The harness reset is also the bot
     * "restart" of boundaries.md decision 12, so the event stream
     * wipes and {@code resetAt} moves - the dedupe window does not
     * span restarts, and cursors the harness held are void by
     * protocol (ids stay monotonic, so no stale page can replay).
     * {@link #onRespawned()} deliberately does NOT wipe: the counter
     * survives respawn (5b) for the same reason the stream does -
     * silent diagnostic loss hides bugs.
     */
    public void reset() {
        crashLatch.reset();
        events.reset();
    }

    /**
     * Passive death+respawn recovery: clears the latch but PRESERVES
     * the counter — silent recovery hides bugs (ADR-0005 5b).
     */
    public void onRespawned() {
        crashLatch.onRespawned();
    }

    /**
     * One pipeline tick. Never throws upward: an uncaught exception
     * here would become a ReportedException on the MC main thread and
     * kill the whole server (ADR-0005 D1).
     *
     * @param world read-only perception for every stage; never null
     */
    // invariant: see ADR-0005 D1 (single catch around all four stages)
    public void onTick(WorldView world) {
        if (crashLatch.isCrashed()) {
            // invariant: see ADR-0005 D3 (MinimalReflex may throw too;
            // the same latch fires again and both channels re-report)
            try {
                MinimalReflex.tick(inLethalFluid, airSupply, actor);
                actor.flush();
            } catch (RuntimeException e) {
                crashLatch.latch(e, tickCounter);
            }
            return;
        }
        try {
            tickCounter++;
            runPipeline(world);
        } catch (RuntimeException e) {
            crashLatch.latch(e, tickCounter);
        }
    }

    /**
     * One-tick reflex-to-mission handoff shared by ENGAGE and ESCAPE
     * (ledger 23 / 26): retire the seat's terminal mission, and when
     * the seat accepts a submit, preempt-and-hold this tick and mint
     * the reflex-owned mission.
     *
     * <p>Degrade path: a factory that returns {@code null} leaves the
     * body parked by preemptAndHold; from the next tick the reflex
     * fires again and the factory retries without starting a cooldown
     * (maySubmit stays true) - this is the ESCAPE no-route shape. An
     * ENGAGE factory never returns null in production, so the uniform
     * null tolerance is strictly defensive.
     *
     * @param seat     one-reflex-mission seat for this action kind;
     *                 never null
     * @param factory  mission minter reading live body state; never
     *                 null
     * @param decision the firing reflex decision; never null
     * @param day      game day for event stamps
     * @param tod      time-of-day ticks for event stamps
     * @return true when this tick was consumed by the handoff (the
     *         caller must return); false when the seat is busy and
     *         the caller falls through to the mission stage so the
     *         live reflex mission keeps running - preempting it every
     *         tick would starve the very mission the reflex submitted
     */
    private boolean handoffToSeat(
            ReflexMissionSeat seat, Supplier<BotProcess> factory, ReflexDecision decision, long day, long tod) {
        seat.retireFinished();
        if (!seat.maySubmit()) {
            return false;
        }
        preemption.preemptAndHold(decision, new Intent.Move(0, 0, false, false), tickCounter, day, tod);
        BotProcess mission = factory.get();
        if (mission != null) {
            arbiter.register(mission);
            arbiter.requestControl(mission);
            seat.submitted(mission);
        }
        return true;
    }

    /**
     * The tick pipeline's real shape. ADR-0004 D1's stage ORDER
     * holds end to end; this map exists because the interleaved
     * machinery is what a reader actually meets, and the four-stage
     * story alone has misled external review (2026-08-27 round)
     * into counting drift where law was kept. Steps and their
     * owning records:
     * <ol>
     * <li>reflex.tick + ENGAGE/ESCAPE seat handoff (ledger 24-27,
     *     {@link #handoffToSeat}); a non-null reflex that does not
     *     hand off holds the body and returns
     * <li>resume guard: three-way dispatch on who holds the paused
     *     slot (reflex fight / reflex rescue / original mission)
     * <li>arbiter.tick - winner selection (Stage 2)
     * <li>{@code DigMission} claim injection: per-tick aim+dig
     *     claims for seated dig-family missions (issue 0013 R1,
     *     generalized 0014; see code-health's abstraction-status
     *     table for the next promotion trigger)
     * <li>behaviors.tick + ExecutionReport feedback (Stage 3)
     * <li>actor.flush - contest resolution, claim expiry, intents
     *     (Stage 4)
     * <li>missions.announceTransition - TASK_* disclosure
     * <li>keepalive emission every {@code KEEPALIVE_INTERVAL} ticks
     *     (Stage 5, observability; issue 0001 fix 2)
     * </ol>
     *
     * <p>Runs on the server tick thread only. The ADR-0005 crash
     * latch lives in {@link #onTick}'s wrapper outside this method:
     * a latched controller runs MinimalReflex only and never
     * reaches this pipeline.
     */
    // invariant: see ADR-0005 D1 (called from onTick inside the single catch frame)
    private void runPipeline(WorldView world) {
        CellPos position = positionSource.get();
        float health = healthSource.get();
        long day = clock.day();
        long tod = clock.timeOfDayTicks();
        engageSeat.tickCooldown();
        rescueSeat.tickCooldown();
        hungrySeat.tickCooldown();

        // Stage 1: reflex bypass — non-null decision skips the mission,
        // except the ENGAGE kind, which spends exactly one tick on the
        // preemption and then runs the fight through the mission stage
        // (a fight is multi-tick state; a held-body reflex cannot carry
        // target tracking, leash and grace - ReflexAction#ENGAGE).
        var decision = reflex.tick(world, health);
        if (decision != null && routeReflexDecision(decision, day, tod)) {
            return;
        }

        // Threat gone: hand control back through world revalidation.
        // The guard dispatches on WHO occupies the paused slot - see
        // ReflexPreemption#resumeParkedMission for the two occupancy
        // cases.
        preemption.resumeParkedMission(decision != null, day, tod);

        // Stage 2: arbiter picks or keeps the winner.
        arbiter.tick(world);
        Directive directive = arbiter.lastDirective();

        // Mission-dig claim path (issue 0013 R1, generalized by 0014
        // DigMission): while any DigMission process is seated and reports
        // isDigging(), re-issue the aim + dig claims every tick (see
        // submitAimAndDig for why silence resets progress). Reflex
        // preemption still wins because the reflex layer runs first
        // and parks missions.
        if (arbiter.current() instanceof DigMission dm && dm.isDigging()) {
            toolSelector.select(world, actor, dm.priority(), "mission:dig:" + dm.missionTaskId(), dm.digTarget());
            claimInjector.aimAndDig(dm.priority(), "mission:dig:" + dm.missionTaskId(), dm.digTarget());
        }

        // Stage 3: behaviors claim channels per directive; reports flow
        // back into the running mission (boundary B response half).
        for (Behavior behavior : behaviors) {
            ExecutionReport report = behavior.tick(world, directive, actor);
            BotProcess running = arbiter.current();
            if (directive != null && running != null) {
                running.onExecutionReport(report);
            }
        }

        // Stage 4: resolve contests, expire claims, emit intents.
        actor.flush();
        missions.announceTransition(day, tod);

        // Stage 5 (observability): periodic keepalive for harnesses
        // that want to distinguish "alive and progressing" from
        // "alive but the planner is silent". Zero behaviour change
        // to stages 1-4 - this is a one-way read of plan-progress
        // state pushed to the event stream. Issue 0001 fix 2.
        if (++ticksSinceKeepalive >= KEEPALIVE_INTERVAL) {
            ticksSinceKeepalive = 0;
            emitKeepalive(position, directive, day, tod);
        }
    }

    /**
     * Route one non-null reflex decision: ENGAGE/FORAGE/ESCAPE hand
     * off to their seats (returning true when this tick is fully
     * consumed by the preemption), everything else - including a
     * factory-less ENGAGE - degrades to the hold-and-park shape.
     *
     * <p>Live reflex-owned mission (or the resubmit cooldown) returns
     * false so the pipeline falls through and that mission keeps
     * running - preempting it every tick would starve the very
     * mission this reflex submitted.
     *
     * @return true when the pipeline tick ends here
     */
    private boolean routeReflexDecision(SurvivalReflexLayer.ReflexDecision decision, long day, long tod) {
        boolean engageDecision = decision.action() == ReflexAction.ENGAGE;
        if (engageDecision && engageMissionFactory != null) {
            return handoffToSeat(engageSeat, engageMissionFactory, decision, day, tod);
        }
        if (decision.action() == ReflexAction.FORAGE && hungryMissionFactory != null) {
            // FORAGE hands off the food-acquisition mission (ledger
            // 34): a factory finding no bush returns a mission that
            // fails fast as FOOD_STRATEGY_EXHAUSTED, or null degrades
            // to the freeze hold - same shape as the rescue handoff.
            return handoffToSeat(hungrySeat, hungryMissionFactory, decision, day, tod);
        }
        if (decision.action() == ReflexAction.ESCAPE && rescueMissionFactory != null) {
            // ESCAPE hands off a pathing goal (lava shore, water
            // source); a factory finding no reachable target returns
            // null and the preemption degrades to the freeze hold -
            // no escape route means the harness must decide.
            return handoffToSeat(rescueSeat, rescueMissionFactory, decision, day, tod);
        }
        // ReflexAction owns the intent shape (ADR-0003 section 2:
        // rules say HOW URGENT, the controller says WHAT the held body
        // does): FREEZE halts, ASCEND holds jump so vanilla fluid
        // physics swims the body up (boundary A - intents only, the
        // engine computes the motion). An ENGAGE decision without a
        // factory degrades to this hold: no combat wiring means park,
        // never fight blind.
        Intent.Move hold = decision.action() == ReflexAction.ASCEND
                ? new Intent.Move(0, 0, true, false)
                : new Intent.Move(0, 0, false, false);
        preemption.preemptAndHold(decision, hold, tickCounter, day, tod);
        return true;
    }

    /**
     * Push one keepalive event with a snapshot of plan-progress
     * state. Scans the behavior list for a {@link PathingBehavior}
     * (v1 has at most one; if more plan reporters are added later,
     * extract a small interface and merge their snapshots here).
     */
    private void emitKeepalive(CellPos position, Directive directive, long day, long tod) {
        CellPos goalCell = goalCellOf(directive);
        Vec3 poseD = new Vec3(position.x(), position.y(), position.z());
        for (Behavior b : behaviors) {
            if (b instanceof PathingBehavior pb) {
                Map<String, String> attrs = pb.keepaliveAttrs(poseD, goalCell);
                try {
                    events.push(new BotEvent(
                            EventKind.KEEPALIVE,
                            day,
                            tod,
                            false,
                            Map.copyOf(attrs),
                            "keepalive at tick " + tickCounter));
                } catch (RuntimeException ignored) {
                    // Reporting must never take the pipeline down.
                }
                return;
            }
        }
    }

    /**
     * Extract the active goal cell from a directive, or null if no
     * directive. Sealed over the current goal algebra.
     */
    private static CellPos goalCellOf(Directive directive) {
        return directive == null ? null : Goals.cellOf(directive.goal());
    }

    private String activeName() {
        var current = arbiter.current();
        return current != null
                ? current.displayName()
                : (arbiter.paused() != null ? arbiter.paused().displayName() : "");
    }
}
