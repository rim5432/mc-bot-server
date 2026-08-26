package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.behavior.ExecutionReport;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.goal.Goal;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.behavior.IdleLook;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.process.TerminalMission;
import com.mcbot.mcbotserver.core.reflex.MinimalReflex;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;

import java.io.PrintWriter;
import java.io.StringWriter;
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
    private final Supplier<BotProcess>
        engageMissionFactory;
    /** Mission TASK_* emission and hand-off edge detection. */
    private final MissionReporter missions;
    /** Reflex-owned ENGAGE fight seat bookkeeping. */
    private final ReflexEngageSeat engageSeat;
    /**
     * Reflex-owned ESCAPE rescue mission factory; null degrades ESCAPE
     * decisions to the freeze hold (rigs without rescue wiring park
     * the mission instead of routing to safety).
     */
    private final Supplier<BotProcess>
        rescueMissionFactory;
    /** Reflex-owned ESCAPE rescue seat bookkeeping. */
    private final ReflexRescueSeat rescueSeat;

    private long tickCounter;
    private boolean crashed;
    private int crashCounter;
    private boolean inLethalFluid;
    private int airSupply = ThreatBlackboard.MAX_AIR_SUPPLY;
    private int ticksSinceKeepalive;

    /**
     * How often the keepalive event fires. Matches
     * {@code PathingBehavior.STUCK_WINDOW} so a keepalive arrives
     * on the same tick a STUCK would have, were it to fire. Issue
     * 0001 fix 2: not a contract - the S-F verifier ignores unknown
     * kinds, so this can be tuned later without breaking replay.
     */
    private static final int KEEPALIVE_INTERVAL = 20;

    /**
     * Assembles the pipeline without combat or rescue wiring: ENGAGE
     * and ESCAPE reflex decisions degrade to the freeze hold.
     * Delegates to the twelve-argument constructor.
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
    public BotController(SurvivalReflexLayer reflex, TaskArbiter arbiter,
                         List<Behavior> behaviors, Actor actor,
                         CellPositionSource positionSource, HealthSource healthSource,
                         GameClock clock, EventQueue events,
                         CrashReporter crashReporter) {
        this(reflex, arbiter, behaviors, actor, positionSource,
            healthSource, clock, events, crashReporter, null, null);
    }

    /**
     * Assembles the pipeline. All collaborators stay plain constructor
     * arguments — no service lookup, no optional wiring.
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
     * @param engageMissionFactory supplies one fresh defend mission
     *                      per reflex engage submission (the wiring
     *                      owns identity, budgets and type sets);
     *                      may be null - ENGAGE then degrades to the
     *                      freeze hold
     */
    public BotController(SurvivalReflexLayer reflex, TaskArbiter arbiter,
                         List<Behavior> behaviors, Actor actor,
                         CellPositionSource positionSource, HealthSource healthSource,
                         GameClock clock, EventQueue events,
                         CrashReporter crashReporter,
                         Supplier<BotProcess>
                             engageMissionFactory) {
        this(reflex, arbiter, behaviors, actor, positionSource,
            healthSource, clock, events, crashReporter,
            engageMissionFactory, null);
    }

    /**
     * Assembles the pipeline with combat and rescue wiring. All
     * collaborators stay plain constructor arguments - no service
     * lookup, no optional wiring.
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
     * @param engageMissionFactory supplies one fresh defend mission
     *                      per reflex engage submission; may be null -
     *                      ENGAGE then degrades to the freeze hold
     * @param rescueMissionFactory supplies one fresh rescue mission
     *                      (GotoProcess to a safe cell) per reflex
     *                      escape submission; the factory reads body
     *                      state to decide lava-shore vs water-target;
     *                      may be null - ESCAPE then degrades to the
     *                      freeze hold
     */
    public BotController(SurvivalReflexLayer reflex, TaskArbiter arbiter,
                         List<Behavior> behaviors, Actor actor,
                         CellPositionSource positionSource, HealthSource healthSource,
                         GameClock clock, EventQueue events,
                         CrashReporter crashReporter,
                         Supplier<BotProcess> engageMissionFactory,
                         Supplier<BotProcess> rescueMissionFactory) {
        this.reflex = Objects.requireNonNull(reflex, "reflex");
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.behaviors = List.copyOf(behaviors);
        this.actor = Objects.requireNonNull(actor, "actor");
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.healthSource =
            Objects.requireNonNull(healthSource, "healthSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.crashReporter =
            Objects.requireNonNull(crashReporter, "crashReporter");
        this.engageMissionFactory = engageMissionFactory;
        this.rescueMissionFactory = rescueMissionFactory;
        this.missions = new MissionReporter(arbiter, events);
        this.engageSeat = new ReflexEngageSeat(arbiter);
        this.rescueSeat = new ReflexRescueSeat(arbiter);
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
     * Whether the crash latch is currently set.
     *
     * @return true from first caught RuntimeException until a full
     *         reset or a respawn
     */
    public boolean isCrashed() {
        return crashed;
    }

    /**
     * Crashes since the last harness reset — preserved across respawns
     * so pathological rates stay visible to the harness (ADR-0005 5b).
     *
     * @return monotonic within one reset cycle
     */
    public int crashCounter() {
        return crashCounter;
    }

    /** Ticks run since assembly; diagnostics only. */
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
        handleCrash(cause);
    }

    /**
     * Harness reset: clears latch, counter and any parked mission
     * context; next tick runs the full pipeline (ADR-0005 5a).
     */
    public void reset() {
        crashed = false;
        crashCounter = 0;
    }

    /**
     * Passive death+respawn recovery: clears the latch but PRESERVES
     * the counter — silent recovery hides bugs (ADR-0005 5b).
     */
    public void onRespawned() {
        crashed = false;
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
        if (crashed) {
            // invariant: see ADR-0005 D3 (MinimalReflex may throw too;
            // the same latch fires again and both channels re-report)
            try {
                MinimalReflex.tick(inLethalFluid, airSupply, actor);
                actor.flush();
            } catch (RuntimeException e) {
                handleCrash(e);
            }
            return;
        }
        try {
            tickCounter++;
            runPipeline(world);
        } catch (RuntimeException e) {
            handleCrash(e);
        }
    }

    private void runPipeline(WorldView world) {
        CellPos position = positionSource.get();
        float health = healthSource.get();
        long day = clock.day();
        long tod = clock.timeOfDayTicks();
        engageSeat.tickCooldown();
        rescueSeat.tickCooldown();

        // Stage 1: reflex bypass — non-null decision skips the mission,
        // except the ENGAGE kind, which spends exactly one tick on the
        // preemption and then runs the fight through the mission stage
        // (a fight is multi-tick state; a held-body reflex cannot carry
        // target tracking, leash and grace - ReflexAction#ENGAGE).
        var decision = reflex.tick(world, tickCounter, day, tod,
            position, health);
        if (decision != null) {
            boolean engageDecision =
                decision.action() == ReflexAction.ENGAGE;
            if (engageDecision && engageMissionFactory != null) {
                engageSeat.retireFinished();
                if (engageSeat.maySubmit()) {
                    preemptAndHold(decision,
                        new Intent.Move(0, 0, false, false), day, tod);
                    BotProcess mission = engageMissionFactory.get();
                    arbiter.register(mission);
                    arbiter.requestControl(mission);
                    engageSeat.submitted(mission);
                    return;
                }
                // Live reflex-owned mission (or the resubmit cooldown):
                // fall through to the mission stage so the fight keeps
                // running - preempting it every tick would starve the
                // very mission this reflex submitted.
            } else if (decision.action() == ReflexAction.ESCAPE
                    && rescueMissionFactory != null) {
                // ESCAPE: same handoff shape as ENGAGE but the mission
                // is a pathing goal (lava shore, water source) rather
                // than a fight. The factory reads body state to decide
                // which target to scan for; a factory that finds no
                // reachable target returns null and the preemption
                // degrades to the freeze hold (park and escalate) -
                // no escape route means the harness must decide.
                rescueSeat.retireFinished();
                if (rescueSeat.maySubmit()) {
                    preemptAndHold(decision,
                        new Intent.Move(0, 0, false, false), day, tod);
                    BotProcess mission = rescueMissionFactory.get();
                    if (mission != null) {
                        arbiter.register(mission);
                        arbiter.requestControl(mission);
                        rescueSeat.submitted(mission);
                    }
                    // If mission is null: the body is already parked
                    // for this tick by preemptAndHold; from the next
                    // tick the reflex fires again and the factory
                    // retries (no cooldown started, maySubmit stays
                    // true). This is the degrade-to-freeze path.
                    return;
                }
                // Live reflex-owned rescue mission (or cooldown): fall
                // through to the mission stage so the route keeps
                // walking - same rationale as ENGAGE.
            } else {
                // ReflexAction owns the intent shape (ADR-0003 section
                // 2: rules say HOW URGENT, the controller says WHAT the
                // held body does): FREEZE halts, ASCEND holds jump so
                // vanilla fluid physics swims the body up (boundary A -
                // intents only, the engine computes the motion). An
                // ENGAGE decision without a factory degrades to this
                // hold: no combat wiring means park, never fight blind.
                Intent.Move hold = decision.action() == ReflexAction.ASCEND
                    ? new Intent.Move(0, 0, true, false)
                    : new Intent.Move(0, 0, false, false);
                preemptAndHold(decision, hold, day, tod);
                return;
            }
        }

        // Threat gone: hand control back through world revalidation.
        // The guard dispatches on WHO occupies the paused slot:
        // (a) the reflex-owned fight itself (a survival reflex parked
        // it mid-fight): resume it whenever the seat is free - even
        // while ENGAGE keeps firing (threat present is exactly when
        // the fight must resume; gating on decision==null would seat
        // the requeued original over it and strand the fight);
        // (b) anything else (the mission an engage submission parked):
        // resume only when no reflex fired this tick AND no mission
        // is seated AND the reflex fight is not still awaiting its
        // seat in pending - in that window the arbiter must SELECT
        // the fight, not resume the parked mission over it.
        boolean fightIsParked = engageSeat.fightIsParked();
        boolean fightAwaitingSeat = engageSeat.fightAwaitingSeat();
        boolean rescueIsParked = rescueSeat.rescueIsParked();
        boolean rescueAwaitingSeat = rescueSeat.rescueAwaitingSeat();
        if (arbiter.paused() != null && arbiter.current() == null
                && (fightIsParked
                    || rescueIsParked
                    || (decision == null
                        && !fightAwaitingSeat
                        && !rescueAwaitingSeat))) {
            BotProcess resuming = arbiter.paused();
            String resumingTask = resuming.displayName();
            String resumingId = (resuming instanceof TerminalMission tm)
                ? tm.missionTaskId() : null;
            boolean resumed = arbiter.tryResume();
            missions.resumeVerdict(resumed, resumingTask, resumingId,
                day, tod);
        }

        // Stage 2: arbiter picks or keeps the winner.
        arbiter.tick(world);
        Directive directive = arbiter.lastDirective();

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
     * Park whoever holds the body and hold it under the reflex claim
     * for this tick - the shared skeleton of every reflex
     * preemption: InterruptionContext snapshot, forcePauseAll with
     * its PARKED / RETIRED_TERMINAL / NO_CURRENT outcomes, the hold
     * claim, flush, and the retirement-lap verdict announcement.
     * DIG decisions additionally inject their aim-and-hold claims
     * through {@link #preemptDigClaims} before the flush.
     *
     * @param decision the winning reflex decision; never null
     * @param hold     the intent that holds the body this tick;
     *                 never null
     * @param day      game day for event stamping
     * @param tod      time-of-day ticks for event stamping
     */
    private void preemptAndHold(
            SurvivalReflexLayer.ReflexDecision decision, Intent.Move hold,
            long day, long tod) {
        // Single-slot eviction with an honest event: when this park
        // will occupy the paused slot over an existing occupant (the
        // reflex-chain shape: original parked by an engage submission,
        // fight seated, now a survival reflex parks the fight), the
        // controller requeues the occupant itself so a DROPPED
        // revalidation reaches the harness as TASK_DROPPED - the
        // arbiter's internal eviction is the safety net, not the
        // reporter.
        if (arbiter.paused() != null && arbiter.current() != null
                && arbiter.current().isActive()
                && arbiter.paused() != arbiter.current()) {
            BotProcess evicted = arbiter.paused();
            String evictedName = evicted.displayName();
            String evictedId = (evicted instanceof TerminalMission tm)
                ? tm.missionTaskId() : null;
            if (arbiter.requeuePausedOrDrop()
                    == TaskArbiter.PausedEviction.DROPPED) {
                missions.dropped(evictedName, evictedId, day, tod,
                    "context invalidated by reflex chain requeue");
            }
        }
        InterruptionContext ctx = new InterruptionContext(tickCounter,
            positionSource.get(), activeName(),
            "reflex-preempt:" + decision.ruleName(), "");
        BotProcess current = arbiter.current();
        String pausedTask = current != null ? current.displayName() : "";
        String pausedId = (current instanceof TerminalMission tm)
            ? tm.missionTaskId() : null;
        boolean announceVerdict = false;
        switch (arbiter.forcePauseAll(ctx)) {
            case PARKED -> {
                missions.paused(pausedTask, pausedId, day, tod,
                    "paused by reflex " + decision.ruleName());
                // No current while parked: the transition detector
                // must not fire on stale state.
                missions.forgetCurrent();
            }
            case RETIRED_TERMINAL -> announceVerdict = true;
            case NO_CURRENT -> {
            }
        }
        actor.submit(new Claim(Channel.MOVE, decision.priority(),
            "reflex:" + decision.ruleName(), hold));
        preemptDigClaims(decision);
        actor.flush();
        if (announceVerdict) {
            // Retirement-lap corpse: its verdict is announced here,
            // on the reflex tick, explicitly per the ParkResult
            // contract - not via any transition-state side effect.
            missions.announceTransition(day, tod);
        }
    }

    /**
     * DIG reflex claim injection: a targeted DIG decision adds a ROT
     * aim at the target cell and an INTERACT dig claim on top of the
     * preemption hold. DIG carries its own geometry - hold still,
     * aim, and hold the claim; the adapter's executor accumulates
     * destroy progress across ticks, the same engine-carries-the-
     * state shape as ASCEND's held jump. A targetless DIG (board
     * stamped inWall without a position, or the post-rescue
     * hysteresis hold where the eye is already clear) degrades to
     * the plain freeze hold - missing data must not mint a
     * dig-at-null. The aim claim is presentation only (the executor
     * is server-authoritative and never reads facing, exactly like
     * vanilla's ServerPlayerGameMode); cell-center math keeps it
     * free of eye-height constants.
     *
     * @param decision the winning reflex decision; never null
     */
    private void preemptDigClaims(
            SurvivalReflexLayer.ReflexDecision decision) {
        if (decision.action() != ReflexAction.DIG
                || decision.target() == null) {
            return;
        }
        CellPos digTarget = decision.target();
        Vec3 from = cellCenter(positionSource.get());
        Vec3 to = cellCenter(digTarget);
        actor.submit(new Claim(Channel.ROT, decision.priority(),
            "reflex:" + decision.ruleName(),
            new Intent.Look(IdleLook.yawTo(from, to),
                IdleLook.pitchTo(from, to))));
        actor.submit(new Claim(Channel.INTERACT, decision.priority(),
            "reflex:" + decision.ruleName(),
            new Intent.Dig(digTarget)));
    }

    /**
     * Push one keepalive event with a snapshot of plan-progress
     * state. Scans the behavior list for a {@link PathingBehavior}
     * (v1 has at most one; if more plan reporters are added later,
     * extract a small interface and merge their snapshots here).
     */
    private void emitKeepalive(CellPos position, Directive directive,
                               long day, long tod) {
        CellPos goalCell = goalCellOf(directive);
        Vec3 poseD = new Vec3(position.x(), position.y(), position.z());
        for (Behavior b : behaviors) {
            if (b instanceof PathingBehavior pb) {
                Map<String, String> attrs = pb.keepaliveAttrs(
                    poseD, goalCell);
                try {
                    events.push(new BotEvent(EventKind.KEEPALIVE,
                        day, tod, false, Map.copyOf(attrs),
                        "keepalive at tick " + tickCounter));
                } catch (RuntimeException ignored) {
                    // Reporting must never take the pipeline down.
                }
                return;
            }
        }
    }

    /**
     * Center of one block cell - the DIG reflex aims from body-cell
     * center to target-cell center; presentation-grade geometry, not
     * physics.
     *
     * @param cell the cell; never null
     * @return the cell's center point; never null
     */
    private static Vec3 cellCenter(CellPos cell) {
        return new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
    }

    /**
     * Extract the active goal cell from a directive, or null if no
     * directive. Sealed over the current goal algebra.
     */
    private static CellPos goalCellOf(Directive directive) {
        if (directive == null) {
            return null;
        }
        Goal g = directive.goal();
        if (g instanceof GoalBlock b) {
            return b.target();
        }
        if (g instanceof GoalNear n) {
            return n.center();
        }
        return null;
    }

    // invariant: see ADR-0005 D2 (latch -> snapshot -> clear -> report)
    private void handleCrash(RuntimeException e) {
        crashed = true;
        crashCounter++;
        CellPos position;
        try {
            position = positionSource.get();
        } catch (RuntimeException poseFailure) {
            position = new CellPos(0, -64, 0);
        }
        StringWriter stack = new StringWriter();
        e.printStackTrace(new PrintWriter(stack));
        InterruptionContext ctx = new InterruptionContext(tickCounter,
            position, activeName(),
            e.getClass().getSimpleName() + ":" + e.getMessage(),
            stack.toString());

        // Clear BEFORE reporting so even a poisoned outbox cannot leave
        // stale intents on the body (ADR-0005 D2 step 3).
        try {
            actor.clearAllIntents();
        } catch (RuntimeException ignored) {
            // The floor is documentation, not perfection.
        }
        reportPrimary(ctx);
        reportFallback(ctx);
    }

    private void reportPrimary(InterruptionContext ctx) {
        try {
            events.push(new BotEvent(EventKind.BOT_CRASHED,
                clock.day(), clock.timeOfDayTicks(), true,
                Map.of("cause", ctx.causeSummary()),
                "bot crashed: " + ctx.causeSummary()));
        } catch (RuntimeException ignored) {
            // Best-effort; the fallback channel still fires below.
        }
    }

    private void reportFallback(InterruptionContext ctx) {
        try {
            crashReporter.report(ctx);
        } catch (RuntimeException ignored) {
            // Nothing left to fall back to.
        }
    }

    private String activeName() {
        var current = arbiter.current();
        return current != null ? current.displayName()
            : (arbiter.paused() != null
                ? arbiter.paused().displayName() : "");
    }
}
