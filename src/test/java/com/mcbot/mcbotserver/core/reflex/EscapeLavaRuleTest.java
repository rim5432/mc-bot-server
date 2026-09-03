package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Lava-escape and fire-extinguish reflex gates: the inLethalFluid
 * and fireTicks signals, the ESCAPE action kind, priority
 * arbitration (lava is 4 HP/tick — the fastest killer — and must
 * outrank everything; fire find-water slots between SURFACE and
 * FREEZE), and the hold windows that clear the threat edge after
 * exit.
 *
 * <p>Contract: see ADR-0003 section 2 and issue 0008 D2/D5-revised.
 * ESCAPE replaces the earlier ASCEND_IN_LETHAL_FLUID (pure held
 * jump): the reflex submits a rescue GotoProcess to the nearest
 * shore / water cell, and the pathing behavior handles all
 * movement. Numeric grounding: lava 4.0F/tick (Entity.lavaHurt);
 * fire 1.0F per 20 ticks, total remaining = fireTicks / 20
 * (integer division, decompiled 1.20.1 Entity.baseTick:483).
 */
class EscapeLavaRuleTest {

    private static final WorldView WORLD = new MockWorldView();

    private static ThreatBlackboard boardAt(boolean inLava) {
        var board = new ThreatBlackboard();
        board.beginTick(20f);
        board.inLethalFluid = inLava;
        return board;
    }

    private static ThreatBlackboard boardAtFire(int fireTicks, float health) {
        var board = new ThreatBlackboard();
        board.beginTick(health);
        board.fireTicks = fireTicks;
        return board;
    }

    // ---- EscapeLavaRule ----

    @Test
    void firesInLavaAndSilentOnDryLand() {
        EscapeLavaRule rule = new EscapeLavaRule();
        assertEquals(EscapeLavaRule.LAVA_ESCAPE_PRIORITY, rule.computePriority(boardAt(true)), "in lava fires");
        assertEquals(-1, rule.computePriority(boardAt(false)), "dry land stays silent");
    }

    @Test
    void actionIsEscapeNotAscend() {
        assertEquals(ReflexAction.ESCAPE, new EscapeLavaRule().action());
    }

    @Test
    void unSensedLavaDefaultsToFalseAndNeverFires() {
        ThreatBlackboard board = new ThreatBlackboard();
        board.beginTick(20f);
        assertEquals(false, board.inLethalFluid, "beginTick resets inLethalFluid to false");
        assertEquals(
                -1, new EscapeLavaRule().computePriority(board), "a rig whose sensor never stamps lava must not flap");
    }

    @Test
    void badPriorityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EscapeLavaRule(0));
    }

    /**
     * The load-bearing arbitration: lava + low air + low health must
     * pick lava escape — 4 HP/tick does not negotiate. The ESCAPE
     * handoff submits a rescue mission; the pathing behavior handles
     * ascent + shore reach.
     */
    @Test
    void lavaOutranksSurfaceAndFreezeWhenAllFire() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
            board.inLethalFluid = true;
            board.airSupply = 50;
        });
        layer.addRule(new FreezeOnLowHealthRule());
        layer.addRule(new SurfaceOnLowAirRule());
        layer.addRule(new EscapeLavaRule());
        var decision = layer.tick(WORLD, 4f);
        assertNotNull(decision);
        assertEquals("ESCAPE_ON_LAVA", decision.ruleName());
        assertEquals(ReflexAction.ESCAPE, decision.action());
    }

    @Test
    void holdSurvivesLavaExitThenReleases() {
        boolean[] inLava = {true};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.inLethalFluid = inLava[0]);
        layer.addRule(new EscapeLavaRule());
        assertNotNull(layer.tick(WORLD, 20f), "in lava fires");
        inLava[0] = false;
        for (int i = 0; i < EscapeLavaRule.LAVA_ESCAPE_HOLD_TICKS; i++) {
            assertNotNull(layer.tick(WORLD, 20f), "hold tick " + i + " keeps escape to clear edge");
        }
        assertNull(layer.tick(WORLD, 20f), "hold expired + stable dry land releases");
    }

    // ---- ExtinguishFireRule ----

    @Test
    void firesWhenRemainingDamageExceedsHealth() {
        ExtinguishFireRule rule = new ExtinguishFireRule();
        // fireTicks=400 -> 20 damage remaining, health=10 -> lethal
        assertEquals(
                ExtinguishFireRule.FIRE_EXTINGUISH_PRIORITY,
                rule.computePriority(boardAtFire(400, 10f)),
                "20 remaining damage vs 10 health fires");
        // fireTicks=20 -> 1 damage remaining, health=10 -> safe
        assertEquals(-1, rule.computePriority(boardAtFire(20, 10f)), "1 remaining damage vs 10 health stays silent");
    }

    @Test
    void zeroFireTicksNeverFires() {
        assertEquals(
                -1,
                new ExtinguishFireRule().computePriority(boardAtFire(0, 1f)),
                "not burning stays silent even at 1 health");
    }

    @Test
    void actionIsEscape() {
        assertEquals(ReflexAction.ESCAPE, new ExtinguishFireRule().action());
    }

    @Test
    void fireEscapeOutranksFreezeButNotSurface() {
        // fireTicks=400 (20 dmg), health=10 -> lethal fire
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> {
            board.fireTicks = 400;
            board.airSupply = 300; // not drowning
        });
        layer.addRule(new FreezeOnLowHealthRule()); // 100
        layer.addRule(new SurfaceOnLowAirRule()); // 110, won't fire
        layer.addRule(new ExtinguishFireRule()); // 105
        var decision = layer.tick(WORLD, 10f);
        assertNotNull(decision);
        assertEquals("EXTINGUISH_FIRE", decision.ruleName(), "fire escape (105) outranks freeze (100)");
    }

    @Test
    void badFirePriorityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExtinguishFireRule(0));
    }
}
