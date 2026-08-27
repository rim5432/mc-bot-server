package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;

/**
 * Bookkeeping for the reflex-owned ENGAGE fight: which mission the
 * ENGAGE reflex minted, when the next one may be minted, and the
 * seat-state queries the paused-mission resume guard asks. Pure
 * state and policy - the controller still owns the preemption
 * choreography (park, hold, register, request control).
 *
 * <p>Implementation note: server tick thread only, like every
 * pipeline collaborator.
 */
final class ReflexEngageSeat implements ReflexSeat {

    /**
     * Ticks between reflex engage submissions. Deliberately ABOVE
     * the engage rule's hold window plus a release-band transit:
     * (a) a reflex-owned defend whose verdict landed while the rule
     * still fires - a threat fleeing through the release band - must
     * not mint a mission per tick; (b) after the threat disappears
     * the rule keeps firing through its hysteresis hold with no
     * threat left, and resubmitting there would mint a spurious
     * instant-SUCCESS defend per engagement tail. The fight's own
     * leash, grace and escape verdicts resolve the engagement.
     */
    static final int ENGAGE_RESUBMIT_COOLDOWN = 40;

    private final TaskArbiter arbiter;
    /** Live reflex-owned fight, if any; null once it retires. */
    private BotProcess fight;
    /** Primed to the cooldown so the first ENGAGE verdict may
     *  submit immediately. */
    private int ticksSinceSubmit = ENGAGE_RESUBMIT_COOLDOWN;

    /**
     * @param arbiter seat authority behind the parked/awaiting
     *                queries; never null
     */
    ReflexEngageSeat(TaskArbiter arbiter) {
        this.arbiter = arbiter;
    }

    /** One pipeline tick of resubmit-cooldown advance. */
    void tickCooldown() {
        if (ticksSinceSubmit < ENGAGE_RESUBMIT_COOLDOWN) {
            ticksSinceSubmit++;
        }
    }

    /**
     * Drop a retired fight from the seat. Its verdict was already
     * announced by the transition detector; keeping the corpse would
     * only blur the parked/awaiting queries below.
     */
    @Override
    public void retireFinished() {
        if (fight != null && !fight.isActive()) {
            fight = null;
        }
    }

    /**
     * @return true when no fight is live and the resubmit cooldown
     *         has elapsed
     */
    @Override
    public boolean maySubmit() {
        return fight == null && ticksSinceSubmit >= ENGAGE_RESUBMIT_COOLDOWN;
    }

    /**
     * Record a just-submitted fight and start its cooldown.
     *
     * @param mission the freshly registered fight; never null
     */
    @Override
    public void submitted(BotProcess mission) {
        fight = mission;
        ticksSinceSubmit = 0;
    }

    /**
     * Reports whether the live fight currently sits parked.
     *
     * @return true when the live fight sits in the arbiter's paused
     *         slot (a survival reflex parked it mid-fight)
     */
    boolean fightIsParked() {
        return fight != null && fight.isActive() && arbiter.paused() == fight;
    }

    /**
     * Reports a registered fight still waiting for its seat.
     *
     * @return true when the live fight is registered but neither
     *         seated nor parked
     */
    boolean fightAwaitingSeat() {
        return fight != null && fight.isActive() && arbiter.paused() != fight;
    }
}
