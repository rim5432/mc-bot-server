package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.process.BotProcess;

import com.mcbot.mcbotserver.core.process.TaskArbiter;

/**
 * Bookkeeping for the reflex-owned ESCAPE rescue mission: which
 * mission the ESCAPE reflex minted, when the next one may be minted,
 * and the seat-state queries the paused-mission resume guard asks.
 * Pure state and policy - the controller still owns the preemption
 * choreography (park, hold, register, request control).
 *
 * <p>Mirrors {@link ReflexEngageSeat} but with a longer cooldown:
 * rescue missions are pathing goals (lava shore, water source) that
 * take longer to resolve than a fight, and a re-submit while the
 * first rescue is still walking would park the active route and
 * re-issue from the same trigger - a no-op churn the arbiter must
 * not see.
 *
 * <p>Implementation note: server tick thread only, like every
 * pipeline collaborator.
 */
final class ReflexRescueSeat {

    /**
     * Ticks between reflex rescue submissions. Longer than the engage
     * cooldown because a rescue route (swim to shore, walk to water)
     * takes tens of ticks, and re-issuing from the same trigger while
     * the first route is mid-walk would park-and-replace an active
     * pathing mission with an identical one. The route's own success /
     * timeout / no-path verdicts resolve the rescue.
     */
    static final int RESCUE_RESUBMIT_COOLDOWN = 80;

    private final TaskArbiter arbiter;
    /** Live reflex-owned rescue mission, if any; null once it retires. */
    private BotProcess rescue;
    /** Primed to the cooldown so the first ESCAPE verdict may
     *  submit immediately. */
    private int ticksSinceSubmit = RESCUE_RESUBMIT_COOLDOWN;

    /**
     * @param arbiter seat authority behind the parked/awaiting
     *                queries; never null
     */
    ReflexRescueSeat(TaskArbiter arbiter) {
        this.arbiter = arbiter;
    }

    /** One pipeline tick of resubmit-cooldown advance. */
    void tickCooldown() {
        if (ticksSinceSubmit < RESCUE_RESUBMIT_COOLDOWN) {
            ticksSinceSubmit++;
        }
    }

    /**
     * Drop a retired rescue mission from the seat. Its verdict was
     * already announced by the transition detector; keeping the corpse
     * would only blur the parked/awaiting queries below.
     */
    void retireFinished() {
        if (rescue != null && !rescue.isActive()) {
            rescue = null;
        }
    }

    /**
     * @return true when no rescue mission is live and the resubmit
     *         cooldown has elapsed
     */
    boolean maySubmit() {
        return rescue == null
            && ticksSinceSubmit >= RESCUE_RESUBMIT_COOLDOWN;
    }

    /**
     * Record a just-submitted rescue mission and start its cooldown.
     *
     * @param mission the freshly registered rescue mission; never null
     */
    void submitted(BotProcess mission) {
        rescue = mission;
        ticksSinceSubmit = 0;
    }

    /**
     * @return true when the live rescue mission sits in the arbiter's
     *         paused slot (a survival reflex parked it mid-route)
     */
    boolean rescueIsParked() {
        return rescue != null && rescue.isActive()
            && arbiter.paused() == rescue;
    }

    /**
     * @return true when the live rescue mission is registered but
     *         neither seated nor parked
     */
    boolean rescueAwaitingSeat() {
        return rescue != null && rescue.isActive()
            && arbiter.paused() != rescue;
    }
}
