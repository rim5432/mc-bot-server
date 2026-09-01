package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.inventory.ItemView;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TameProcess directed-taming contract: the HANDED animal is fed
 * until the scan reports it tamed (the directly-judged verdict, the
 * corpse-sighting shape of attack), a never-seen id fails fast, the
 * first sighting settles species / ownership / loadout with typed
 * refusals, and leash, timeout, and cancel behave as in defend and
 * attack.
 *
 * <p>Contract: see boundaries.md decision 10/11.
 */
class TameProcessTest {

    private static final CellPos BOT = new CellPos(0, 64, 0);

    private static final String WOLF = "minecraft:wolf";

    private static final String BONE = "minecraft:bone";

    private static final InterruptionContext CTX =
            new InterruptionContext(1L, BOT, "tame:t1", "reflex-preempt:test", "");

    private TameProcess mission() {
        return new TameProcess("t1", "wolf-1", 50, 1000L, () -> BOT);
    }

    private EntitySnapshot wolf(String id, int x) {
        return new EntitySnapshot(id, WOLF, new CellPos(x, 64, 0), 10f, 10f);
    }

    private EntitySnapshot wolf(String id, int x, boolean tamed) {
        return new EntitySnapshot(id, WOLF, new CellPos(x, 64, 0), 10f, 10f, tamed);
    }

    private EntitySnapshot angryWolf(String id, int x) {
        return new EntitySnapshot(id, WOLF, new CellPos(x, 64, 0), 10f, 10f, false, true);
    }

    private MockWorldView worldWithBone() {
        MockWorldView world = new MockWorldView();
        world.setInventory(inventoryWith(BONE));
        return world;
    }

    private static InventoryView inventoryWith(String heldId) {
        List<ItemView> main = new ArrayList<>(Collections.nCopies(InventoryView.MAIN_SIZE, ItemView.EMPTY));
        main.set(0, new ItemView(heldId, 1));
        return new InventoryView(
                main, 0, List.copyOf(Collections.nCopies(InventoryView.ARMOR_SIZE, ItemView.EMPTY)), ItemView.EMPTY);
    }

    /**
     * The chase directive carries the tame order with the sighted
     * species, and the tamed flip on a later sighting IS the
     * completion verdict - attack's corpse-sighting shape, inverted.
     */
    @Test
    void completesOnTamedSighting() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));

        Directive chase = m.onTick(world);
        assertTrue(chase.overrides().tame() != null, "the tame order rides the directive");
        assertEquals("wolf-1", chase.overrides().tame().targetId(), "the order names the handed id");
        assertEquals(WOLF, chase.overrides().tame().typeKey(), "the order carries the sighted species");
        assertNull(chase.overrides().combat(), "taming is not fighting");
        GoalNear goal = (GoalNear) chase.goal();
        assertEquals(2, goal.range(), "the chase parks at the use-on-entity rim");
        assertFalse(m.missionSucceeded(), "untamed sighting keeps the mission running");

        world.removeEntity("wolf-1");
        world.addEntity(wolf("wolf-1", 6, true));
        m.onTick(world);

        assertTrue(m.missionSucceeded(), "the tamed sighting completes the mission");
        assertNull(m.failureReasonOrNull(), "a completed mission carries no failure reason");
        assertEquals("wolf-1", m.verdictAttrs().get("targetId"), "the verdict names the animal");
    }

    /** A never-seen id is refused now, not budget-stared. */
    @Test
    void neverSeenIdFailsFast() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_NO_TARGET, m.failureReasonOrNull());
    }

    /**
     * The first sighting settles the species: a non-tameable animal
     * can never land the task, so the verdict is immediate.
     */
    @Test
    void nonTameableSpeciesFailsFast() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(new EntitySnapshot("wolf-1", "minecraft:zombie", new CellPos(6, 64, 0), 20f, 20f));

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_NOT_TAMEABLE, m.failureReasonOrNull());
    }

    /** An animal that already carries an owner cannot be re-tamed. */
    @Test
    void alreadyTamedAtFirstSightFailsFast() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6, true));

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_ALREADY_TAMED, m.failureReasonOrNull());
    }

    /**
     * An angry wolf's mobInteract falls through the bone branch
     * (Wolf.java:377 guards on !isAngry()), so every press is a silent
     * no-op - the process refuses with a typed reason instead of
     * budget-staring.
     */
    @Test
    void angryWolfFailsFast() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(angryWolf("wolf-1", 6));

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_TARGET_ANGRY, m.failureReasonOrNull());
    }

    /**
     * Without the species' tame item in the hotbar the presses can
     * never mean anything - the loadout refusal is typed, mirroring
     * the NO_SUCH_ENTITY honesty.
     */
    @Test
    void missingTameItemFailsFast() {
        MockWorldView world = new MockWorldView();
        world.setInventory(inventoryWith("minecraft:stick"));
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_NO_TAME_ITEM, m.failureReasonOrNull());
    }

    /** An engaged target crossing the shared leash radius fails. */
    @Test
    void leashBreachFails() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));
        m.onTick(world);

        world.removeEntity("wolf-1");
        world.addEntity(wolf("wolf-1", 14));
        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_LOST, m.failureReasonOrNull());
    }

    /** Absence past the shared grace window is the conservative escape. */
    @Test
    void absencePastGraceEscapes() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));
        m.onTick(world);
        world.removeEntity("wolf-1");

        for (int i = 0; i < TameProcess.TARGET_GRACE_TICKS; i++) {
            m.onTick(world);
        }
        assertNull(m.failureReasonOrNull(), "grace still holds inside the window");

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_ESCAPED, m.failureReasonOrNull());
    }

    /** The tick budget owns the verdict when nothing else does. */
    @Test
    void budgetExpiryFails() {
        MockWorldView world = worldWithBone();
        TameProcess m = new TameProcess("t1", "wolf-1", 50, 5L, () -> BOT);
        world.addEntity(wolf("wolf-1", 6));

        for (int i = 0; i < 6; i++) {
            m.onTick(world);
        }

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_TIMEOUT, m.failureReasonOrNull());
    }

    /** Harness cancellation is sticky and later ticks cannot resurrect it. */
    @Test
    void cancelIsSticky() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));
        m.onTick(world);

        m.abort();

        assertFalse(m.missionSucceeded());
        assertEquals("CANCELLED", m.failureReasonOrNull());
        m.onTick(world);
        assertEquals("CANCELLED", m.failureReasonOrNull(), "a terminal verdict never overwrites");
    }

    /** Reflex preemption spends the verdict the same way attack does. */
    @Test
    void preemptionFailsTheMission() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));
        m.onTick(world);

        m.onContextInvalidated();

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_PREEMPTED, m.failureReasonOrNull());
    }

    /** A corpse sighting fails typed - the tame can never land on it. */
    @Test
    void corpseSightingFailsTyped() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(new EntitySnapshot("wolf-1", WOLF, new CellPos(6, 64, 0), 0f, 10f));

        m.onTick(world);

        assertFalse(m.missionSucceeded());
        assertEquals(TameProcess.REASON_TARGET_DEAD, m.failureReasonOrNull());
    }

    /** Resume spends all grace credit so the next scan adjudicates. */
    @Test
    void resumeSpendsTheGrace() {
        MockWorldView world = worldWithBone();
        TameProcess m = mission();
        world.addEntity(wolf("wolf-1", 6));
        m.onTick(world);
        world.removeEntity("wolf-1");

        assertTrue(m.resume(CTX), "a live mission resumes");

        m.onTick(world);
        assertEquals(TameProcess.REASON_ESCAPED, m.failureReasonOrNull(), "the spent grace decides immediately");
    }
}
