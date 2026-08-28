package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
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
    void preemptionInvalidationFailsTheMission() {
        AttackProcess m = mission();
        m.onContextInvalidated();
        assertFalse(m.isActive());
        assertEquals(AttackProcess.REASON_PREEMPTED, m.failureReasonOrNull());
    }
}
