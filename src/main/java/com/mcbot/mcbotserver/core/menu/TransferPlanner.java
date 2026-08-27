package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import java.util.ArrayList;
import java.util.List;

/**
 * Click planning primitives for moving items between the player
 * region and an opened container/furnace: ordered scans plus one
 * shared split-carry engine behind the exact-count verbs.
 *
 * <p>Public entry points live on {@link MenuPlanner}. All plans are
 * computed before the first click - an invalid request performs none.
 */
final class TransferPlanner {

    private TransferPlanner() {}

    /**
     * Container-side {flat slot, count} stacks of one item kind, in
     * snapshot order.
     */
    static List<int[]> containerStacks(MenuView menu, String itemId) {
        List<int[]> stacks = new ArrayList<>();
        for (var slot : menu.slots()) {
            if (slot.role() == SlotRole.CONTAINER
                    && !slot.isEmpty()
                    && slot.item().itemId().equals(itemId)) {
                stacks.add(new int[] {slot.index(), slot.item().count()});
            }
        }
        return stacks;
    }

    /** Source stacks of one role as {flat slot, count} pairs, snapshot order. */
    static List<int[]> roleStacks(MenuView menu, SlotRole role) {
        List<int[]> stacks = new ArrayList<>();
        for (var slot : menu.slots()) {
            if (slot.role() == role && !slot.isEmpty()) {
                stacks.add(new int[] {slot.index(), slot.item().count()});
            }
        }
        return stacks;
    }

    /** Total count across {flat slot, count} pairs (any container). */
    static int total(Iterable<int[]> stacks) {
        int sum = 0;
        for (int[] s : stacks) {
            sum += s[1];
        }
        return sum;
    }

    /**
     * {flat index, free room} pairs for DEPOSIT targets, in snapshot
     * order, over slots passing {@code want}: empty slots carry up to
     * the 64-stack assumption, slots already holding {@code itemId}
     * their headroom, foreign-item slots nothing.
     */
    static List<int[]> roomForItem(java.util.function.Predicate<SlotView> want, String itemId, MenuView menu) {
        List<int[]> rooms = new ArrayList<>();
        for (var slot : menu.slots()) {
            if (!want.test(slot)) {
                continue;
            }
            int room = slot.isEmpty()
                    ? 64
                    : slot.item().itemId().equals(itemId) ? 64 - slot.item().count() : 0;
            if (room > 0) {
                rooms.add(new int[] {slot.index(), room});
            }
        }
        return rooms;
    }

    /**
     * {flat index, free room} pairs for TAKE destinations, in
     * snapshot order over the player region: a slot is roomy when
     * empty (64) or simply not full - the planner measures the slot's
     * own content, independent of which item travels (execution-time
     * vanilla rejection of cross-item merges remains the safety net).
     */
    static List<int[]> roomOnPlayerSlots(MenuView menu) {
        List<int[]> rooms = new ArrayList<>();
        for (var slot : PlayerRegion.slots(menu)) {
            int room = slot.isEmpty() ? 64 : 64 - slot.item().count();
            if (room > 0) {
                rooms.add(new int[] {slot.index(), room});
            }
        }
        return rooms;
    }

    // ------------------------------------------------------------------
    // shared split-carry engine (exact-count transfers)
    // ------------------------------------------------------------------

    /**
     * Exact-count transfer between an ordered source sequence and an
     * ordered destination-room list: lift a source stack once, place
     * exactly one item per right-click onto successive destinations,
     * then return the remainder with a final left-click on the source
     * (a no-op click when the whole stack was placed - both carried
     * and source are empty there). Callers pre-prove supply and
     * destination room, so neither cursor can overrun.
     *
     * @param sources      {flat slot, count} pairs, consumed head-first
     * @param destinations {flat slot, free room} pairs, filled in order
     * @param amount       items to move in total; positive, proven available
     * @param steps        collector for emitted clicks; never null
     */
    static void splitCarry(
            Iterable<int[]> sources, List<int[]> destinations, int amount, List<MenuPlanner.Step> steps) {
        int remaining = amount;
        int destIdx = 0;
        int room = destinations.isEmpty() ? 0 : destinations.get(0)[1];
        for (int[] source : sources) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(source[1], remaining);
            int src = source[0];
            steps.add(new MenuPlanner.Step(src, 0, MenuClick.PICKUP));
            for (int placed = 0; placed < take; placed++) {
                while (room == 0) {
                    destIdx++;
                    room = destinations.get(destIdx)[1];
                }
                steps.add(new MenuPlanner.Step(destinations.get(destIdx)[0], 1, MenuClick.PICKUP));
                room--;
            }
            // Return the remainder (a no-op click when the whole
            // stack was placed: both carried and source are empty).
            steps.add(new MenuPlanner.Step(src, 0, MenuClick.PICKUP));
            remaining -= take;
        }
    }
}
