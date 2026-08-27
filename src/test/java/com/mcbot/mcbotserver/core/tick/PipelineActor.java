package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import java.util.Map;

/**
 * Recording actor that remembers its latest flush resolution, so
 * tick-level gates can assert "stage 4 resolved claims this tick"
 * without intercepting the controller's internal call chain.
 *
 * <p>The {@link RecordingActor} Javadoc explicitly blesses this
 * subclass as the last-flush variant instead of growing hooks into
 * the base double.
 */
final class PipelineActor extends RecordingActor {

    /** Resolution handed back by the most recent {@link #flush()}. */
    Map<Channel, Claim> lastFlush = Map.of();

    @Override
    public Map<Channel, Claim> flush() {
        lastFlush = super.flush();
        return lastFlush;
    }
}
