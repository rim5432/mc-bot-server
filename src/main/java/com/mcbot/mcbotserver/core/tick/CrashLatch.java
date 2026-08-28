package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.types.CellPos;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The ADR-0005 exception state machine, extracted from
 * {@link BotController}: the sticky crashed latch, the crash counter
 * preserved across respawns, the clear-before-report intent wipe, and
 * the dual-channel best-effort report (event stream + fallback
 * reporter). The controller keeps the catch FRAMES (D1: the pipeline
 * wrapper, D3: the MinimalReflex degraded mode) and delegates the
 * latch state itself here.
 *
 * <p>Contract: see ADR-0005 D2-D5 (latch -> snapshot -> clear ->
 * report; sticky until reset; reset clears the counter while respawn
 * preserves it - silent recovery hides bugs).
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// invariant: see ADR-0005 D2 (latch -> snapshot -> clear -> report)
public final class CrashLatch {

    private final EventQueue events;
    private final CrashReporter crashReporter;
    private final BotController.GameClock clock;
    private final BotController.CellPositionSource positionSource;
    private final Supplier<String> activeName;
    private final Actor actor;
    private boolean crashed;
    private int crashCounter;

    /**
     * Creates the latch over its two report channels and the body
     * accessors it snapshots on crash.
     *
     * @param events         primary report channel; never null
     * @param crashReporter  fallback report channel; never null
     * @param clock          game-time accessor for event stamps; never
     *                       null
     * @param positionSource body position accessor for the
     *                       interruption snapshot; never null
     * @param activeName     display name of the mission in control at
     *                       crash time; never null
     * @param actor          claim surface whose intents are cleared
     *                       before reporting; never null
     */
    public CrashLatch(
            EventQueue events,
            CrashReporter crashReporter,
            BotController.GameClock clock,
            BotController.CellPositionSource positionSource,
            Supplier<String> activeName,
            Actor actor) {
        this.events = Objects.requireNonNull(events, "events");
        this.crashReporter = Objects.requireNonNull(crashReporter, "crashReporter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
        this.activeName = Objects.requireNonNull(activeName, "activeName");
        this.actor = Objects.requireNonNull(actor, "actor");
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

    /**
     * Harness reset: clears latch and counter; the next tick runs the
     * full pipeline (ADR-0005 5a).
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
     * Emergency latch from any failure path, in-pipeline or out.
     * Idempotent in latch state, but the counter still increments so
     * a second failure is visible to the harness and the dual-channel
     * report fires again.
     *
     * @param cause the exception that triggered the failure; never
     *              null
     * @param tick  controller tick counter, for the interruption
     *              snapshot
     */
    // invariant: see ADR-0005 D2 (latch -> snapshot -> clear -> report)
    public void latch(RuntimeException cause, long tick) {
        Objects.requireNonNull(cause, "cause");
        crashed = true;
        crashCounter++;
        CellPos position;
        try {
            position = positionSource.get();
        } catch (RuntimeException poseFailure) {
            position = new CellPos(0, -64, 0);
        }
        StringWriter stack = new StringWriter();
        cause.printStackTrace(new PrintWriter(stack));
        InterruptionContext ctx = new InterruptionContext(
                tick,
                position,
                activeName.get(),
                cause.getClass().getSimpleName() + ":" + cause.getMessage(),
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
            events.push(new BotEvent(
                    EventKind.BOT_CRASHED,
                    clock.day(),
                    clock.timeOfDayTicks(),
                    true,
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
}
