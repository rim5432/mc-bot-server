package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for bow ranging (ledger 37): beyond melee reach and
 * inside the bow band, a carried bow plus arrows switches the hotbar
 * to the bow, sustains Use(true) across the charge, and releases on
 * the falling edge; missing arrows, melee-range targets, and dropped
 * directives all fall back to (or release into) the melee path.
 */
class CombatRangedGateTest {

    private static final Vec3 POS = new Vec3(0, 64, 0);

    private static final String BOW = "minecraft:bow";

    private static final String ARROW = "minecraft:arrow";

    private static final String SWORD = "minecraft:iron_sword";

    @Test
    void equipsBowBeforeDrawingWhenUnequipped() {
        CombatBehavior combat = combat();
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, null, null, BOW), ARROW);

        combat.tick(world, orderAt(6), actor);

        assertEquals(3, selectedSlotOf(actor), "the bow slot must be claimed first");
        assertTrue(useClaims(actor).isEmpty(), "no draw before the equip lands");
    }

    @Test
    void sustainedDrawReleasesAfterFullCharge() {
        CombatBehavior combat = combat();
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, SWORD, null, null, BOW), ARROW);

        for (int tick = 0; tick < CombatBehavior.BOW_CHARGE_TICKS + 1; tick++) {
            combat.tick(world, orderAt(6), actor);
        }

        List<Boolean> presses = pressSequence(actor);
        assertEquals(CombatBehavior.BOW_CHARGE_TICKS + 1, presses.size(), "one claim per tick");
        for (int i = 0; i < CombatBehavior.BOW_CHARGE_TICKS; i++) {
            assertTrue(presses.get(i), "charge ticks hold the draw");
        }
        assertFalse(presses.get(CombatBehavior.BOW_CHARGE_TICKS), "the last tick releases");
    }

    @Test
    void meleeRangeStaysMelee() {
        CombatBehavior combat = combat();
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, null, null, BOW), ARROW);

        combat.tick(world, orderAt(2), actor);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "melee range must not re-arm the hotbar");
        assertFalse(useClaims(actor).isEmpty(), "melee pacing still swings");
    }

    @Test
    void noArrowsStaysMelee() {
        CombatBehavior combat = combat();
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, null, null, BOW));

        combat.tick(world, orderAt(6), actor);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "a bow without arrows must not claim SLOT");
        assertTrue(useClaims(actor).isEmpty(), "out of melee reach and without arrows, no swing either");
    }

    @Test
    void droppedDirectiveReleasesTheDraw() {
        CombatBehavior combat = combat();
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, SWORD, null, null, BOW), ARROW);

        combat.tick(world, orderAt(6), actor);
        combat.tick(world, orderAt(6), actor);
        // Directive drops to plain goto: the in-flight draw releases.
        combat.tick(world, Directive.of(new GoalNear(new CellPos(4, 64, 4), 1)), actor);

        List<Boolean> presses = pressSequence(actor);
        assertFalse(presses.isEmpty(), "the release must be emitted");
        assertFalse(presses.get(presses.size() - 1), "the last USE claim is the release");
    }

    private static CombatBehavior combat() {
        return new CombatBehavior("combat", () -> POS);
    }

    /** A combat order whose target sits at the given x distance (z=0). */
    private static Directive orderAt(int x) {
        return new Directive(new GoalNear(new CellPos(x, 64, 0), 1), new Overrides(new Attack("z1")));
    }

    private static MockWorldView world(InventoryView inventory, String... backpackIds) {
        MockWorldView world = new MockWorldView();
        InventoryView inv = inventory;
        if (backpackIds.length > 0) {
            List<ItemView> main = new ArrayList<>(inv.main());
            for (int i = 0; i < backpackIds.length; i++) {
                int slot = InventoryView.HOTBAR_SIZE + i;
                if (slot < InventoryView.MAIN_SIZE && backpackIds[i] != null) {
                    main.set(slot, new ItemView(backpackIds[i], 16));
                }
            }
            inv = new InventoryView(main, inv.selectedSlot(), inv.armor(), inv.offhand());
        }
        world.setInventory(inv);
        return world;
    }

    private static int selectedSlotOf(RecordingActor actor) {
        return actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .map(c -> (Intent.SelectSlot) c.intent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a SLOT claim"))
                .slot();
    }

    private static List<Boolean> pressSequence(RecordingActor actor) {
        return useClaims(actor).stream().map(Intent.Use::pressing).toList();
    }

    private static List<Intent.Use> useClaims(RecordingActor actor) {
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
    private static InventoryView inventory(int selected, String... hotbarIds) {
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
}
