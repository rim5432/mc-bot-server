package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
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
 * Layer-1 gate for combat weapon selection (Stage 3 Phase 4 combat
 * loadout slice): while a combat order runs, the behavior holds the
 * hotbar's highest per-hit weapon via one SLOT claim; ties keep the
 * lower slot; no weapon in the hotbar claims nothing. Per-hit is the
 * whole ranking because mob melee has no attack-speed scaling.
 */
class CombatBehaviorLoadoutTest {

    private static final String SWORD = "minecraft:diamond_sword";

    private static final String AXE = "minecraft:diamond_axe";

    /** Axe 9 beats sword 7 per the vanilla tier table (dossier). */
    private static final WeaponCatalog CATALOG = id -> {
        if (AXE.equals(id)) {
            return 9f;
        }
        if (SWORD.equals(id)) {
            return 7f;
        }
        return 0f;
    };

    @Test
    void holdsHighestPerHitWeapon() {
        CombatBehavior combat = new CombatBehavior("combat", () -> new Vec3(0, 64, 0), CATALOG);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(1, null, null, AXE, null, SWORD));

        combat.tick(world, fightOrder(), actor);

        assertEquals(2, selectedSlotOf(actor), "the axe (9) must beat the sword (7)");
    }

    @Test
    void alreadySelectedWeaponClaimsNothing() {
        CombatBehavior combat = new CombatBehavior("combat", () -> new Vec3(0, 64, 0), CATALOG);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(3, null, null, null, AXE));

        combat.tick(world, fightOrder(), actor);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "holding the best weapon already must not re-claim");
    }

    @Test
    void noWeaponInHotbarClaimsNothing() {
        CombatBehavior combat = new CombatBehavior("combat", () -> new Vec3(0, 64, 0), CATALOG);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, "minecraft:dirt", "minecraft:cooked_beef"));

        combat.tick(world, fightOrder(), actor);

        assertTrue(actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT));
    }

    @Test
    void tieKeepsLowerSlot() {
        CombatBehavior combat = new CombatBehavior("combat", () -> new Vec3(0, 64, 0), CATALOG);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(4, null, SWORD, null, null, null, SWORD));

        combat.tick(world, fightOrder(), actor);

        assertEquals(1, selectedSlotOf(actor), "equal damage keeps the lower hotbar slot");
    }

    @Test
    void nonCombatDirectivesNeverTouchSelection() {
        CombatBehavior combat = new CombatBehavior("combat", () -> new Vec3(0, 64, 0), CATALOG);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, null, AXE));

        combat.tick(world, Directive.of(new GoalNear(new CellPos(4, 64, 4), 1)), actor);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "goto traffic must not re-arm the bot either");
    }

    private static int selectedSlotOf(RecordingActor actor) {
        return actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .map(c -> (Intent.SelectSlot) c.intent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a SLOT claim"))
                .slot();
    }

    private static Directive fightOrder() {
        return new Directive(new GoalNear(new CellPos(2, 64, 0), 1), new Overrides(new Attack("z1")));
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
