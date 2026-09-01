package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-region sourcing primitives shared by every planner: the
 * MAIN-before-HOTBAR supply pool, its per-item ledgers and totals.
 *
 * <p>All planners source exclusively from here and return remainders
 * to it - one supply pool, one read order (snapshot order), one
 * definition of "carried".
 */
final class PlayerRegion {

    private PlayerRegion() {}

    static boolean isPlayerRegion(SlotView slot) {
        return slot.role() == SlotRole.MAIN || slot.role() == SlotRole.HOTBAR;
    }

    /**
     * Player-region slots in snapshot order (MAIN before HOTBAR on
     * every current menu kind) — the single supply pool every plan
     * sources from and returns remainder to.
     *
     * @param menu the full snapshot; never null
     * @return the matching slots in snapshot order; never null
     */
    static List<SlotView> slots(MenuView menu) {
        return menu.slots().stream().filter(PlayerRegion::isPlayerRegion).toList();
    }

    /**
     * Collects the player-region slots holding the item, in snapshot
     * order (MAIN before HOTBAR on every current menu kind).
     *
     * @param menu   the full snapshot; never null
     * @param itemId the item to source; never null
     * @return queue of {flat slot, count} pairs, front = first source;
     *         never null
     */
    static Deque<int[]> ledger(MenuView menu, String itemId) {
        Deque<int[]> ledger = new ArrayDeque<>();
        for (var slot : slots(menu)) {
            if (!slot.isEmpty() && slot.item().itemId().equals(itemId)) {
                ledger.add(new int[] {slot.index(), slot.item().count()});
            }
        }
        return ledger;
    }

    /**
     * Total carried count per item kind across the player region —
     * the supply pool recipe resolution draws from.
     *
     * @param menu the full snapshot; never null
     * @return item id -> total count; never null, possibly empty
     */
    static Map<String, Integer> totals(MenuView menu) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (var slot : slots(menu)) {
            if (!slot.isEmpty()) {
                totals.merge(slot.item().itemId(), slot.item().count(), Integer::sum);
            }
        }
        return totals;
    }

    /** Player-region carried total of one item kind (0 when absent). */
    static int countOf(MenuView menu, String itemId) {
        int sum = 0;
        for (var slot : slots(menu)) {
            if (!slot.isEmpty() && slot.item().itemId().equals(itemId)) {
                sum += slot.item().count();
            }
        }
        return sum;
    }
}
