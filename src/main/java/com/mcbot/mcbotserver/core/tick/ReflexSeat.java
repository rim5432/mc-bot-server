package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.process.BotProcess;

/**
 * One-reflex-mission seat shared by the ENGAGE and ESCAPE handoffs:
 * retires terminal missions, gates resubmission behind a per-seat
 * cooldown, and records the mission it caused.
 *
 * <p>Contract: see ledger 23 (reflex-carried idle combat) and 26
 * (ESCAPE rescue handoff) - both seats implement the same one-tick
 * controller handoff shape; {@code tickCooldown} stays seat-local
 * because only the pipeline loop drives it.
 *
 * <p>Package-private by design; not part of any boundary.
 */
interface ReflexSeat {

    /**
     * Drop this seat's mission once it went terminal. Its verdict was
     * already announced by the transition detector; keeping the
     * corpse would blur the parked/awaiting queries.
     */
    void retireFinished();

    /**
     * Whether the seat accepts a new reflex mission this tick - no
     * live mission and no cooldown running.
     *
     * @return true when a fresh submission may be minted
     */
    boolean maySubmit();

    /**
     * Records the mission minted for this seat after it was
     * registered and took control.
     *
     * @param mission the reflex-owned mission; never null
     */
    void submitted(BotProcess mission);
}
