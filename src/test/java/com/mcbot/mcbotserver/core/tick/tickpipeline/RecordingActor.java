package com.mcbot.mcbotserver.tickpipeline;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-double actor: records every submitted claim in arrival order
 * while delegating resolution to a real {@link ChannelArbiter}, so
 * tests can assert both "what was claimed" and "what won".
 *
 * <p>Package-private test infrastructure; not part of any boundary.
 */
final class RecordingActor implements Actor {

    final List<Claim> submitted = new ArrayList<>();
    private final ChannelArbiter delegate = new ChannelArbiter();

    @Override
    public void submit(Claim claim) {
        submitted.add(claim);
        delegate.submit(claim);
    }

    @Override
    public Map<Channel, Claim> flush() {
        return delegate.flush();
    }

    @Override
    public void clearAllIntents() {
        submitted.clear();
        delegate.clearAllIntents();
    }
}
