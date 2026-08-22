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
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.MinimalReflex;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p>Body facts (pose, health, clock, lava flag) arrive through
 * constructor-injected accessors — the controller never imports engine
 * types, and the Stage 1 adapter is their only production supplier.
 *
 * <p>Implementation note: runs on the server tick thread only; the
 * Forge subscription that calls {@link #onTick} lives in the mod entry
 * class until that adapter lands.
 */
// contract: see ADR-0004 D1 + ADR-0005 D1 (pipeline order and catch frame)
public final class BotController {

    /** Body-position accessor. */
    @FunctionalInterface
    public interface PoseSource {

        /**
         * Current block cell of the body.
         *
         * @return the pose; never null
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
    private final PoseSource poseSource;
    private final HealthSource healthSource;
    private final GameClock clock;
    private final EventQueue events;
    private final CrashReporter crashReporter;

    private long tickCounter;
    private boolean crashed;
    private int crashCounter;
    private boolean inLethalFluid;
    private BotProcess previousCurrent;

    /**
     * Assembles the pipeline. All collaborators stay plain constructor
     * arguments — no service lookup, no optional wiring.
     *
     * @param reflex        survival bypass; never null
     * @param arbiter       task-channel winner selector; never null
     * @param behaviors     execution tier; never null, may be empty
     * @param actor         claim surface; never null
     * @param poseSource    body position accessor; never null
     * @param healthSource  body health accessor; never null
     * @param clock         game-time accessor; never null
     * @param events        event stream for the primary crash channel;
     *                      never null
     * @param crashReporter fallback reporter; never null
     */
    public BotController(SurvivalReflexLayer reflex, TaskArbiter arbiter,
                         List<Behavior> behaviors, Actor actor,
                         PoseSource poseSource, HealthSource healthSource,
                         GameClock clock, EventQueue events,
                         CrashReporter crashReporter) {
        this.reflex = Objects.requireNonNull(reflex, "reflex");
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.behaviors = List.copyOf(behaviors);
        this.actor = Objects.requireNonNull(actor, "actor");
        this.poseSource = Objects.requireNonNull(poseSource, "poseSource");
        this.healthSource =
            Objects.requireNonNull(healthSource, "healthSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.crashReporter =
            Objects.requireNonNull(crashReporter, "crashReporter");
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
                MinimalReflex.tick(inLethalFluid, actor);
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
        CellPos pose = poseSource.get();
        float health = healthSource.get();
        long day = clock.day();
        long tod = clock.timeOfDayTicks();

        // Stage 1: reflex bypass — non-null decision skips the mission.
        var decision = reflex.tick(world, tickCounter, day, tod,
            pose, health);
        if (decision != null) {
            InterruptionContext ctx = new InterruptionContext(tickCounter,
                pose, activeName(), "reflex-preempt:" + decision.ruleName(),
                "");
            String pausedTask = activeName();
            var result = arbiter.forcePauseAll(ctx);
            boolean announceVerdict = false;
            switch (result) {
                case PARKED -> {
                    emitMissionEvent(EventKind.TASK_PAUSED, day, tod,
                        pausedTask, "paused by reflex "
                            + decision.ruleName());
                    // No current while parked: the transition detector
                    // must not fire on stale state.
                    previousCurrent = null;
                }
                case RETIRED_TERMINAL -> announceVerdict = true;
                case NO_CURRENT -> {
                }
            }
            actor.submit(new Claim(Channel.MOVE, decision.priority(),
                "reflex:" + decision.ruleName(),
                new Intent.Move(0, 0, false, false)));
            actor.flush();
            if (announceVerdict) {
                // Retirement-lap corpse: its verdict is announced here,
                // on the reflex tick, explicitly per the ParkResult
                // contract - not via any previousCurrent side effect.
                emitMissionTransition(day, tod);
            }
            return;
        }

        // Threat gone: hand control back through world revalidation.
        if (arbiter.paused() != null) {
            String resumingTask = arbiter.paused().displayName();
            boolean resumed = arbiter.tryResume();
            emitMissionEvent(resumed
                    ? EventKind.TASK_RESUMED : EventKind.TASK_DROPPED,
                day, tod, resumingTask,
                resumed ? "world assumptions held"
                    : "world assumptions invalidated; task dropped");
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
        emitMissionTransition(day, tod);
    }

    /**
     * Detect a mission hand-off and emit its completion event. The
     * pipeline stays generic: any process implementing TerminalMission
     * gets TASK_COMPLETED / TASK_FAILED; anything else ends silently.
     */
    private void emitMissionTransition(long day, long tod) {
        BotProcess previous = previousCurrent;
        BotProcess now = arbiter.current();
        previousCurrent = now;
        if (previous == null || previous == now) {
            return;
        }
        if (!(previous instanceof com.mcbot.mcbotserver.core.process
                .TerminalMission mission)) {
            return;
        }
        if (mission.missionSucceeded()) {
            emitMissionEvent(EventKind.TASK_COMPLETED, day, tod,
                mission.missionTaskId(), "goal reached");
        } else if (previous.isActive()) {
            // Swapped out while still active (preemption path already
            // reported); nothing terminal to announce.
            return;
        } else {
            String reason = mission.failureReasonOrNull();
            String shown = reason != null ? reason : "unknown";
            var extra = reason == null
                ? Map.<String, String>of()
                : Map.of("reason", reason);
            emitMissionEvent(EventKind.TASK_FAILED, day, tod,
                mission.missionTaskId(), "failed: " + shown, extra);
        }
    }

    private void emitMissionEvent(String kind, long day, long tod,
                                  String taskName, String detail) {
        emitMissionEvent(kind, day, tod, taskName, detail, Map.of());
    }

    /**
     * Push one mission lifecycle event. Structured fields land in
     * attrs (task, reason); text stays human-readable summary only -
     * harnesses must never parse it.
     */
    private void emitMissionEvent(String kind, long day, long tod,
                                  String taskName, String detail,
                                  Map<String, String> extraAttrs) {
        boolean urgent = EventKind.TASK_PAUSED.equals(kind)
            || EventKind.TASK_DROPPED.equals(kind);
        try {
            var attrs = new java.util.LinkedHashMap<String, String>();
            attrs.put("task", taskName);
            attrs.putAll(extraAttrs);
            events.push(new BotEvent(kind, day, tod, urgent,
                Map.copyOf(attrs), taskName + ": " + detail));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }

    // invariant: see ADR-0005 D2 (latch -> snapshot -> clear -> report)
    private void handleCrash(RuntimeException e) {
        crashed = true;
        crashCounter++;
        CellPos pose;
        try {
            pose = poseSource.get();
        } catch (RuntimeException poseFailure) {
            pose = new CellPos(0, -64, 0);
        }
        StringWriter stack = new StringWriter();
        e.printStackTrace(new PrintWriter(stack));
        InterruptionContext ctx = new InterruptionContext(tickCounter,
            pose, activeName(),
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
