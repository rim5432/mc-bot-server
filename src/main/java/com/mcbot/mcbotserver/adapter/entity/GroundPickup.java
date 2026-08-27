package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Player-shaped ground-item pickup (Player.java:577 area - the
 * dispatch is player-side scanning, there is no Entity.touch to
 * override; issue 0010 section 7's open dependency, no acquisition
 * strategy works without it): items intersecting the body route
 * into the binding container - SimpleContainer.addItem mutates the
 * stack down to its remainder and a fully-taken item discards.
 * Pickup-delay and removal rules are honored; no sound (the mob
 * carrier has no pickup sound event to ride).
 */
final class GroundPickup {

    private final BotBodyEntity body;

    /**
     * Creates the pickup over one body.
     *
     * @param body the picking body; never null
     */
    GroundPickup(BotBodyEntity body) {
        this.body = body;
    }

    /** Scans once per tick; zero-cost when nothing intersects. */
    void tick() {
        for (Entity entity :
                body.level().getEntities(body, body.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (entity.isRemoved() || !(entity instanceof ItemEntity item)) {
                continue;
            }
            if (body.level().isClientSide || item.hasPickUpDelay()) {
                continue;
            }
            ItemStack stack = item.getItem();
            if (!stack.isEmpty()) {
                ItemStack remainder = body.getInventory().container().addItem(stack);
                if (remainder.isEmpty()) {
                    item.discard();
                }
            }
        }
    }
}
