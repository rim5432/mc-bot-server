package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import java.util.List;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The assertion family of the gametest rig, split from
 * {@link GametestRig} (which keeps the body/drive/spawn/terrain
 * machinery): the two base checks, the event-stream read model
 * ({@code eventsOf}/{@code eventSeen}), and the wire-facing asserts
 * ({@code assertEventSeen}/{@code eventOf}/{@code assertAttr}/
 * {@code assertSlot}). Every scenario asserts through this family -
 * the 2026-09-03 round replaced the hand-built message concat each
 * scenario repeated with one helper per assertion shape.
 *
 * <p>Template: shared by every suite; see {@link GametestRig} for the
 * rig itself.
 */
final class GametestAsserts {

    private GametestAsserts() {}

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    static void checkEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new GameTestAssertException(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    /**
     * The raw events currently on the stream (cursor-zero full
     * replay); the read model behind the event assertions.
     *
     * @param events the rig's event stream; never null
     * @return the events in stream order; never null
     */
    static List<BotEvent> eventsOf(InMemoryEventQueue events) {
        return events.statusSnapshot(0).events();
    }

    private static List<String> eventKinds(InMemoryEventQueue events) {
        return eventsOf(events).stream().map(BotEvent::kind).toList();
    }

    /**
     * Whether one event kind already appeared - the wait-condition
     * shape of {@link #assertEventSeen}.
     *
     * @param events the rig's event stream; never null
     * @param kind   registered event-kind string; never null
     * @return true when the kind is anywhere on the stream
     */
    static boolean eventSeen(InMemoryEventQueue events, String kind) {
        return eventKinds(events).contains(kind);
    }

    /**
     * Asserts the given event kind already appeared on the stream;
     * usable inside wait-until conditions and terminal executes.
     *
     * @param events the rig's event stream; never null
     * @param kind   registered event-kind string; never null
     */
    static void assertEventSeen(InMemoryEventQueue events, String kind) {
        List<String> kinds = eventKinds(events);
        check(kinds.contains(kind), "expected " + kind + " in stream, got " + kinds);
    }

    /**
     * Finds the FIRST event of the given kind on the rig's stream -
     * the one-lifecycle-event-per-kind lookup most scenarios need.
     * Fails with the full kind list when absent, so a missing event
     * names its siblings instead of a bare NoSuchElementException.
     *
     * @param events the rig's event stream; never null
     * @param kind   registered event-kind string; never null
     * @return the first matching event; never null
     */
    static BotEvent eventOf(InMemoryEventQueue events, String kind) {
        return eventsOf(events).stream()
                .filter(e -> kind.equals(e.kind()))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("expected " + kind + " in stream, got " + eventKinds(events)));
    }

    /**
     * Asserts one wire attr equals the expected string - the attr-side
     * twin of {@link #checkEquals}; the failure message carries the
     * event kind, the key, and the actual value, replacing the
     * hand-built message concat every scenario repeated.
     *
     * @param event    the event whose attrs are read; never null
     * @param key      wire attr key; never null
     * @param expected expected attr value; never null
     */
    static void assertAttr(BotEvent event, String key, String expected) {
        check(
                expected.equals(event.attrs().get(key)),
                event.kind() + " attr " + key + " must be " + expected + ", got "
                        + event.attrs().get(key));
    }

    /**
     * Asserts a container slot holds exactly the expected item and
     * count - the slot-side twin of {@link #checkEquals} for the
     * post-action inventory reads.
     *
     * @param container the body container; never null
     * @param slot      slot index; 0-based
     * @param expected  the required item identity; never null
     * @param count     the required stack count; positive
     */
    static void assertSlot(net.minecraft.world.SimpleContainer container, int slot, Item expected, int count) {
        ItemStack stack = container.getItem(slot);
        check(stack.is(expected), "slot " + slot + " must hold " + expected + "; got " + stack);
        check(stack.getCount() == count, "slot " + slot + " count must be " + count + "; got " + stack.getCount());
    }
}
