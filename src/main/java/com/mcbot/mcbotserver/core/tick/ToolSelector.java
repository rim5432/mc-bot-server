package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.actor.ToolCatalog;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.Objects;

/**
 * Dig tool auto-selection for the seated dig-family missions (issue
 * 0007 6.1 tool slice): before a mission's aim-and-dig pair drives,
 * scan the hotbar for the item that digs the target block fastest
 * and hold it via one SLOT claim. Mirrors combat's holdBestWeapon
 * with one deliberate difference: the switch fires only when the
 * best hotbar tool is STRICTLY faster than the held item - equal
 * speed buys nothing, so the held slot wins ties and equal-speed
 * churn never flickers the selection. The empty-hand baseline is
 * 1.0 in vanilla ({@code DiggerItem} answers {@code speed} only
 * inside its mineable tag), so anything at or below the held item's
 * speed never justifies a switch.
 *
 * <p>Mission path only: the suffocation reflex digs without this
 * step on purpose - an emergency dig must not spend a SLOT claim
 * that could collide with the EAT reflex's select-and-use pair.
 */
final class ToolSelector {

    private static final float BASELINE = 1.0f;

    private final ToolCatalog tools;

    /**
     * Creates the selector over one catalog.
     *
     * @param tools dig-speed seam; never null
     */
    ToolSelector(ToolCatalog tools) {
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    /**
     * Submit the tool switch for one digging tick when the hotbar
     * holds something strictly faster than the selected item. Idempotent
     * per tick: holding the best tool already submits nothing, so no
     * latch is needed - the guard is the baseline comparison itself.
     * Air or unloaded targets submit nothing (stale geometry must not
     * mint a claim).
     *
     * @param world    read surface for inventory and target block; never
     *                 null
     * @param actor    claim surface; never null
     * @param priority the digging driver's priority
     * @param holder   stable claim owner, same string as the aim-and-dig
     *                 pair; never null
     * @param target   the cell being dug; never null
     */
    void select(WorldView world, Actor actor, int priority, String holder, CellPos target) {
        BlockSnapshot snapshot = world.getBlock(target, ViewMode.LIVE);
        if (snapshot == null) {
            return;
        }
        String blockId = snapshot.blockId();
        if (BlockSnapshot.AIR.equals(blockId) || BlockSnapshot.UNKNOWN.equals(blockId)) {
            return;
        }
        InventoryView inventory = world.getInventory();
        float heldSpeed = speedOf(inventory, inventory.selectedSlot(), blockId);
        int best = -1;
        float bestSpeed = heldSpeed;
        for (int slot = 0; slot < InventoryView.HOTBAR_SIZE; slot++) {
            float speed = speedOf(inventory, slot, blockId);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        if (best >= 0) {
            actor.submit(new Claim(Channel.SLOT, priority, holder, new Intent.SelectSlot(best)));
        }
    }

    /** Vanilla destroy speed of one hotbar slot against the block; empty hand answers the 1.0 baseline. */
    private float speedOf(InventoryView inventory, int slot, String blockId) {
        var item = inventory.main().get(slot);
        if (item.isEmpty()) {
            return BASELINE;
        }
        return tools.destroySpeed(item.itemId(), blockId);
    }
}
