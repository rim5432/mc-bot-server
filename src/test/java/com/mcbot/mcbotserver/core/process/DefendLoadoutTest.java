package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DefendProcess loadout routing contract, split from
 * {@link DefendProcessTest} (the targeting/verdict semantics stay
 * there): engagement is weapon-aware, not only target-aware. The
 * standoff opening, the melee-weapon ranking, and the mid-fight
 * loadout re-routes (ammunition running out, weapons appearing or
 * vanishing) are one decision family re-derived from the live
 * inventory every engaged tick - engage-time choice and mid-fight
 * choice share one function, never drifting.
 *
 * <p>Contract: see boundaries.md decision 11 and issue 0018 (the
 * keep-range band). Fixtures live in {@link RangedLoadoutFixtures}
 * where they are shared with the combat behavior tests.
 */
class DefendLoadoutTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);
    private static final String ZOMBIE = "minecraft:zombie";
    private static final String SKELETON = "minecraft:skeleton";
    private static final String SWORD = "minecraft:iron_sword";

    private EntitySnapshot zombie(String id, int x) {
        return new EntitySnapshot(id, ZOMBIE, new CellPos(x, 64, 0), 20f, 20f);
    }

    /**
     * A ranged hostile is ENGAGED at the standoff rim when the
     * inventory carries a bow + arrows: no refusal, the directive
     * targets the skeleton, and the chase goal is RANGED_STANDOFF (10)
     * - the mover holds inside the bow band instead of closing to the
     * melee rim (ledger 37 answered the kite-and-shoot mismatch).
     */
    @Test
    void rangedHostileWithBowLoadoutEngagesAtStandoff() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE, SKELETON), Set.of(SKELETON));
        world.setInventory(inventoryWithBow());

        world.addEntity(new EntitySnapshot("S1", SKELETON, new CellPos(6, 64, 0), 20f, 20f));

        Directive directive = m.onTick(world);
        assertTrue(m.isActive(), "armed means the fight is taken, not refused");
        assertEquals("S1", directive.overrides().combat().targetId());
        com.mcbot.mcbotserver.api.goal.GoalRange goal = (com.mcbot.mcbotserver.api.goal.GoalRange) directive.goal();
        assertEquals(DefendProcess.RANGED_STANDOFF - 2, goal.min(), "the band inner edge backs the bot away");
        assertEquals(DefendProcess.RANGED_STANDOFF + 2, goal.max(), "the band outer edge is the approach limit");
    }

    @Test
    void rangedHostileWithoutLoadoutStillRefuses() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE, SKELETON), Set.of(SKELETON));

        world.addEntity(new EntitySnapshot("S1", SKELETON, new CellPos(6, 64, 0), 20f, 20f));

        m.onTick(world);
        assertFalse(m.isActive());
        assertEquals("ENGAGEMENT_REFUSED", m.failureReasonOrNull());
    }

    /**
     * Engagement is weapon-aware, not only target-aware: a bow-only
     * carrier opens a MELEE-target fight at the standoff rim instead
     * of charging the swing rim - charging would trade full-draw
     * arrows for fist-tier bow clubbing once the target arrives.
     * The blind catalog (none()) conservatively trusts the bow, so
     * the standoff applies there too.
     */
    @Test
    void bowOnlyCarrierStandsOffMeleeTarget() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), WeaponCatalog.none());
        world.setInventory(inventoryWithBow());

        world.addEntity(zombie("Z1", 6));

        Directive directive = m.onTick(world);
        assertTrue(m.isActive(), "the fight is taken at standoff, not refused");
        assertEquals("Z1", directive.overrides().combat().targetId());
        com.mcbot.mcbotserver.api.goal.GoalRange goal = (com.mcbot.mcbotserver.api.goal.GoalRange) directive.goal();
        assertEquals(
                DefendProcess.RANGED_STANDOFF - 2,
                goal.min(),
                "the bow-only carrier holds a band whose inner edge triggers backward replanning");
        assertEquals(DefendProcess.RANGED_STANDOFF + 2, goal.max(), "the band outer edge is the approach limit");
    }

    /**
     * The standoff opening is bow-only policy: a carried sword
     * outranking the bow restores the chase to the swing rim - the
     * melee band stays melee when the hotbar can actually answer it.
     */
    @Test
    void meleeWeaponRecoversTheChargeForMeleeTargets() {
        MockWorldView world = new MockWorldView();
        WeaponCatalog catalog = id -> SWORD.equals(id) ? 7f : 0f;
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), catalog);
        world.setInventory(inventoryWithBowAndSword());

        world.addEntity(zombie("Z1", 6));

        Directive directive = m.onTick(world);
        assertTrue(m.isActive());
        assertEquals("Z1", directive.overrides().combat().targetId());
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) directive.goal();
        assertEquals(DefendProcess.GOAL_RANGE, goal.range(), "a real melee weapon charges the swing rim as before");
    }

    /**
     * Unarmed carriers keep the plain melee charge: the standoff
     * opening requires a ranged loadout to open WITH.
     */
    @Test
    void unarmedCarrierChargesMeleeTarget() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), WeaponCatalog.none());

        world.addEntity(zombie("Z1", 6));

        Directive directive = m.onTick(world);
        assertTrue(m.isActive());
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) directive.goal();
        assertEquals(DefendProcess.GOAL_RANGE, goal.range(), "no bow, no standoff - the plain chase");
    }

    private static InventoryView inventoryWithBow() {
        return RangedLoadoutFixtures.inventoryWithBow();
    }

    private static InventoryView inventoryWithBowAndSword() {
        return RangedLoadoutFixtures.inventoryWithBowAndSword();
    }

    /**
     * An own-inventory snapshot carrying a bow but no arrows: the
     * loadout whose hotbarBowSlot answer is -1 - ammunition is part
     * of the loadout, not a bonus.
     *
     * @return the inventory view; never null
     */
    private static InventoryView inventoryWithBowWithoutArrows() {
        java.util.List<ItemView> main =
                new java.util.ArrayList<>(java.util.Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bow", 1));
        return new InventoryView(
                main,
                0,
                java.util.List.copyOf(java.util.Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)),
                ItemView.EMPTY);
    }

    /**
     * Mid-fight re-route, ammunition direction: a bow-only carrier
     * engaged at standoff must charge the swing rim once the arrows
     * are gone. hotbarBowSlot answers -1 without ammunition, and the
     * stance follows the live loadout instead of the engage-time
     * snapshot - an empty bow holding a band it can no longer service
     * is the frozen-decision defect this pins away.
     */
    @Test
    void arrowsRunOutMidFightChargesMelee() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), WeaponCatalog.none());
        world.setInventory(inventoryWithBow());
        world.addEntity(zombie("Z1", 6));

        Directive first = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalRange opening = (com.mcbot.mcbotserver.api.goal.GoalRange) first.goal();
        assertEquals(DefendProcess.RANGED_STANDOFF + 2, opening.max(), "the fight opens at the standoff band");

        world.setInventory(inventoryWithBowWithoutArrows());
        Directive rerouted = m.onTick(world);
        assertTrue(m.isActive(), "the fight continues - re-routed, not failed");
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) rerouted.goal();
        assertEquals(DefendProcess.GOAL_RANGE, goal.range(), "no ammunition means the swing-rim chase");
    }

    /**
     * Mid-fight re-route, weapon direction: a sword appearing in the
     * hotbar mid-standoff closes the fight to the swing rim - the
     * same ranking that decided melee at engage time keeps deciding
     * it while engaged.
     */
    @Test
    void swordPickupMidFightClosesFromStandoff() {
        MockWorldView world = new MockWorldView();
        WeaponCatalog catalog = id -> SWORD.equals(id) ? 7f : 0f;
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), catalog);
        world.setInventory(inventoryWithBow());
        world.addEntity(zombie("Z1", 6));

        Directive first = m.onTick(world);
        assertTrue(first.goal() instanceof com.mcbot.mcbotserver.api.goal.GoalRange, "bow-only opens the standoff");

        world.setInventory(inventoryWithBowAndSword());
        Directive rerouted = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) rerouted.goal();
        assertEquals(DefendProcess.GOAL_RANGE, goal.range(), "the ranked sword restores the charge");
    }

    /**
     * Mid-fight re-route, loadout-gained direction: an unarmed melee
     * engagement opens the standoff once a bow loadout appears - the
     * stance is a function of state, not of history.
     */
    @Test
    void bowPickupMidFightOpensStandoff() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), WeaponCatalog.none());
        world.addEntity(zombie("Z1", 6));

        Directive first = m.onTick(world);
        assertTrue(first.goal() instanceof com.mcbot.mcbotserver.api.goal.GoalNear, "unarmed opens the chase");

        world.setInventory(inventoryWithBow());
        Directive rerouted = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalRange goal = (com.mcbot.mcbotserver.api.goal.GoalRange) rerouted.goal();
        assertEquals(DefendProcess.RANGED_STANDOFF - 2, goal.min(), "the gained loadout holds the standoff band");
    }

    /**
     * The one refusing direction: a ranged-typed target whose loadout
     * vanishes mid-fight fails ENGAGEMENT_REFUSED with the threat
     * type - an unarmed chase against a kiting skeleton is the bleed
     * the engage-time refusal exists to prevent, mid-fight included.
     */
    @Test
    void skeletonLoadoutGoneMidFightRefuses() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE, SKELETON), Set.of(SKELETON));
        world.setInventory(inventoryWithBow());
        world.addEntity(new EntitySnapshot("S1", SKELETON, new CellPos(6, 64, 0), 20f, 20f));

        Directive first = m.onTick(world);
        assertTrue(
                first.goal() instanceof com.mcbot.mcbotserver.api.goal.GoalRange,
                "armed, the skeleton is answered at standoff");

        world.setInventory(inventoryWithBowWithoutArrows());
        m.onTick(world);
        assertFalse(m.isActive(), "the unarmed skeleton fight refuses rather than bleeds");
        assertEquals("ENGAGEMENT_REFUSED", m.failureReasonOrNull());
        assertEquals(SKELETON, m.verdictAttrs().get("threatType"));
    }

    /**
     * Churn guard: an unchanged loadout never flips the stance - the
     * re-evaluation reads state, it must not invent transitions.
     */
    @Test
    void unchangedLoadoutKeepsStanceAcrossTicks() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE), Set.of(), WeaponCatalog.none());
        world.setInventory(inventoryWithBow());
        world.addEntity(zombie("Z1", 6));

        for (int tick = 0; tick < 10; tick++) {
            Directive directive = m.onTick(world);
            assertTrue(
                    directive.goal() instanceof com.mcbot.mcbotserver.api.goal.GoalRange,
                    "stance stable on tick " + tick);
        }
    }
}
