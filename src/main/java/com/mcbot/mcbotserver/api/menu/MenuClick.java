package com.mcbot.mcbotserver.api.menu;

/**
 * Click vocabulary for menu transactions: the api-side stand-in for
 * the engine's click types, keeping {@code net.minecraft} out of
 * every signature a core planner can reach (boundaries.md section A:
 * core has zero MC imports).
 *
 * <p>Deliberate deviation from the engine taxonomy: {@code CLONE}
 * (creative-mode middle-click pick-block — the device is survival)
 * and {@code QUICK_CRAFT} (the stateful drag-paint protocol — a
 * planner composes the same effects from PICKUP and THROW) are
 * excluded. Growing this enum is a review checkpoint (issue 0007
 * §6.2), same discipline as the pinned boundary-interface list.
 */
public enum MenuClick {

    /**
     * Pick up or put down the carried stack. Button 0 = whole stack,
     * button 1 = single item.
     */
    PICKUP,

    /**
     * Shift-move between the container region and the player
     * inventory. Button is ignored.
     */
    QUICK_MOVE,

    /**
     * Drop from the clicked slot to the world. Button 0 = one item,
     * button 1 = the whole stack.
     */
    THROW
}
