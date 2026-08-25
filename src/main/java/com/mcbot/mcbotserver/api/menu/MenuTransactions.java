package com.mcbot.mcbotserver.api.menu;

import com.mcbot.mcbotserver.api.types.CellPos;

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
}
