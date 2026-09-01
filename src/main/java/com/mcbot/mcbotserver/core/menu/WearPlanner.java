package com.mcbot.mcbotserver.core.menu;

import com.mcbot.mcbotserver.api.menu.ArmorCatalog;
import com.mcbot.mcbotserver.api.menu.ArmorCatalog.ArmorPiece;
import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.api.menu.SlotView;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Wear planning: the best wearable from the player region moves into
 * each own-inventory armor slot when it strictly beats what is worn.
 * One move is the lift-swap-return triple - lift the candidate,
 * swap it with the worn piece (a plain place when the slot is
 * empty), return the displaced piece to the now-empty source slot
 * (a no-op pickup when nothing was displaced).
 *
 * <p>Moves are computed on one snapshot and never interfere: each
 * move touches only its own armor slot and its source slot, and a
 * displaced piece re-classifies to the armor slot it came from -
 * the slot this plan has already handled - so it can never become a
 * candidate for a later move.
 */
final class WearPlanner {

    private WearPlanner() {}

    /**
     * Plan wearing over one own-inventory snapshot.
     *
     * @param menu    the own-inventory snapshot; never null
     * @param catalog armor classification; never null
     * @return the click sequence in armor-slot order (flat 5..8,
     *         head..feet); never null, may be empty when nothing
     *         improves
     * @throws IllegalArgumentException when the menu carries no
     *                                  ARMOR-role slots (not an
     *                                  own-inventory menu)
     */
    static List<MenuPlanner.Step> plan(MenuView menu, ArmorCatalog catalog) {
        List<SlotView> armorSlots = menu.slots().stream()
                .filter(slot -> slot.role() == SlotRole.ARMOR)
                .toList();
        if (armorSlots.isEmpty()) {
            throw new IllegalArgumentException("no armor slots: not an own-inventory menu");
        }
        List<SlotView> pool = PlayerRegion.slots(menu);
        List<MenuPlanner.Step> steps = new ArrayList<>();
        for (SlotView armor : armorSlots) {
            SlotView best = bestCandidate(pool, armor.index(), catalog);
            if (best != null && replaces(armor, catalog.classify(best.item().itemId()), catalog)) {
                appendMove(steps, best.index(), armor.index());
            }
        }
        return steps;
    }

    /**
     * Highest-protection wearable for one armor slot; ties keep
     * snapshot order (the compare is strictly greater).
     */
    @Nullable
    private static SlotView bestCandidate(List<SlotView> pool, int armorSlot, ArmorCatalog catalog) {
        SlotView best = null;
        int bestProtection = Integer.MIN_VALUE;
        for (SlotView candidate : pool) {
            if (candidate.isEmpty()) {
                continue;
            }
            ArmorPiece piece = catalog.classify(candidate.item().itemId());
            if (piece == null || piece.armorSlot() != armorSlot) {
                continue;
            }
            if (piece.protection() > bestProtection) {
                best = candidate;
                bestProtection = piece.protection();
            }
        }
        return best;
    }

    /**
     * Whether the candidate strictly beats what is worn. An
     * unclassifiable worn piece is never stripped: the planner
     * cannot rank what it does not know.
     */
    private static boolean replaces(SlotView armor, @Nullable ArmorPiece candidate, ArmorCatalog catalog) {
        if (armor.isEmpty()) {
            return true;
        }
        ArmorPiece worn = catalog.classify(armor.item().itemId());
        return worn != null && candidate != null && candidate.protection() > worn.protection();
    }

    private static void appendMove(List<MenuPlanner.Step> steps, int source, int armorSlot) {
        steps.add(new MenuPlanner.Step(source, 0, MenuClick.PICKUP));
        steps.add(new MenuPlanner.Step(armorSlot, 0, MenuClick.PICKUP));
        steps.add(new MenuPlanner.Step(source, 0, MenuClick.PICKUP));
    }
}
