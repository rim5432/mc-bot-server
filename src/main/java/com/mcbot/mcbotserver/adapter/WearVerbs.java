package com.mcbot.mcbotserver.adapter;

import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.menu.SlotRole;
import com.mcbot.mcbotserver.core.menu.MenuPlanner;
import com.mcbot.mcbotserver.core.menu.MenuViewJson;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;

/**
 * Armor verb executor (Stage 3 Phase 4 wear slice): the
 * {@code menu wear} behavior - plan over the own-inventory snapshot
 * with the armor catalog, execute the lift-swap-return triples
 * between ticks, report how many armor slots changed. Brigadier tree
 * construction stays with {@link MenuCommands}; the other imperative
 * verbs live in {@link MenuVerbs}.
 */
final class WearVerbs {

    private WearVerbs() {}

    /**
     * Wear the best available armor: a world menu open at the call is
     * closed first by the open-inventory contract.
     *
     * @param live menu surface accessor; never null
     * @param src  the reply target; never null
     * @return 1 on wiring failure, 0 otherwise
     */
    static int runWear(Supplier<MenuCommands.Live> live, CommandSourceStack src) {
        MenuCommands.Live l = live.get();
        if (l == null) {
            return CommandResponse.noActiveBot(src);
        }
        MenuView menu = l.tx().menuSnapshot();
        if (menu == null || !"inventory".equals(menu.type())) {
            menu = l.tx().openInventoryMenu();
        }
        List<MenuPlanner.Step> steps;
        try {
            steps = MenuPlanner.planWear(menu, l.armor());
        } catch (RuntimeException e) {
            return CommandResponse.answer(src, CommandResponse.err(e.getMessage()));
        }
        MenuView after = MenuVerbs.executeSteps(l.tx(), steps, menu);
        JsonObject root = CommandResponse.ok();
        root.addProperty("worn", wornCount(menu, after));
        root.add("menu", MenuViewJson.toJsonObject(after));
        return CommandResponse.answer(src, root);
    }

    /** Armor slots whose content changed between two snapshots. */
    private static int wornCount(MenuView before, MenuView after) {
        int worn = 0;
        for (var slot : after.slots()) {
            if (slot.role() == SlotRole.ARMOR && !slot.isEmpty()) {
                var was = before.slot(slot.index());
                if (was.isEmpty() || !was.item().itemId().equals(slot.item().itemId())) {
                    worn++;
                }
            }
        }
        return worn;
    }
}
