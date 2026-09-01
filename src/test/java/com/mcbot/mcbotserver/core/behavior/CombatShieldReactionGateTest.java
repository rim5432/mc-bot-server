package com.mcbot.mcbotserver.core.behavior;

import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.applySlotFeedback;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.inventory;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.pressSequence;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.useClaims;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.api.world.ProjectileSnapshot;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the reactive shield: an incoming projectile plus a
 * carried shield preempts both attack paths - SLOT takes the shield
 * one tick before the USE press (the actor applies USE before SLOT,
 * so the press must not read the weapon), the hold survives the
 * threat's disappearance for the linger window, and combat resumes
 * with a weapon re-selection the swing waits for. Also pins the
 * hold-item gate: a press fired while the shield is still the held
 * slot would re-raise the block instead of swinging, so a
 * shield-only carrier never presses a swing at all.
 */
class CombatShieldReactionGateTest {

    private static final Vec3 POS = new Vec3(0, 64, 0);

    private static final String SWORD = "minecraft:iron_sword";

    private static final String SHIELD = "minecraft:shield";

    private static final String BOW = "minecraft:bow";

    private static final String ARROW = "minecraft:arrow";

    /** The vanilla-shaped ranking: the sword outranks the bow. */
    private static final com.mcbot.mcbotserver.api.inventory.WeaponCatalog SWORD_OR_BOW =
            id -> SWORD.equals(id) ? 7f : 0f;

    /** An arrow eight blocks out, closing at one block per tick. */
    private static ProjectileSnapshot incomingArrow() {
        return new ProjectileSnapshot(new Vec3(8, 64, 0), new Vec3(-1, 0, 0));
    }

