package com.mcbot.mcbotserver.boundaryd;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.mcbot.mcbotserver.api.event.EventKind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kind-prefix narrowing gates (issue 0011 D3): the events-poll verb
 * filters payload by kind prefix while the cursor fields stay the
 * TRUE stream values and the cursor-integrity kinds survive every
 * filter - narrowing must never turn a missed batch into a silent
 * gap or advance the harness's cursor by only what was shown.
 *
 * <p>Contract: see {@link EventBatch#narrowedToKindPrefix} and
 * issues/0011-harness-surface-convergence.md section 2.
 */
class EventBatchNarrowTest {

    private static BotEvent event(String kind) {
        return new BotEvent(kind, 0L, 0L, false, Map.of(),
            kind + " probe");
    }

    private static EventBatch batch(BotEvent... events) {
        // latestEventId deliberately HIGHER than the events this
        // sample covers: the cursor describes the whole stream, not
        // the sample - exactly the property narrowing must preserve.
        return new EventBatch(List.of(events), 3, 100L, 9L);
    }

    @Test
    void prefixKeepsMatchesAndDropsTheRest() {
        EventBatch narrowed = batch(
            event(EventKind.STATE_PUSH),
            event(EventKind.TASK_COMPLETED),
            event(EventKind.KEEPALIVE),
            event(EventKind.TASK_FAILED))
            .narrowedToKindPrefix("TASK_");
        assertEquals(List.of("TASK_COMPLETED", "TASK_FAILED"),
            narrowed.events().stream().map(BotEvent::kind).toList());
    }

    /**
     * The whole point of the filter: a harness polling "TASK_" must
     * still learn that events were dropped, or its idea of the stream
     * silently diverges from reality.
     */
    @Test
    void cursorIntegrityKindsSurviveEveryFilter() {
        EventBatch narrowed = batch(
            event(EventKind.TASK_COMPLETED),
            event(EventKind.EVENT_GAP),
            event(EventKind.STATE_PUSH),
            event(EventKind.EVENT_DROPPED))
            .narrowedToKindPrefix("TASK_");
        assertEquals(
            List.of("TASK_COMPLETED", "EVENT_GAP", "EVENT_DROPPED"),
            narrowed.events().stream().map(BotEvent::kind).toList());
    }

    @Test
    void cursorFieldsStayTheTrueStreamValues() {
        EventBatch original = batch(
            event(EventKind.STATE_PUSH),
            event(EventKind.STATE_PUSH));
        EventBatch narrowed = original.narrowedToKindPrefix("TASK_");
        assertTrue(narrowed.events().isEmpty(),
            "nothing matches the prefix");
        assertEquals(original.latestEventId(), narrowed.latestEventId(),
            "the cursor must advance by what existed, not what was "
                + "shown");
        assertEquals(original.droppedCount(), narrowed.droppedCount());
        assertEquals(original.resetAt(), narrowed.resetAt());
    }

    @Test
    void blankPrefixReturnsTheSameBatch() {
        EventBatch original = batch(event(EventKind.STATE_PUSH));
        assertSame(original, original.narrowedToKindPrefix(""));
        assertSame(original, original.narrowedToKindPrefix(null));
    }
}
