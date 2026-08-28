package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.api.menu.MenuClick;
import com.mcbot.mcbotserver.api.menu.MenuTransactions;
import com.mcbot.mcbotserver.api.menu.MenuView;
import com.mcbot.mcbotserver.api.types.CellPos;
import net.minecraft.core.BlockPos;

/**
 * The imperative menu-transaction half of the actor binding (issue
 * 0007 §6.2, ledger 29), extracted from {@link BindingActor} so the
 * per-tick claim resolver stops carrying the request-response methods
 * (2026-08-28 method-count paydown). Composed by BindingActor and
 * reachable through {@code actor.menuTransactions()} - still ONE
 * boundary-A implementer aggregate per body, sharing the actor's
 * facade so the containerMenu state has one owner.
 *
 * <p>Contract: see boundaries.md §A (menu transaction surface, ledger
 * 29). Runs on the server tick thread only; call between ticks -
 * after {@code actor.flush()}, before the next perception snapshot;
 * never inside claim resolution.
 */
// contract: see boundaries.md §A (menu transaction surface, ledger 29)
public final class ActorMenuTransactions implements MenuTransactions {

    /** Menu transactions: one opener per body (0007 A1). */
    private final MenuOpener menus;

    /**
     * Creates the transaction surface over the actor binding's facade.
     *
     * @param facade the body's one Player-typed acting surface; never
     *               null
     */
    public ActorMenuTransactions(BotPlayerFacade facade) {
        this.menus = new MenuOpener(facade);
    }

    // contract: see boundaries.md §A (menu transaction surface, ledger 29)
    @Override
    public MenuView openMenu(CellPos target) {
        return menus.open(new BlockPos(target.x(), target.y(), target.z()))
                .map(BindingMenu::snapshot)
                .orElse(null);
    }

    @Override
    public MenuView openInventoryMenu() {
        return menus.openInventory().snapshot();
    }

    @Override
    public MenuView menuSnapshot() {
        BindingMenu current = menus.currentMenu();
        return current == null ? null : current.snapshot();
    }

    @Override
    public MenuView menuClick(int slot, int button, MenuClick type) {
        BindingMenu current = menus.currentMenu();
        if (current == null) {
            throw new IllegalStateException("no menu is open");
        }
        current.click(slot, button, type);
        return current.snapshot();
    }

    @Override
    public void closeMenu() {
        BindingMenu current = menus.currentMenu();
        if (current != null) {
            current.close();
        }
    }

    @Override
    public MenuView menuButtonClick(int id) {
        BindingMenu current = menus.currentMenu();
        if (current == null) {
            throw new IllegalStateException("no menu is open");
        }
        current.clickButton(id);
        return current.snapshot();
    }

    @Override
    public MenuView openEntityMenu(int entityId) {
        return menus.openEntity(entityId).map(BindingMenu::snapshot).orElse(null);
    }

    @Override
    public MenuView setBeaconEffects(String primaryKey, String secondaryKey) {
        BindingMenu current = menus.currentMenu();
        if (current == null) {
            throw new IllegalStateException("no menu is open");
        }
        current.setBeaconEffects(primaryKey, secondaryKey);
        return current.snapshot();
    }
}
