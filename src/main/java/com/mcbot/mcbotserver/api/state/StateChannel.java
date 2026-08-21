package com.mcbot.mcbotserver.api.state;

/**
 * Boundary-D state snapshot channel: the bot pushes on change, the
 * harness pulls only to resync (after a resetAt change).
 *
 * <p>Contract: see boundaries.md Boundary D protocol, state snapshot
 * section. This is the third semantic shape's pull surface — commands
 * are the sync-write seam and the event stream is the poll seam; state
 * must not leak through either. Change detection is the producer's
 * responsibility: {@code current()} is safe to call at any cadence but
 * harnesses should never need to poll it.
 */
public interface StateChannel {

    /**
     * The current model-relevant snapshot.
     *
     * @return the latest state; never null
     */
    BotState current();
}
