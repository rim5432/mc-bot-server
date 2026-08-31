package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Beyond-head contract of the {@code resetAt} marker (issue 0015
 * resetAt epoch honesty): when queues draw their marker from one
 * shared allocator, every construction and every reset reports a
 * value strictly beyond anything a client could have bookmarked from
 * an earlier queue - a respawned bot's stream can never collide with
 * a stale epoch-equal cursor. Pinned after the boundary-D QA round
 * (receipt 2026-08-31-052015, case C2-post) proved the collision
 * live: a fresh queue minted marker 1 against a stored epoch of 1.
 */
class ResetEpochGateTest {

    /** Two queues from one allocator never share a marker. */
    @Test
    void sharedAllocatorMakesRecreationBeyondHead() {
        AtomicLong source = new AtomicLong();
        InMemoryEventQueue first = new InMemoryEventQueue(() -> 1L, () -> 0L, source::incrementAndGet);
        InMemoryEventQueue second = new InMemoryEventQueue(() -> 1L, () -> 0L, source::incrementAndGet);
        assertTrue(second.resetAt() > first.resetAt(), first.resetAt() + " then " + second.resetAt());
    }

    /** Reset draws the next allocation, staying beyond every prior marker. */
    @Test
    void resetAllocatesBeyondAllPriorMarkers() {
        AtomicLong source = new AtomicLong();
        InMemoryEventQueue first = new InMemoryEventQueue(() -> 1L, () -> 0L, source::incrementAndGet);
        first.reset(); // draw 2
        InMemoryEventQueue second = new InMemoryEventQueue(() -> 1L, () -> 0L, source::incrementAndGet); // draw 3
        first.reset(); // draw 4
        assertEquals(4L, first.resetAt());
        assertEquals(3L, second.resetAt());
    }

    /** The two-arg constructor keeps the pre-allocator 1, 2, 3 shape. */
    @Test
    void sessionLocalAllocatorPreservesLegacySequence() {
        InMemoryEventQueue queue = new InMemoryEventQueue(() -> 1L, () -> 0L);
        assertEquals(1L, queue.resetAt());
        queue.reset();
        assertEquals(2L, queue.resetAt());
        queue.reset();
        assertEquals(3L, queue.resetAt());
    }

    /** Independent queues keep independent session-local sequences. */
    @Test
    void independentDefaultQueuesEachStartAtOne() {
        InMemoryEventQueue a = new InMemoryEventQueue(() -> 1L, () -> 0L);
        InMemoryEventQueue b = new InMemoryEventQueue(() -> 1L, () -> 0L);
        assertEquals(1L, a.resetAt());
        assertEquals(1L, b.resetAt());
    }

    /** Null allocator rejects, matching the supplier discipline. */
    @Test
    void nullAllocatorRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryEventQueue(() -> 1L, () -> 0L, null));
    }
}
