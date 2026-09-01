package com.mcbot.mcbotserver.core.state;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.state.StateChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * Reference StateChannel: change-detected push onto the event stream,
 * pull-through on {@code current()}.
 *
 * <p>Contract: see boundaries.md Boundary D protocol, state snapshot
 * section — the bot pushes STATE_PUSH on transition; getState() is the
 * resync call after a resetAt change. Equality of the previous and next
 * snapshot decides a push, so continuous callers (a poll-happy harness)
 * cannot flood the stream: no delta, no event.
 *
 * <p>The snapshot source is injected because body facts (inventory,
 * effects, task summary) are adapter knowledge in production and plain
 * lambdas in tests.
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see boundaries.md Boundary D protocol (state snapshot shape)
public final class ChangeDetectingStateChannel implements StateChannel {

    /** Where the freshest BotState comes from. */
    @FunctionalInterface
    public interface SnapshotSource {

        /**
         * Capture the current state.
         *
         * @return the snapshot; never null
         */
        BotState capture();
    }

    private final SnapshotSource source;
    private final EventQueue events;
    private final LongSupplier daySupplier;
    private final LongSupplier timeOfDaySupplier;

    @Nullable
    private BotState last;

    /**
     * Creates a channel over one capture point.
     *
     * @param source            state capture point; never null
     * @param events            event stream receiving STATE_PUSH
     *                          entries; never null
     * @param daySupplier       game-day stamp accessor; never null
     * @param timeOfDaySupplier time-of-day stamp accessor; never null
     */
    public ChangeDetectingStateChannel(
            SnapshotSource source, EventQueue events, LongSupplier daySupplier, LongSupplier timeOfDaySupplier) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.events = Objects.requireNonNull(events, "events");
        this.daySupplier = Objects.requireNonNull(daySupplier, "day");
        this.timeOfDaySupplier = Objects.requireNonNull(timeOfDaySupplier, "timeOfDay");
        this.source = source;
    }

    @Override
    public BotState current() {
        BotState now = source.capture();
        if (!now.equals(last)) {
            boolean firstSnapshot = last == null;
            last = now;
            pushStateEvent(now, firstSnapshot);
        }
        return now;
    }

    private void pushStateEvent(BotState state, boolean first) {
        try {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("posX", String.valueOf(state.pos().x()));
            attrs.put("posY", String.valueOf(state.pos().y()));
            attrs.put("posZ", String.valueOf(state.pos().z()));
            attrs.put("dimension", state.dimension());
            attrs.put("task", state.currentTaskSummary());
            events.push(new BotEvent(
                    EventKind.STATE_PUSH,
                    daySupplier.getAsLong(),
                    timeOfDaySupplier.getAsLong(),
                    false,
                    attrs,
                    first ? "initial state" : "state changed"));
        } catch (RuntimeException ignored) {
            // A failed state push must not break the caller's tick.
        }
    }
}
