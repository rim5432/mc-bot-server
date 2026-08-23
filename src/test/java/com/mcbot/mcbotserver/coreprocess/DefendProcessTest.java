package com.mcbot.mcbotserver.coreprocess;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        new InterruptionContext(1L, BOT, "defend:t1",
            "reflex-preempt:test", "");

    private DefendProcess mission() {
        return new DefendProcess("t1", PriorityBands.DEFEND_PRIORITY,
            1000L, () -> BOT, HOSTILES);
    }

    private EntitySnapshot zombie(String id, int x) {
        return new EntitySnapshot(id, ZOMBIE,
            new CellPos(x, 64, 0), 20f, 20f);
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
            assertTrue(m.isActive(),
                "tick " + i + ": A must stay engaged despite B at 7");
            assertFalse(m.missionSucceeded(),
                "tick " + i + ": must not report SUCCESS for live A");
            // R1: the directive must target A, not B — goal switching
            // is observable through the Attack override's targetId.
            Attack attack = (Attack) d.overrides().combat();
            assertEquals("A", attack.targetId(),
                "tick " + i + ": directive must attack A, not B");
        }
    }

    /**
     * When the engaged target is genuinely removed (death / despawn),
     * grace counts from 1 to 10 (still active), and the 11th tick
     * trips ticksSinceSeen > TARGET_GRACE_TICKS → succeed().
     */
    @Test
    void genuineAbsenceFiresSuccessAfterGrace() {
        MockWorldView world = new MockWorldView();
        DefendProcess m = mission();

        world.addEntity(zombie("A", 6));
        m.onTick(world);
        assertTrue(m.isActive());

        world.removeEntity("A");

        for (int i = 0; i < 10; i++) {
            m.onTick(world);
            assertTrue(m.isActive(),
                "grace tick " + i + " (ticksSinceSeen=" + (i + 1)
                    + ") must still be active");
        }
        m.onTick(world);
        assertFalse(m.isActive(), "11th tick must retire the mission");
        assertTrue(m.missionSucceeded());
        assertNull(m.failureReasonOrNull());
    }

    /**
     * C: pins the process-layer contract that health=0 snapshots are
     * valid engagement targets. BindingWorldView does not filter by
     * health (verified at adapter layer), so a dying entity in its
     * ~20-tick death animation is still returned by getEntities and
     * matched by id. SUCCESS is delayed until actual removal, not until
     * health hits zero. The sensor-side no-filter contract requires an
     * adapter-layer test; this test pins only the process behavior.
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
        world.addEntity(new EntitySnapshot("A", ZOMBIE,
            new CellPos(6, 64, 0), 0f, 20f));

        for (int i = 0; i < 15; i++) {
            m.onTick(world);
            assertTrue(m.isActive(),
                "tick " + i + ": health=0 entity still matched by id");
            assertFalse(m.missionSucceeded());
        }

        // Actual removal → grace → SUCCESS
        world.removeEntity("A");
        for (int i = 0; i < 10; i++) {
            m.onTick(world);
            assertTrue(m.isActive(), "grace tick " + i);
        }
        m.onTick(world);
        assertFalse(m.isActive());
        assertTrue(m.missionSucceeded());
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
        assertTrue(m.isActive(),
            "first post-resume tick must keep fighting when A is present");
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
     * 14) is absent from findTarget → grace → 11th tick SUCCESS. This
     * pins the 14/12 gap semantics documented in the class Javadoc and
     * tracked in issue 0006; when that issue resolves (raise radius or
     * add ESCAPED terminal), this test must flip and force explicit
     * acknowledgment.
     */
    @Test
    void targetBeyondScanRadiusFiresSuccess() {
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
        assertTrue(m.missionSucceeded());
        assertNull(m.failureReasonOrNull());
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
     * Pins that the non-engaged scan radius is ENGAGE_RADIUS=8 (the
     * ternary preserved from old nearestHostile) and that REFUSED fires
     * before engage. A ranged type beyond 8 blocks yields immediate
     * SUCCESS (nothing in engage range), not REFUSED — see class Javadoc.
     */
    @Test
    void rangedHostileWithinEngageRadiusIsRefused() {
        MockWorldView world = new MockWorldView();
        // 6-arg constructor: skeleton is in hostileTypes (visible to
        // scan) AND rangedTypes (refused rather than engaged).
        DefendProcess m = new DefendProcess("t1",
            PriorityBands.DEFEND_PRIORITY, 1000L, () -> BOT,
            Set.of(ZOMBIE, SKELETON),
            Set.of(SKELETON));

        world.addEntity(new EntitySnapshot("S1", SKELETON,
            new CellPos(6, 64, 0), 20f, 20f));

        m.onTick(world);
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals("ENGAGEMENT_REFUSED", m.failureReasonOrNull());
    }
}