    @Test
    void shieldClaimsSlotBeforeThePress() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, SHIELD));
        world.addProjectile(incomingArrow());

        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);

        List<com.mcbot.mcbotserver.api.actor.Claim> slots = actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .toList();
        assertEquals(1, slots.size(), "one slot claim, to the shield");
        assertEquals(
                1,
                ((com.mcbot.mcbotserver.api.actor.Intent.SelectSlot)
                                slots.get(0).intent())
                        .slot());
        assertTrue(useClaims(actor).isEmpty(), "no press on the switch tick - it would read the sword");

        combat.tick(world, orderAt(2), actor);
        List<Boolean> presses = pressSequence(actor);
        assertEquals(1, presses.size(), "the press lands the tick after the slot claim");
        assertTrue(presses.get(0), "the press raises the shield");
    }

    @Test
    void holdSurvivesWhileTheThreatFlies() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, SHIELD));

        for (int tick = 0; tick < 6; tick++) {
            world.addProjectile(incomingArrow());
            combat.tick(world, orderAt(2), actor);
            applySlotFeedback(world, actor);
        }

        List<Boolean> presses = pressSequence(actor);
        assertEquals(5, presses.size(), "one slot tick, then one claim per flight tick");
        for (Boolean press : presses) {
            assertTrue(press, "the block holds - no melee pair may interleave");
        }
    }

    @Test
    void threatPassedReleasesAfterLingerAndResumesMelee() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS, SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, SHIELD));

        world.addProjectile(incomingArrow());
        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        world.clearProjectiles();

        for (int tick = 0; tick < CombatBehavior.BLOCK_LINGER_TICKS; tick++) {
            combat.tick(world, orderAt(2), actor);
            applySlotFeedback(world, actor);
        }
        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);

        List<Boolean> presses = pressSequence(actor);
        // [raise, linger x10, release, sword-switch tick has no press, swing, unlatch]
        assertEquals(14, presses.size(), "raise + 10 linger + release + swing pair: " + presses);
        assertTrue(presses.get(0), "the raise");
        for (int i = 1; i <= CombatBehavior.BLOCK_LINGER_TICKS; i++) {
            assertTrue(presses.get(i), "linger tick " + i + " holds the guard");
        }
        assertFalse(presses.get(11), "the guard releases after the linger");
        assertTrue(presses.get(12), "the swing fires once the sword lands");
        assertFalse(presses.get(13), "the swing unlatches");
        List<com.mcbot.mcbotserver.api.actor.Claim> slots = actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .toList();
        assertEquals(
                0,
                ((com.mcbot.mcbotserver.api.actor.Intent.SelectSlot)
                                slots.get(slots.size() - 1).intent())
                        .slot(),
                "the sword is re-selected after the block");
    }

    @Test
    void noShieldCarriedFightsOn() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD));
        world.addProjectile(incomingArrow());

        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(2, presses.size(), "plain melee pacing despite the arrow");
        assertTrue(presses.get(0), "the swing fires");
        assertFalse(presses.get(1), "the swing unlatches");
        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "nothing to switch to - no shield carried");
    }

    @Test
    void ownShotIsIgnored() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, SHIELD));
        world.addProjectile(new ProjectileSnapshot(new Vec3(-1, 64, 0), new Vec3(-3, 0, 0)));

        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(2, presses.size(), "a receding flight earns no block");
        assertTrue(presses.get(0), "the swing fires");
        assertFalse(presses.get(1), "the swing unlatches");
    }

    @Test
    void committedDrawReleasesForTheBlock() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS, SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, SWORD, null, null, BOW, SHIELD), ARROW);

        for (int tick = 0; tick < 12; tick++) {
            combat.tick(world, orderAt(6), actor);
            applySlotFeedback(world, actor);
        }
        world.addProjectile(incomingArrow());
        combat.tick(world, orderAt(6), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(6), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(6), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(14, presses.size(), "12 charge ticks, the release, the raise: " + presses);
        for (int i = 0; i < 12; i++) {
            assertTrue(presses.get(i), "charge tick " + i + " holds");
        }
        assertFalse(presses.get(12), "even a committed draw releases for the block");
        assertTrue(presses.get(13), "the shield raises right after");
        List<com.mcbot.mcbotserver.api.actor.Claim> slots = actor.submitted.stream()
                .filter(c -> c.channel() == Channel.SLOT)
                .toList();
        assertEquals(
                4,
                ((com.mcbot.mcbotserver.api.actor.Intent.SelectSlot)
                                slots.get(slots.size() - 1).intent())
                        .slot(),
                "the shield slot is taken");
    }

    @Test
    void droppedDirectiveReleasesTheBlock() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, SHIELD));

        world.addProjectile(incomingArrow());
        combat.tick(world, orderAt(2), actor);
        applySlotFeedback(world, actor);
        combat.tick(world, orderAt(2), actor);

        // Directive drops to plain goto: the guard must not outlive it.
        combat.tick(world, Directive.of(new GoalNear(new CellPos(4, 64, 4), 1)), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(2, presses.size(), "raise then release");
        assertTrue(presses.get(0), "the raise");
        assertFalse(presses.get(1), "the last USE claim is the release");
    }

    @Test
    void shieldOnlyCarrierNeverPressesASwing() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SHIELD));

        for (int tick = 0; tick < 3; tick++) {
            combat.tick(world, orderAt(2), actor);
            applySlotFeedback(world, actor);
        }

        assertTrue(useClaims(actor).isEmpty(), "a press on the held shield would re-raise it, never swing");
    }

    /** A combat order whose target sits at the given x distance (z=0). */
    private static Directive orderAt(int x) {
        return new Directive(new GoalNear(new CellPos(x, 64, 0), 1), new Overrides(new Attack("z1")));
    }

    private static MockWorldView world(InventoryView inventory, String... backpackIds) {
        MockWorldView world = new MockWorldView();
        InventoryView inv = inventory;
        if (backpackIds.length > 0) {
            List<com.mcbot.mcbotserver.api.inventory.ItemView> main = new java.util.ArrayList<>(inv.main());
            for (int i = 0; i < backpackIds.length; i++) {
                int slot = InventoryView.HOTBAR_SIZE + i;
                if (slot < InventoryView.MAIN_SIZE && backpackIds[i] != null) {
                    main.set(slot, new com.mcbot.mcbotserver.api.inventory.ItemView(backpackIds[i], 16));
                }
            }
            inv = new InventoryView(main, inv.selectedSlot(), inv.armor(), inv.offhand());
        }
        world.setInventory(inv);
        return world;
    }
}
