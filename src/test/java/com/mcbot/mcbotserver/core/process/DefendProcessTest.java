package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DefendProcess target-locking contract: an engaged target is resolved
 * by identity, not by proximity. A nearer hostile must not drop the
 * current target; genuine absence fires grace-then-SUCCESS; dying
 * entities (health=0, still in level) remain valid engagement targets
 * until removed; resume() after preemption keeps a present target
 * despite a closer hostile appearing during the pause.
 *
 * <p>Contract: see ADR-0002 section 1 and boundaries.md decision 11.
 * The target-stickiness fix ensures the inner arbitration (which hostile
 * to fight) is explicit, not a side effect of nearestHostile ordering.
 */
class DefendProcessTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);
    private static final String ZOMBIE = "minecraft:zombie";
    private static final String SKELETON = "minecraft:skeleton";
    private static final Set<String> HOSTILES = Set.of(ZOMBIE);
    private static final InterruptionContext CTX =
            new InterruptionContext(1L, BOT, "defend:t1", "reflex-preempt:test", "");

    private DefendProcess mission() {
        return new DefendProcess("t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, HOSTILES);
    }

    private EntitySnapshot zombie(String id, int x) {
        return new EntitySnapshot(id, ZOMBIE, new CellPos(x, 64, 0), 20f, 20f);
    }

    /**
     * D-corrected sticky-target scenario: A engages at distance 6
     * (within ENGAGE_RADIUS=8), moves to 10 (chasing, within leash=12
     * and scan=14). B injects at 7 — nearer than A, nearestHostile
     * returns B. The engaged branch must resolve A by id, keep fighting
     * for 15 ticks, and emit directives targeting A (not B).
     */
    @Test
    void closerHostileDoesNotDropEngagedTarget() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive(), "must engage A at distance 6");

        world.removeEntity("A");
        world.addEntity(zombie("A", 10));
        world.addEntity(zombie("B", 7));

        for (int i = 0; i < 15; i++) {
            Directive d = m.onTick(world);
            assertTrue(m.isActive(), "tick " + i + ": A must stay engaged despite B at 7");
            assertFalse(m.missionSucceeded(), "tick " + i + ": must not report SUCCESS for live A");
            // R1: the directive must target A, not B — goal switching
            // is observable through the Attack override's targetId.
            Attack attack = (Attack) d.overrides().combat();
            assertEquals("A", attack.targetId(), "tick " + i + ": directive must attack A, not B");
        }
    }

    /**
     * When the engaged target is genuinely removed (death / despawn),
     * grace counts from 1 to 10 (still active), and the 11th tick
     * trips ticksSinceSeen > TARGET_GRACE_TICKS → succeed().
     */
    @Test
    void genuineAbsenceFiresEscapedAfterGrace() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        world.removeEntity("A");

        for (int i = 0; i < 10; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "grace tick " + i + " (ticksSinceSeen=" + (i + 1) + ") must still be active");
        }
        m.onTick(world);
        assertFalse(m.isActive(), "11th tick must retire the mission");
        assertFalse(m.missionSucceeded());
        assertEquals(DefendProcess.REASON_ESCAPED, m.failureReasonOrNull());
    }

    /**
     * C: pins the process-layer contract that health=0 snapshots are
     * valid engagement targets. BindingWorldView does not filter by
     * health (verified at adapter layer), so a dying entity in its
     * ~20-tick death animation is still returned by getEntities and
     * matched by id. Terminal state is delayed until actual removal,
     * not until health hits zero. The sensor-side no-filter contract
     * requires an adapter-layer test; this test pins only the process
     * behavior. After removal the bot cannot distinguish death from
     * escape, so it reports TARGET_ESCAPED (issue 0006).
     */
    @Test
    void dyingEntityRemainsEngagedUntilRemoved() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        // Replace with health=0 snapshot (death animation state)
        world.removeEntity("A");
        world.addEntity(new EntitySnapshot("A", ZOMBIE, new CellPos(6, 64, 0), 0f, 20f));

        for (int i = 0; i < 15; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "tick " + i + ": health=0 entity still matched by id");
            assertFalse(m.missionSucceeded());
        }

        // Actual removal → grace → TARGET_ESCAPED (bot cannot tell
        // death from escape; conservative failure verdict per issue 0006)
        world.removeEntity("A");
        for (int i = 0; i < 10; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "grace tick " + i);
        }
        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals(DefendProcess.REASON_ESCAPED, m.failureReasonOrNull());
    }

    /**
     * B3: an area with no hostile at submission time completes
     * immediately (non-engaged branch, nearest == null → succeed()).
     */
    @Test
    void emptyAreaCompletesImmediately() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        m.onTick(world);
        assertFalse(m.isActive());
        assertTrue(m.missionSucceeded());
        assertNull(m.failureReasonOrNull());
    }

    /**
     * B2: resume() after preemption sets ticksSinceSeen =
     * TARGET_GRACE_TICKS (10). The first post-resume tick is the
     * razor edge: if the target is still present, findTarget resets
     * the counter and the fight continues — even if a closer hostile
     * appeared during the pause. Old code: nearest=B≠A → grace branch
     * → 10++ = 11 > 10 → false SUCCESS on the very first tick.
     */
    @Test
    void resumeKeepsTargetDespiteCloserHostile() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        // Preemption: resume spends all grace credit
        assertTrue(m.resume(CTX));

        // During the pause, a closer hostile appears
        world.addEntity(zombie("B", 4));

        // First post-resume tick: target present → reset → continue
        Directive d = m.onTick(world);
        assertTrue(m.isActive(), "first post-resume tick must keep fighting when A is present");
        assertFalse(m.missionSucceeded());
        Attack attack = (Attack) d.overrides().combat();
        assertEquals("A", attack.targetId());

        // Confirm stability over several more ticks
        for (int i = 0; i < 5; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "post-resume tick " + i);
            assertFalse(m.missionSucceeded());
        }
    }

    /**
     * R2 / A-decision executable spec: target beyond scan radius (16 >
     * 14) is absent from findTarget → grace → 11th tick TARGET_ESCAPED
     * failure. The bot cannot distinguish death from escape (no death
     * flag on EntitySnapshot), so the conservative failure verdict is
     * reported; the harness re-scans to confirm. Resolves issue 0006
     * (scan-radius / leash gap): a target that leaves the scan area no
     * longer produces a false SUCCESS that reads as "neutralized".
     */
    @Test
    void targetBeyondScanRadiusFiresEscaped() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        // A moves to 16 — beyond engaged scan radius (14)
        world.removeEntity("A");
        world.addEntity(zombie("A", 16));

        for (int i = 0; i < 10; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "grace tick " + i);
        }
        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals(DefendProcess.REASON_ESCAPED, m.failureReasonOrNull());
    }

    /**
     * R2 / R4: target beyond leash (13 > 12) but within scan (14) is
     * found by findTarget → leash check → fail(REASON_LOST). This also
     * pins that the leash check is not gated on nearest-hostile identity
     * (the old blind spot: a closer hostile at 7 would have shielded A
     * at 13 from the leash break).
     */
    @Test
    void targetBeyondLeashFailsLost() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        // A at 13: beyond leash(12), within scan(14). B at 7 is nearer.
        world.removeEntity("A");
        world.addEntity(zombie("A", 13));
        world.addEntity(zombie("B", 7));

        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals("LOST_TARGET", m.failureReasonOrNull());
    }

    /**
     * Non-engaged branch: a ranged hostile within ENGAGE_RADIUS is
     * REFUSED immediately (melee-only cannot answer kite-and-shoot).
     * Pins that REFUSED fires before engage. Ranged hostiles between
     * ENGAGE_RADIUS (8) and DETECTION_RADIUS (16) are also REFUSED —
     * see rangedHostileBeyondEngageWithinDetectionIsRefused.
     */
    @Test
    void rangedHostileWithinEngageRadiusIsRefused() {
        MockWorldView world = new MockWorldView();
        // 6-arg constructor: skeleton is in hostileTypes (visible to
        // scan) AND rangedTypes (refused rather than engaged).
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE, SKELETON), Set.of(SKELETON));

        world.addEntity(new EntitySnapshot("S1", SKELETON, new CellPos(6, 64, 0), 20f, 20f));

        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals("ENGAGEMENT_REFUSED", m.failureReasonOrNull());
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
        com.mcbot.mcbotserver.api.goal.GoalNear goal = (com.mcbot.mcbotserver.api.goal.GoalNear) directive.goal();
        assertEquals(DefendProcess.RANGED_STANDOFF, goal.range(), "the chase holds the standoff rim");
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
     * Non-engaged branch: a ranged hostile between ENGAGE_RADIUS (8)
     * and DETECTION_RADIUS (16) is seen by the wider detection scan
     * and REFUSED, rather than missed and reported as SUCCESS ("area
     * clear"). This is the fix for the non-engaged sister of issue
     * 0006: a skeleton kiting at 10 blocks can shoot the bot but the
     * bot cannot reach it — refusing is the honest verdict.
     */
    @Test
    void rangedHostileBeyondEngageWithinDetectionIsRefused() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = new DefendProcess(
                "t1", PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT, Set.of(ZOMBIE, SKELETON), Set.of(SKELETON));

        // Skeleton at 10: beyond ENGAGE_RADIUS (8), inside DETECTION_RADIUS (16).
        world.addEntity(new EntitySnapshot("S1", SKELETON, new CellPos(10, 64, 0), 20f, 20f));

        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals("ENGAGEMENT_REFUSED", m.failureReasonOrNull());
        assertEquals(SKELETON, m.verdictAttrs().get("threatType"));
    }
}
