package com.mcbot.mcbotserver.core.behavior;

import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.inventory;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.pressSequence;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.selectedSlotOf;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.useClaims;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for bow ranging (ledger 37): beyond melee reach and
 * inside the bow band, a carried bow plus arrows switches the hotbar
 * to the bow, sustains Use(true) across the charge, and releases on
 * the falling edge; missing arrows, melee-range targets, and dropped
 * directives all fall back to (or release into) the melee path. The
 * melee fallback is itself gated twice: a draw past the commit
 * threshold finishes its shot when the range flips (hysteresis), and
 * under the weapon-aware configuration a bow-only carrier keeps
 * firing point-blank instead of clubbing.
 */
class CombatRangedGateTest {

    private static final Vec3 POS = new Vec3(0, 64, 0);

    private static final String BOW = "minecraft:bow";

    private static final String ARROW = "minecraft:arrow";

    private static final String SWORD = "minecraft:iron_sword";

    /**
     * The vanilla-shaped ranking the weapon-aware tests run under:
     * the sword outranks the bow (which carries no melee modifier) -
     * the production assembly's {@code VanillaWeaponCatalog} shape.
     */
    private static final com.mcbot.mcbotserver.api.inventory.WeaponCatalog SWORD_OR_BOW =
            id -> SWORD.equals(id) ? 7f : 0f;

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
        assertFalse(strikeClaims(actor).isEmpty(), "melee pacing still swings");
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

    /**
     * Draw-commit hysteresis: a draw past {@link CombatBehavior#BOW_COMMIT_TICKS}
     * is finished, not aborted, when the target closes inside
     * ATTACK_REACH mid-charge. The old per-tick distance reflex let a
     * target oscillating across the 3-block boundary restart the draw
     * every approach tick and never release at all - the wasted-charge
     * disease the 08-31 engine run exposed.
     */
    @Test
    void committedDrawFinishesWhenTheTargetClosesToMelee() {
        Vec3[] position = {new Vec3(0, 64, 0)};
        CombatBehavior combat = new CombatBehavior("combat", () -> position[0], SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, SWORD, null, null, BOW), ARROW);

        // Target 6.5 away: the draw runs on the ranged path.
        for (int tick = 0; tick < 12; tick++) {
            combat.tick(world, orderAt(6), actor);
            UseClaimTestSupport.applySlotFeedback(world, actor);
        }
        // Target now 2.7 away - inside the melee band - but the draw
        // is charged past the commit threshold and finishes.
        position[0] = new Vec3(4, 64, 0);
        for (int tick = 0; tick < 12; tick++) {
            combat.tick(world, orderAt(6), actor);
            UseClaimTestSupport.applySlotFeedback(world, actor);
        }

        List<Boolean> presses = pressSequence(actor);
        assertEquals(23, presses.size(), "20 charge, release, sword-switch gap, swing pair: " + presses);
        for (int i = 0; i < CombatBehavior.BOW_CHARGE_TICKS; i++) {
            assertTrue(presses.get(i), "the draw holds through the range flip at tick " + i);
        }
        assertFalse(presses.get(20), "the committed shot releases");
        assertTrue(presses.get(21), "the melee path takes over right after the shot");
        assertFalse(presses.get(22), "the melee swing unlatches");
        assertEquals(0, selectedSlotOf(actor), "the sword is drawn only after the shot lands");
    }

    /**
     * The commit guard is not a general draw immunity: below the
     * threshold the flip to melee still aborts immediately and draws
     * the sword - the old economics stay correct for a barely-started
     * draw.
     */
    @Test
    void freshDrawStillAbortsWhenTheTargetClosesToMelee() {
        Vec3[] position = {new Vec3(0, 64, 0)};
        CombatBehavior combat = new CombatBehavior("combat", () -> position[0], SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, SWORD, null, null, BOW), ARROW);

        for (int tick = 0; tick < 4; tick++) {
            combat.tick(world, orderAt(6), actor);
        }
        position[0] = new Vec3(4, 64, 0);
        combat.tick(world, orderAt(6), actor);
        UseClaimTestSupport.applySlotFeedback(world, actor);
        combat.tick(world, orderAt(6), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(6, presses.size(), "4 charge ticks, the abort, the first swing: " + presses);
        assertFalse(presses.get(4), "a barely-started draw is aborted, not finished");
        assertTrue(presses.get(5), "the swing fires once the sword lands");
        assertEquals(0, selectedSlotOf(actor), "the melee band draws the sword");
    }

    /**
     * Weapon-aware point-blank policy: a bow-only carrier keeps
     * drawing inside ATTACK_REACH - full-draw arrows replace fist-tier
     * bow clubbing once the target arrives (the close-in half of the
     * standoff opening DefendProcess orders for melee targets).
     */
    @Test
    void bowOnlyCarrierKeepsFiringInsideMeleeReach() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS, SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, BOW), ARROW);

        for (int tick = 0; tick < 22; tick++) {
            combat.tick(world, orderAt(2), actor);
        }

        List<Boolean> presses = pressSequence(actor);
        assertTrue(presses.size() >= 21, "the draw runs to release: " + presses.size());
        for (int i = 0; i < CombatBehavior.BOW_CHARGE_TICKS; i++) {
            assertTrue(presses.get(i), "charge tick " + i + " holds - melee-range clubbing would alternate");
        }
        assertFalse(presses.get(CombatBehavior.BOW_CHARGE_TICKS), "the shot releases at full charge");
        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "nothing outranks the bow, so the hotbar never switches");
    }

    /**
     * The point-blank policy is not a hijack: under the weapon-aware
     * configuration a carried sword still owns the melee band - the
     * bow only answers inside ATTACK_REACH while nothing outranks it.
     */
    @Test
    void carriedSwordKeepsTheMeleeBandUnderWeaponAwareness() {
        CombatBehavior combat = new CombatBehavior("combat", () -> POS, SWORD_OR_BOW);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, SWORD, BOW), ARROW);

        combat.tick(world, orderAt(2), actor);
        combat.tick(world, orderAt(2), actor);

        assertTrue(
                actor.submitted.stream().noneMatch(c -> c.channel() == Channel.SLOT),
                "the sword is already held - and melee-range must not re-arm the bow");
        List<Boolean> presses = pressSequence(actor);
        assertEquals(2, presses.size(), "melee pacing: one swing pair, not a draw");
        assertTrue(presses.get(0), "the swing fires");
        assertFalse(presses.get(1), "the swing unlatches - a draw would hold true");
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
}
