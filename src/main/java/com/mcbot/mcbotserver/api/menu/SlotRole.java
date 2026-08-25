package com.mcbot.mcbotserver.api.menu;

/**
 * What a menu slot IS, independent of the menu kind's flat layout.
 * The adapter owns the vanilla layout knowledge (which flat index is
 * the result, where the grid ends, where the hotbar starts); the
 * planner and the harness read roles and never re-derive layout by
 * arithmetic on menu types.
 *
 * <p>Covers the Phase 2 menu kinds (inventory, crafting_table,
 * chest). New menu kinds grow this vocabulary with their own roles
 * (furnace: INPUT / FUEL / OUTPUT) as a review checkpoint — the
 * growth event is the same as adding the menu kind itself.
 */
public enum SlotRole {

    /** Crafting output slot (virtual: a projection of the grid). */
    RESULT,

    /** Crafting input grid slot (2x2 or 3x3). */
    GRID,

    /** Storage slot of the opened world container (chest, barrel). */
    CONTAINER,

    /** Player backpack slot (the 27 above the hotbar). */
    MAIN,

    /** Player hotbar slot (the bottom 9). */
    HOTBAR,

    /** Player armor slot (head / chest / legs / feet). */
    ARMOR,

    /** Player offhand slot. */
    OFFHAND
}
