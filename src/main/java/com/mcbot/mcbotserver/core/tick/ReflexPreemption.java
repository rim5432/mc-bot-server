package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.process.TerminalMission;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The reflex preemption and resume choreography, extracted from
 * {@link BotController} (2026-08-28 paydown of the lean deferral the
 * GodClass suppression cited): park-whoever-holds-the-body with its
 * InterruptionContext snapshot, the paused-slot eviction, and the
 * world-revalidated resume guard.
 *
 * <p>Contract: see boundaries.md §C (reflex ticks first; a non-null
 * reflex skips the mission; preemption carries InterruptionContext)
 * and ADR-0004 D1 (reflex stage precedes the arbiter). The seat list
 * generalizes over every reflex-owned mission seat - the resume
 * guard's only seat question is whether ANY seat parks or awaits, so
 * a fourth seat joins by construction.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md §C (reflex-first preemption + InterruptionContext)
public final class ReflexPreemption {

    private final TaskArbiter arbiter;
    private final MissionReporter missions;
    private final Actor actor;
    private final ReflexClaimInjector claimInjector;
    private final List<ReflexMissionSeat> reflexSeats;
    private final Supplier<CellPos> positionSource;

    /**
     * Creates the choreography over the arbiter and its reporters.
     *
     * @param arbiter        task-channel winner selector; never null
     * @param missions       TASK_* emission; never null
     * @param actor          claim surface; never null
     * @param claimInjector  auxiliary reflex claim construction; never
     *                       null
     * @param reflexSeats    every reflex-owned mission seat; never
     *                       null
     * @param positionSource body position accessor for interruption
     *                       snapshots; never null
     */
    public ReflexPreemption(
            TaskArbiter arbiter,
            MissionReporter missions,
            Actor actor,
            ReflexClaimInjector claimInjector,
            List<ReflexMissionSeat> reflexSeats,
            Supplier<CellPos> positionSource) {
        this.arbiter = Objects.requireNonNull(arbiter, "arbiter");
        this.missions = Objects.requireNonNull(missions, "missions");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.claimInjector = Objects.requireNonNull(claimInjector, "claimInjector");
        this.reflexSeats = List.copyOf(reflexSeats);
        this.positionSource = Objects.requireNonNull(positionSource, "positionSource");
    }

    /**
     * Hand control back through world revalidation when a parked
     * mission can be resumed. The guard dispatches on WHO occupies
     * the paused slot:
     * (a) the reflex-owned fight itself (a survival reflex parked it
     * mid-fight): resume whenever the seat is free - even while
     * ENGAGE keeps firing (threat present is exactly when the fight
     * must resume; gating on reflexFired would seat the requeued
     * original over it and strand the fight);
     * (b) anything else (the mission an engage submission parked):
     * resume only when no reflex fired this tick AND no mission is
     * seated AND neither reflex fight is still awaiting its seat in
     * pending - in that window the arbiter must SELECT the fight,
     * not resume the parked mission over it.
     *
     * @param reflexFired whether the reflex layer emitted a decision
     *                    this tick
     * @param day         game day for the resume verdict stamp
     * @param tod         time-of-day ticks for the resume verdict
     *                    stamp
     */
    public void resumeParkedMission(boolean reflexFired, long day, long tod) {
        boolean reflexMissionParked = reflexSeats.stream().anyMatch(ReflexMissionSeat::missionIsParked);
        boolean reflexMissionAwaiting = reflexSeats.stream().anyMatch(ReflexMissionSeat::missionAwaitingSeat);
        if (arbiter.paused() != null
                && arbiter.current() == null
                && (reflexMissionParked || (!reflexFired && !reflexMissionAwaiting))) {
            BotProcess resuming = arbiter.paused();
            String resumingTask = resuming.displayName();
            String resumingId = (resuming instanceof TerminalMission tm) ? tm.missionTaskId() : null;
            boolean resumed = arbiter.tryResume();
            missions.resumeVerdict(resumed, resumingTask, resumingId, day, tod);
        }
    }

    /**
     * Park whoever holds the body and hold it under the reflex claim
     * for this tick - the shared skeleton of every reflex
     * preemption: InterruptionContext snapshot, forcePauseAll with
     * its PARKED / RETIRED_TERMINAL / NO_CURRENT outcomes, the hold
     * claim, flush, and the retirement-lap verdict announcement.
     * DIG decisions additionally inject their aim-and-hold claims
     * through the claim injector before the flush.
     *
     * @param decision the winning reflex decision; never null
     * @param hold     the intent that holds the body this tick;
     *                 never null
     * @param tick     controller tick counter, for the interruption
     *                 snapshot
     * @param day      game day for event stamping
     * @param tod      time-of-day ticks for event stamping
     */
    public void preemptAndHold(
            SurvivalReflexLayer.ReflexDecision decision, Intent.Move hold, long tick, long day, long tod) {
        evictPausedForReflex(day, tod);
        InterruptionContext ctx = new InterruptionContext(
                tick, positionSource.get(), activeName(), "reflex-preempt:" + decision.ruleName(), "");
        BotProcess current = arbiter.current();
        String pausedTask = current != null ? current.displayName() : "";
        String pausedId = (current instanceof TerminalMission tm) ? tm.missionTaskId() : null;
        boolean announceVerdict = false;
        switch (arbiter.forcePauseAll(ctx)) {
            case PARKED -> {
                missions.paused(pausedTask, pausedId, day, tod, "paused by reflex " + decision.ruleName());
                // No current while parked: the transition detector
                // must not fire on stale state.
                missions.forgetCurrent();
            }
            case RETIRED_TERMINAL -> announceVerdict = true;
            case NO_CURRENT -> {}
        }
        actor.submit(new Claim(
                Channel.MOVE, decision.priority(), ReflexAction.REFLEX_OWNER_PREFIX + decision.ruleName(), hold));
        claimInjector.injectAux(decision);
        actor.flush();
        if (announceVerdict) {
            // Retirement-lap corpse: its verdict is announced here,
            // on the reflex tick, explicitly per the ParkResult
            // contract - not via any transition-state side effect.
            missions.announceTransition(day, tod);
        }
    }

    /**
     * Single-slot eviction with an honest event: when this park will
     * occupy the paused slot over an existing occupant (the
     * reflex-chain shape: original parked by an engage submission,
     * fight seated, now a survival reflex parks the fight), the
     * controller requeues the occupant itself so a DROPPED
     * revalidation reaches the harness as TASK_DROPPED - the
     * arbiter's internal eviction is the safety net, not the reporter.
     */
    private void evictPausedForReflex(long day, long tod) {
        if (arbiter.paused() != null
                && arbiter.current() != null
                && arbiter.current().isActive()
                && arbiter.paused() != arbiter.current()) {
            BotProcess evicted = arbiter.paused();
            String evictedName = evicted.displayName();
            String evictedId = (evicted instanceof TerminalMission tm) ? tm.missionTaskId() : null;
            if (arbiter.requeuePausedOrDrop() == TaskArbiter.PausedEviction.DROPPED) {
                missions.dropped(evictedName, evictedId, day, tod, "context invalidated by reflex chain requeue");
            }
        }
    }

    private String activeName() {
        var current = arbiter.current();
        return current != null
                ? current.displayName()
                : (arbiter.paused() != null ? arbiter.paused().displayName() : "");
    }
}
