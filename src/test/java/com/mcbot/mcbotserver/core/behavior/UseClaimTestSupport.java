package com.mcbot.mcbotserver.core.behavior;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared assertions-and-fixtures for the USE-channel behavior gate
 * family (combat ranging, fishing): the claim-filtering readers a
 * {@link RecordingActor} needs plus the hotbar-shaped inventory
 * snapshot builder. Extracted when CPD flagged the two gate tests
 * carrying byte-identical private helpers - one shape, one owner.
 */
final class UseClaimTestSupport {

    private UseClaimTestSupport() {}

    /**
     * The first SLOT claim's selected slot.
     *
     * @param actor the recorded submissions; never null
     * @return the slot index the behavior selected
     */
    static int selectedSlotOf(RecordingActor actor) {
        return actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .map(c -> (Intent.SelectSlot) c.intent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a SLOT claim"))
                .slot();
    }

    /**
     * The USE-channel press sequence, in submission order.
     *
     * @param actor the recorded submissions; never null
     * @return the pressing flags; never null
     */
    static List<Boolean> pressSequence(RecordingActor actor) {
        return useClaims(actor).stream().map(Intent.Use::pressing).toList();
    }

    /**
     * Every USE-channel Intent.Use claim, in submission order.
     *
     * @param actor the recorded submissions; never null
     * @return the use intents; never null
     */
    static List<Intent.Use> useClaims(RecordingActor actor) {
        List<Intent.Use> uses = new ArrayList<>();
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.USE && c.intent() instanceof Intent.Use u) {
                uses.add(u);
            }
        }
        return uses;
    }

    /**
     * An own-inventory snapshot with the given hotbar contents: a null
     * entry (or past the array) means an empty slot.
     *
     * @param selected  current hotbar selection 0..8
     * @param hotbarIds item ids for hotbar slots 0..8 in order; null
     *                  leaves the slot empty
     * @return the inventory view; never null
     */
    static InventoryView inventory(int selected, String... hotbarIds) {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        for (int i = 0; i < Math.min(hotbarIds.length, InventoryView.HOTBAR_SIZE); i++) {
            if (hotbarIds[i] != null) {
                main.set(i, new ItemView(hotbarIds[i], 1));
            }
        }
        return new InventoryView(
                main,
                selected,
                List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)),
                ItemView.EMPTY);
    }

    /**
     * Feeds the actor's latest SLOT claim back into the mock's
     * selected slot. BindingActor.applySlot writes the body's held
     * slot at flush time, and the combat behavior's hold-item gate
     * reads that write - a multi-tick transition test that never
     * applies the feedback freezes the held item where it started.
     *
     * @param world the mock whose selection follows the claims; never null
     * @param actor the recorded submissions; never null
     */
    static void applySlotFeedback(com.mcbot.mcbotserver.core.world.MockWorldView world, RecordingActor actor) {
        int slot = -1;
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.SLOT && c.intent() instanceof Intent.SelectSlot s) {
                slot = s.slot();
            }
        }
        if (slot >= 0) {
            InventoryView inv = world.getInventory();
            world.setInventory(new InventoryView(inv.main(), slot, inv.armor(), inv.offhand()));
        }
    }
}
