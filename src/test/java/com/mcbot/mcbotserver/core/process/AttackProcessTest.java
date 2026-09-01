package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.inventory.WeaponCatalog;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * AttackProcess directed-engagement contract: the HANDED target id
 * is fought (any type - a cow is as valid as a zombie), a never-seen
 * id fails fast, absence past grace fails ESCAPED (the conservative
 * verdict, same rationale as defend), leash and timeout behave as
 * in defend, and resume() adjudicates on the next scan instead of
 * trusting the pre-pause target.
 *
 * <p>Contract: see boundaries.md decision 10/11 and ledger 39.
 */
class AttackProcessTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);
    private static final String COW = "minecraft:cow";
    private static final InterruptionContext CTX =
            new InterruptionContext(1L, BOT, "attack:t1", "reflex-preempt:test", "");

    private AttackProcess mission() {
        return new AttackProcess("t1", "cow-7", 50, 1000L, () -> BOT);
    }

    private EntitySnapshot cow(String id, int x) {
        return new EntitySnapshot(id, COW, new CellPos(x, 64, 0), 10f, 10f);
    }

    /**
     * A bow-only carrier opens the directed fight at the standoff rim
     * - the same weapon-aware engagement defend uses - and the
     * decision freezes at first sight for the whole fight.
     */
    @Test
    void bowOnlyCarrierOpensAtStandoffAndFreezesTheDecision() {
        MockWorldView world = new MockWorldView();
        world.setInventory(inventoryWithBow());
        AttackProcess m = new AttackProcess("t1", "cow-7", 50, 1000L, () -> BOT, WeaponCatalog.none());
        world.addEntity(cow("cow-7", 6));

        Directive first = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) first.goal();
        assertEquals(
                DefendProcess.RANGED_STANDOFF,
                goal.range(),
                "bow-only opens at the standoff rim instead of charging into clubbing");

        // Later ticks hold the frozen decision even as the target
        // closes - no per-tick flip-flop.
        world.removeEntity("cow-7");
        world.addEntity(cow("cow-7", 3));
        Directive second = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalNear held = (com.mcbot.mcbotserver.api.goal.GoalNear) second.goal();
        assertEquals(DefendProcess.RANGED_STANDOFF, held.range(), "the engage-time range decision is frozen");
    }

    /**
     * A carried sword outranking the bow restores the plain chase:
     * the standoff opening is bow-only policy, not a general tax.
     */
    @Test
    void meleeWeaponKeepsTheDirectedChase() {
        MockWorldView world = new MockWorldView();
        world.setInventory(inventoryWithBowAndSword());
        WeaponCatalog catalog = id -> id.endsWith("iron_sword") ? 7f : 0f;
        AttackProcess m = new AttackProcess("t1", "cow-7", 50, 1000L, () -> BOT, catalog);
        world.addEntity(cow("cow-7", 6));

        Directive d = m.onTick(world);
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) d.goal();
        assertEquals(AttackProcess.GOAL_RANGE, goal.range(), "a real melee weapon charges the swing rim");
    }

    /**
     * An own-inventory snapshot carrying a bow in slot 0 and arrows in
     * the backpack - the minimal ranged loadout.
     *
     * @return the inventory view; never null
     */
    private static InventoryView inventoryWithBow() {
        java.util.List<ItemView> main =
                new java.util.ArrayList<>(java.util.Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bow", 1));
        main.set(InventoryView.HOTBAR_SIZE, new ItemView("minecraft:arrow", 32));
        return new InventoryView(
                main,
                0,
                java.util.List.copyOf(java.util.Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)),
                ItemView.EMPTY);
    }

    /**
     * The bow-plus-sword loadout: a ranged loadout that must NOT
     * trigger the standoff opening.
     *
     * @return the inventory view; never null
     */
    private static InventoryView inventoryWithBowAndSword() {
        java.util.List<ItemView> main =
                new java.util.ArrayList<>(java.util.Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView("minecraft:bow", 1));
        main.set(1, new ItemView("minecraft:iron_sword", 1));
        main.set(InventoryView.HOTBAR_SIZE, new ItemView("minecraft:arrow", 32));
        return new InventoryView(
                main,
                0,
                java.util.List.copyOf(java.util.Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)),
                ItemView.EMPTY);
    }

    @Test
    void engagesTheHandedIdWhateverItsType() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-9", 3));
        world.addEntity(cow("cow-7", 6));

        Directive d = m.onTick(world);
        assertTrue(m.isActive(), "the handed cow must be engaged");
        Attack attack = (Attack) d.overrides().combat();
        assertEquals("cow-7", attack.targetId(), "the directive targets the HANDED id, not the nearest cow");
        assertEquals("cow-7", m.verdictAttrs().get("targetId"), "verdict attrs carry the target id");
    }

    @Test
    void neverSeenIdFailsFastAsNoSuchEntity() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-9", 3));

        m.onTick(world);
        assertFalse(m.isActive(), "an id absent from the very first scan must fail at once");
        assertEquals(AttackProcess.REASON_NO_TARGET, m.failureReasonOrNull());
        assertFalse(m.missionSucceeded());
    }

    @Test
    void absencePastGraceFailsEscapedConservatively() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-7", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        world.removeEntity("cow-7");
        for (int i = 0; i < AttackProcess.TARGET_GRACE_TICKS; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "grace tick " + i + " must hold");
        }
        m.onTick(world);
        assertFalse(m.isActive(), "past grace the verdict fires");
        assertEquals(AttackProcess.REASON_ESCAPED, m.failureReasonOrNull());
    }

    @Test
    void targetBeyondLeashFailsLost() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-7", 6));
        m.onTick(world);

        // 14 blocks: inside the scan envelope, beyond the leash.
        world.removeEntity("cow-7");
        world.addEntity(cow("cow-7", 14));
        m.onTick(world);
        assertFalse(m.isActive(), "a kited target beyond the leash ends the mission");
        assertEquals(AttackProcess.REASON_LOST, m.failureReasonOrNull());
    }

    @Test
    void timeoutFailsTheMission() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = new AttackProcess("t2", "cow-7", 50, 5L, () -> BOT);
        world.addEntity(cow("cow-7", 6));
        for (int i = 0; i < 5; i++) {
            m.onTick(world);
        }
        assertFalse(m.isActive(), "the tick budget ends the fight");
        assertEquals(AttackProcess.REASON_TIMEOUT, m.failureReasonOrNull());
    }

    @Test
    void resumeAdjudicatesOnTheNextScanNotOnTrust() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-7", 6));
        m.onTick(world);
        m.onLostControl(CTX);

        assertTrue(m.resume(CTX), "an active mission may resume");
        world.removeEntity("cow-7");
        // resume() spent the grace credit: ONE tick adjudicates.
        m.onTick(world);
        assertFalse(m.isActive(), "an absent target fails immediately post-resume");
        assertEquals(AttackProcess.REASON_ESCAPED, m.failureReasonOrNull());

        // And the present case: resume, target still there, fight
        // continues.
        MockWorldView live = new MockWorldView();
        AttackProcess m2 = mission();
        live.addEntity(cow("cow-7", 6));
        m2.onTick(live);
        m2.onLostControl(CTX);
        assertTrue(m2.resume(CTX));
        Directive d = m2.onTick(live);
        assertTrue(m2.isActive(), "a present target resets the grace counter and continues");
        assertEquals("cow-7", ((Attack) d.overrides().combat()).targetId());
    }

    @Test
    void corpseSightingCompletesTheMissionAsAKill() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        // The last sighting before death - health zero, still scanned
        // (the sensor does not filter dead entities).
        world.addEntity(new EntitySnapshot("cow-7", COW, new CellPos(6, 64, 0), 0f, 10f));

        m.onTick(world);

        assertFalse(m.isActive(), "the kill verdict is terminal");
        assertTrue(m.missionSucceeded(), "a corpse sighting IS the confirmed kill");
        assertEquals("cow-7", m.verdictAttrs().get("targetId"));
    }

    @Test
    void cancelVerdictSurvivesLaterPreemption() {
        MockWorldView world = new MockWorldView();
        AttackProcess m = mission();
        world.addEntity(cow("cow-7", 6));
        m.onTick(world);

        m.abort();
        m.onContextInvalidated();

        assertEquals(
                "CANCELLED",
                m.failureReasonOrNull(),
                "the harness cancel verdict must not be overwritten by preemption");
    }

    @Test
    void preemptionInvalidationFailsTheMission() {
        AttackProcess m = mission();
        m.onContextInvalidated();
        assertFalse(m.isActive());
        assertEquals(AttackProcess.REASON_PREEMPTED, m.failureReasonOrNull());
    }
}
