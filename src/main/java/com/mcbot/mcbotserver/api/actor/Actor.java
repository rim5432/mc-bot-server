package com.mcbot.mcbotserver.api.actor;

import java.util.Map;

/**
 * The write half of boundary A: the only place intents may be
 * registered. Implementations collect claims, resolve one winner per
 * channel per tick (higher priority wins, ties favor the incumbent),
 * and expire everything at flush.
 *
 * <p>Contract: see ADR-0004 D2 and boundaries.md section B — behaviors
 * reach the body exclusively through this interface; a process must
 * never touch it. {@link #clearAllIntents} is the crash path required
 * by ADR-0005 D2 step 3: callable when every subsystem is suspect, so
 * it must depend on nothing but its own buffer.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
public interface Actor {

    /**
     * Register one claim for this tick. Later submissions for the same
     * channel simply join the contest; nothing is resolved until flush.
     *
     * @param claim the claim to enter; must not be null
     */
    void submit(Claim claim);

    /**
     * Resolve this tick's contests and expire all claims. After this
     * returns, the body applies exactly one winning intent per contested
     * channel and neutral output on channels nobody claimed.
     *
     * @return winners keyed by channel; never null, possibly empty
     */
    Map<Channel, Claim> flush();

    /**
     * Drop every buffered claim without resolving. Must stay callable
     * from the exception latch path with zero dependence on subsystems
     * that might themselves have crashed (ADR-0005 D2 step 3).
     */
    void clearAllIntents();
}
