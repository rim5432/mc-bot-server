package com.mcbot.mcbotserver.api.menu;

import com.mcbot.mcbotserver.api.types.CellPos;
import javax.annotation.Nullable;

/**
 * Menu transactions: the imperative, request-response half of the
 * boundary-A write surface. Open once, click at will, close once —
 * NOT per-tick claims: claims expire every tick and arbitrate
 * between contestants, both wrong for a transaction (issue 0007
 * §6.2).
 *
 * <p>Implemented by the actor binding alongside {@code Actor}, so
 * boundary A's single-mutation-surface rule survives (a second menu
 * controller outside the actor would need review sanction).
 * {@code Actor} itself stays claim-only — claim-only actors (the
 * core arbiter, test recordings) are legitimate implementations and
 * must not carry dead menu methods.
 *
 * <p>Contract: see boundaries.md section A (ledger 29). Runs on the
 * server tick thread only. Call between ticks — after
 * {@code actor.flush()}, before the next perception snapshot (issue
 * 0007 §6.4); never inside claim resolution.
 */
// contract: see boundaries.md §A (menu transaction surface, ledger 29)
public interface MenuTransactions {

    /**
     * Open the world menu at the target block (crafting table, chest).
     * Closes any menu currently open first (grid materials returned,
     * chest lid lowered). The open gate matches the project
     * interaction reach (4.5 blocks, eye to block center).
     *
     * @param target the world-absolute block position; never null
     * @return the opened menu's first snapshot, or null when rejected
     *         (out of reach, unsupported kind, missing container, or
     *         the container is blocked — a cat on the chest counts)
     */
    @Nullable
    MenuView openMenu(CellPos target);

    /**
     * Open the bot's own inventory menu (2x2 crafting grid, armor,
     * full inventory). Closes any open world menu first.
     *
     * @return the inventory menu snapshot; never null
     */
    MenuView openInventoryMenu();

    /**
     * Snapshot the currently open menu.
     *
     * @return the current menu's snapshot, or null when no menu is
     *         open
     */
    @Nullable
    MenuView menuSnapshot();

    /**
     * Click a slot in the open menu and return the post-click
     * snapshot — the answer is the menu as it is after the click.
     *
     * @param slot   the menu's flat slot index (-1 = outside the
     *               window / drop the carried stack)
     * @param button the mouse button; per-kind semantics are
     *               documented on {@link MenuClick}
     * @param type   the click kind; never null
     * @return the post-click snapshot; never null
     * @throws IllegalStateException when no menu is open, the menu is
     *         closed, or the stillValid gate fails (bot out of the
     *         vanilla keep-open range, or the source block changed)
     */
    MenuView menuClick(int slot, int button, MenuClick type);

    /**
     * Close the open menu: crafting-grid materials and the carried
     * stack return to the inventory (dropping at the body when full),
     * chest lids lower. Terminal and idempotent — an at-least-once
     * retry cannot double-run the close.
     */
    void closeMenu();

    /**
     * Click a non-slot button in the open menu (enchantment option,
     * beacon effect selection). Default implementation returns null —
     * claim-only actors and test recordings legitimately have no button
     * surface. The adapter binding delegates to
     * {@code AbstractContainerMenu.clickMenuButton}.
     *
     * @param id the button id as defined by the menu subclass (0-based)
     * @return the post-click snapshot, or null when the menu does not
     *         support button clicks (default implementation)
     * @throws IllegalStateException when no menu is open or the menu is
     *         closed
     */
    default @Nullable MenuView menuButtonClick(int id) {
        return null;
    }

    /**
     * Open a menu backed by a world entity (villager trading, horse
     * inventory). Default implementation returns null — claim-only
     * actors and test recordings legitimately have no entity surface.
     * The adapter binding resolves the entity by runtime id, checks
     * reach, and dispatches to the entity's own menu-opening mechanism
     * (Merchant for villagers, HasCustomInventoryScreen for horses).
     *
     * @param entityId the vanilla runtime entity id (as returned by
     *                 /entities scan); never negative
     * @return the opened menu's first snapshot, or null when rejected
     *         (entity not found, out of reach, untamed horse, or
     *         unsupported entity kind)
     */
    default @Nullable MenuView openEntityMenu(int entityId) {
        return null;
    }

    /**
     * Set the open anvil menu's rename text. AnvilMenu.setItemName
     * updates the rename field and recomputes the result (a rename
     * alone costs 1 level). Default implementation is a no-op —
     * claim-only actors and test recordings legitimately have no
     * rename surface.
     *
     * @param name the new item name, or null/blank to clear
     * @throws IllegalStateException when no menu is open, the menu is
     *         closed, or the open menu is not an anvil
     */
    default void setAnvilName(String name) {}

    /**
     * Set a beacon menu's primary and secondary effects. BeaconMenu does
     * not use button clicks — effects are applied directly, consuming one
     * payment item. Default implementation is a no-op — claim-only actors
     * and test recordings legitimately have no beacon surface.
     *
     * @param primaryKey   the primary effect registry key (e.g.
     *                     "minecraft:speed"), or null for none
     * @param secondaryKey the secondary effect registry key, or null for
     *                     none
     * @return the post-update snapshot, or null for the default no-op
     *         implementation
     * @throws IllegalStateException when no menu is open or the open menu
     *         is not a beacon
     */
    default @Nullable MenuView setBeaconEffects(String primaryKey, String secondaryKey) {
        return null;
    }
}
