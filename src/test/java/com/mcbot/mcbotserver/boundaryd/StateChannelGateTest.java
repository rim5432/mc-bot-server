package com.mcbot.mcbotserver.boundaryd;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.state.ChangeDetectingStateChannel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Stage-0 state-channel gate: the third boundary-D semantic shape has
 * a real surface — change-detected STATE_PUSH plus pull-through
 * current().
 *
 * <p>Contract: see boundaries.md Boundary D protocol, state snapshot
 * section (push on transition; getState is the resync call).
 */
class StateChannelGateTest {

    private static BotState stateAt(int x, String task) {
        return new BotState(new CellPos(x, 64, 0), 0f, 0f, "overworld",
            Map.of(), 0, Map.of(), task, 20, 0);
    }

    /**
     * First call pushes the initial snapshot; equal snapshots push
     * nothing; a changed snapshot pushes exactly one more event.
     */
    @Test
    void pushOnTransitionOnly() {
        InMemoryEventQueue queue =
            new InMemoryEventQueue(() -> 9L, () -> 12000L);
        BotState[] holder = {stateAt(0, "idle")};
        ChangeDetectingStateChannel channel =
            new ChangeDetectingStateChannel(() -> holder[0], queue,
                () -> 9L, () -> 12000L);

        BotState first = channel.current();
        assertEquals(stateAt(0, "idle"), first);
        List<BotEvent> events = queue.statusSnapshot(0).events();
        assertEquals(1, events.size());
        assertEquals(EventKind.STATE_PUSH, events.get(0).kind());
        assertEquals("initial state", events.get(0).text());
        assertEquals(9L, events.get(0).day(),
            "stamps come from the injected clock");

        // Same state again: no delta, no event.
        assertSame(channel.current(), first);
        assertEquals(List.of(), queue.statusSnapshot(1).events());

        // Move + new task: exactly one more push.
        holder[0] = stateAt(3, "goto");
        channel.current();
        List<BotEvent> second = queue.statusSnapshot(1).events();
        assertEquals(1, second.size());
        assertEquals("state changed", second.get(0).text());
        assertEquals("3", second.get(0).attrs().get("posX"));
        assertEquals("64", second.get(0).attrs().get("posY"));
        assertEquals("0", second.get(0).attrs().get("posZ"));
        assertEquals("goto", second.get(0).attrs().get("task"));
    }

    /** current() always returns the freshest capture. */
    @Test
    void currentIsPullThroughNotCacheOnly() {
        InMemoryEventQueue queue =
            new InMemoryEventQueue(() -> 1L, () -> 0L);
        BotState[] holder = {stateAt(0, "idle")};
        ChangeDetectingStateChannel channel =
            new ChangeDetectingStateChannel(() -> holder[0], queue,
                () -> 1L, () -> 0L);

        channel.current();
        holder[0] = stateAt(7, "follow");
        assertEquals(stateAt(7, "follow"), channel.current(),
            "pull must reflect the latest capture, not the last push");
    }
}
