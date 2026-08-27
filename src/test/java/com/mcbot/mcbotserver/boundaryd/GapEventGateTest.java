package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventBatch;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behind-consumer recovery gate: the queue detects a stale
 * {@code sinceEventId} and prepends a synthetic EVENT_GAP to the
 * polled page so the harness sees the loss at the same point it
 * would have seen the lost events.
 *
 * <p>Contract: see boundaries.md Behind-consumer recovery contract.
 * Five cases cover the meaningful shape boundaries: a real gap,
 * the zero-gap boundary, a fresh poll from cursor 0, gap with a
 * co-occurring overflow (gap at head, EVENT_DROPPED at tail), and
 * the empty-queue / fully-evicted worst case where the gap covers
 * the whole range.
 */
class GapEventGateTest {

    private static BotEvent stamped(String kind) {
        return new BotEvent(kind, 1L, 600L, false, Map.of(), kind);
    }

    /**
     * Stale sinceEventId older than oldest retained entry: the page
     * is headed by an EVENT_GAP carrying the lost-range count, the
     * caller's cursor, and the id of the first real event on the
     * page. The gap is urgent.
     */
    @Test
    void staleCursorPrependsGapEvent() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        // Overflow by 5 to evict the first 5 entries. After this
        // loop, entries hold id 6..205, lastEventId=205.
        for (int i = 0; i < InMemoryEventQueue.DEFAULT_CAP + 5; i++) {
            q.push(stamped("ev-" + i));
        }
        // Caller's stale cursor is 1. Lost range = 2..5, count=4,
        // oldest=6. The page is gap + entries 6..205.
        EventBatch batch = q.statusSnapshot(1L);
        List<BotEvent> page = batch.events();
        BotEvent gap = page.get(0);
        assertEquals(EventKind.EVENT_GAP, gap.kind());
        assertSame(true, gap.urgent(), "gap must be urgent - harness mental model is wrong");
        assertEquals("4", gap.attrs().get("count"), "4 events between since=1 and oldest=6");
        assertEquals("1", gap.attrs().get("since"));
        assertEquals("6", gap.attrs().get("oldest"));
        // Real page starts at id 6, which carries text "ev-5" (the
        // first 5 ev-0..ev-4 are evicted, ev-5 is the oldest kept).
        assertEquals("ev-5", page.get(1).text());
        // The most recent push sits right before the synthetic
        // EVENT_DROPPED notice - that one is appended at the tail
        // because the same 205-push loop that evicted the 5 oldest
        // also set droppedSinceLastPoll=5.
        assertEquals(
                "ev-" + (InMemoryEventQueue.DEFAULT_CAP + 4),
                page.get(page.size() - 2).text());
    }

    /**
     * sinceEventId equal to oldest-1: zero events lost, no gap.
     * The page starts with the first real event the caller already
     * saw the id of.
     */
    @Test
    void cursorAtOldestMinusOneHasNoGap() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        q.push(stamped("a"));
        q.push(stamped("b"));
        // lastEventId=2. Ask since 1 - that's "everything past id 1"
        // = just b. Zero lost, no gap.
        EventBatch batch = q.statusSnapshot(1L);
        assertEquals(1, batch.events().size());
        assertEquals("b", batch.events().get(0).text());
        for (BotEvent e : batch.events()) {
            assertFalse(EventKind.EVENT_GAP.equals(e.kind()), "no gap when zero events were lost");
        }
    }

    /**
     * First-time poll with cursor 0 on a populated queue: the
     * entries run from id 1, so since=0 means "give me everything",
     * which is the full first-time page - no gap.
     */
    @Test
    void firstPollWithCursorZeroHasNoGap() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        q.push(stamped("a"));
        q.push(stamped("b"));
        EventBatch batch = q.statusSnapshot(0L);
        assertEquals(2, batch.events().size());
        for (BotEvent e : batch.events()) {
            assertFalse(EventKind.EVENT_GAP.equals(e.kind()), "first poll with cursor 0 has no gap");
        }
    }

    /**
     * Behind-consumer + queue overflow: gap at the head (real
     * loss) and EVENT_DROPPED at the tail (push-time overflow).
     * Both are reported, the page order is gap -> real -> dropped.
     */
    @Test
    void gapAndOverflowCoexist() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        for (int i = 0; i < InMemoryEventQueue.DEFAULT_CAP + 5; i++) {
            q.push(stamped("ev-" + i));
        }
        // lastEventId = 205. Oldest retained = 6 (200 capacity,
        // first 5 evicted). Caller thinks last seen was 1 - events
        // 2..5 lost (count=4), oldest on the page = 6.
        EventBatch batch = q.statusSnapshot(1L);
        assertTrue(batch.droppedCount() > 0, "overflow must still be reported via droppedCount");
        BotEvent first = batch.events().get(0);
        assertEquals(EventKind.EVENT_GAP, first.kind());
        assertEquals("4", first.attrs().get("count"));
        assertEquals("1", first.attrs().get("since"));
        assertEquals("6", first.attrs().get("oldest"));
        BotEvent last = batch.events().get(batch.events().size() - 1);
        assertEquals(EventKind.EVENT_DROPPED, last.kind());
    }

    /**
     * Worst case: the original entries the harness once saw are all
     * evicted and the harness is still behind. The gap fires with
     * {@code oldest} pointing at the first retained id and
     * {@code count} covering the lost range, so the harness can
     * reconcile via {@code getState()} before draining the page.
     *
     * <p>Why since=1, not since=0: cursor 0 is the "first poll,
     * give me everything" sentinel — the lost-range signal does not
     * apply. To exercise the "harness is behind" semantic, the test
     * must use a non-zero cursor that the harness once owned.
     */
    @Test
    void allOriginalEntriesEvictedAndStaleCursorEmitsGap() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        q.push(stamped("a"));
        q.push(stamped("b"));
        // Push DEFAULT_CAP + 5 more events. After this loop:
        // lastEventId = 2 + (DEFAULT_CAP + 5) = 2 + 205 = 207.
        // The queue holds 200 entries; the first 7 ids (1..7) are
        // evicted. Oldest retained = 8.
        for (int i = 0; i < InMemoryEventQueue.DEFAULT_CAP + 5; i++) {
            q.push(stamped("flood-" + i));
        }
        // Harness last saw "a" (id 1), then the bot generated
        // "b" + floods. Lost range = 2..7, count = 6, oldest = 8.
        EventBatch batch = q.statusSnapshot(1L);
        BotEvent first = batch.events().get(0);
        assertEquals(EventKind.EVENT_GAP, first.kind());
        assertEquals("6", first.attrs().get("count"));
        assertEquals("1", first.attrs().get("since"));
        assertEquals("8", first.attrs().get("oldest"));
    }

    /**
     * Cursor equal to the last seen id: nothing lost, page empty,
     * no gap. The harness has not fallen behind.
     */
    @Test
    void cursorAtLastEventIdEmitsNoGapAndEmptyPage() {
        InMemoryEventQueue q = new InMemoryEventQueue(() -> 1L, () -> 0L);
        q.push(stamped("a"));
        // lastEventId=1. Ask since 1: nothing new, zero lost.
        EventBatch batch = q.statusSnapshot(1L);
        assertTrue(batch.events().isEmpty());
    }
}
