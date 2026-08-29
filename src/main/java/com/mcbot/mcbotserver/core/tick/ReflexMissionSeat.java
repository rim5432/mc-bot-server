package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;

/**
 * One-reflex-mission seat: bookkeeping for a mission a reflex
 * handoff minted - which mission, when the next one may be minted,
 * and the seat-state queries the paused-mission resume guard asks.
 * Pure state and policy; the controller still owns the preemption
 * choreography (park, hold, register, request control).
 *
 * <p>Promotion (2026-08-27, the third seat): the ENGAGE and ESCAPE
 * seats were mirror classes differing only in cooldown constant and
 * method naming - the arch-drift no-generalization ruling reserved
 * exactly this point, "seat dispatch promotes at the third seat".
 * One parameterized class now serves the fight, rescue, and forage
 * handoffs; a fourth seat is one construction away.
 *
 * <p>Contract: see ledger 23 (reflex-carried idle combat), 26
 * (ESCAPE rescue handoff), and 34 (food acquisition). Package-private
 * by design; not part of any boundary.
 */
final class ReflexMissionSeat {

    private final TaskArbiter arbiter;

    private final int resubmitCooldownTicks;

    private BotProcess mission;

    /** Primed to the cooldown so the first verdict may submit at once. */
    private int ticksSinceSubmit;

    /**
     * Creates a seat with its resubmit cadence.
     *
     * @param arbiter              seat authority behind the
     *                             parked/awaiting queries; never null
     * @param resubmitCooldownTicks ticks between reflex submissions;
     *                             positive - sized per mission family
     *                             (fights resolve fast, pathing
     *                             missions churn if re-issued mid-walk)
     */
    ReflexMissionSeat(TaskArbiter arbiter, int resubmitCooldownTicks) {
        this.arbiter = arbiter;
        this.resubmitCooldownTicks = resubmitCooldownTicks;
        this.ticksSinceSubmit = resubmitCooldownTicks;
    }

    /** One pipeline tick of resubmit-cooldown advance. */
    void tickCooldown() {
        if (ticksSinceSubmit < resubmitCooldownTicks) {
            ticksSinceSubmit++;
        }
    }

    /**
     * Drop a retired mission from the seat. Its verdict was already
     * announced by the transition detector; keeping the corpse would
     * only blur the parked/awaiting queries.
     */
    public void retireFinished() {
        if (mission != null && !mission.isActive()) {
            mission = null;
        }
    }

    /**
     * @return true when no mission is live and the resubmit cooldown
     *         has elapsed
     */
    public boolean maySubmit() {
        return mission == null && ticksSinceSubmit >= resubmitCooldownTicks;
    }

    /**
     * Record a just-submitted mission and start its cooldown.
     *
     * @param mission the freshly registered mission; never null
     */
    public void submitted(BotProcess mission) {
        this.mission = mission;
        this.ticksSinceSubmit = 0;
    }

    /**
     * Reports whether the seat's live mission currently sits parked.
     *
     * @return true when the live mission sits in the arbiter's paused
     *         slot (a survival reflex parked it mid-run)
     */
    boolean missionIsParked() {
        return mission != null && mission.isActive() && arbiter.paused() == mission;
    }

    /**
     * Reports a registered mission still waiting for its seat.
     *
     * @return true when the live mission is registered but neither
     *         seated nor parked
     */
    boolean missionAwaitingSeat() {
        return mission != null && mission.isActive() && arbiter.paused() != mission;
    }
}
