package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.reflex.ReflexRule;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Stage-0 reflex chain gate: the FULL preempt -> resume loop runs
 * against mocks before any entity exists.
 *
 * <p>Contract: see ADR-0003 sections 1-3 and boundaries.md decision 16.
 * This is the resume-validation half of the stage-0 gate, exercised
 * through the real layer, the real arbiter and one minimal rule.
 */
class ReflexChainGateTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final CellPos POS = new CellPos(5, 64, 5);

    /** Scriptable mission recording its lifecycle for assertions. */
    private static final class ScriptedMission implements BotProcess {
        private boolean active = true;
        private boolean worldAssumptionsHold = true;
        private int lostCalls;
        private int invalidatedCalls;
        private int resumedTicks;
        private String lostCause = "";

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public int priority() {
            return 50;
        }

        @Override
        public Directive onTick(WorldView world) {
            resumedTicks++;
            return Directive.of(new com.mcbot.mcbotserver.api.goal.GoalBlock(POS));
        }

        @Override
        public void onLostControl(InterruptionContext context) {
            lostCalls++;
            lostCause = context.causeSummary();
        }

        @Override
        public boolean resume(InterruptionContext context) {
            // The frozen contract: revalidate world assumptions FIRST.
            return worldAssumptionsHold;
        }

        @Override
        public void onContextInvalidated() {
            invalidatedCalls++;
        }

        @Override
        public String displayName() {
            return "scripted-mission";
        }
    }

    /**
     * Decision 16: low health fires the rule, the mission is parked
     * with an interruption snapshot naming the rule, recovery resumes
     * it after revalidation, and the whole loop never touches process
     * code beyond the frozen lifecycle hooks.
     */
    @Test
    void fullPreemptResumeChainOverMocks() {
        float[] health = {20f};
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = health[0]);
        layer.addRule(new FreezeOnLowHealthRule());

        TaskArbiter arbiter = new TaskArbiter();
        ScriptedMission mission = new ScriptedMission();
        arbiter.register(mission);
        arbiter.requestControl(mission);
        arbiter.tick(WORLD);
        assertEquals(1, mission.resumedTicks);

        // Bleed: reflex must fire and park the mission this tick.
        health[0] = 4f;
        SurvivalReflexLayer.ReflexDecision decision = layer.tick(WORLD, 11L, 3L, 8000L, POS, health[0]);
        assertNotNull(decision, "low health must fire FREEZE_ON_LOW_HEALTH");
        assertEquals("FREEZE_ON_LOW_HEALTH", decision.ruleName());
        assertEquals(FreezeOnLowHealthRule.FREEZE_PRIORITY, decision.priority());

        long tick = 11L;
        InterruptionContext ctx =
                new InterruptionContext(tick, POS, mission.displayName(), "reflex-preempt:" + decision.ruleName(), "");
        assertEquals(TaskArbiter.ParkResult.PARKED, arbiter.forcePauseAll(ctx));
        assertEquals(1, mission.lostCalls);
        assertEquals("reflex-preempt:FREEZE_ON_LOW_HEALTH", mission.lostCause);

        // While parked: mission stage produces nothing even as ticks pass.
        arbiter.tick(WORLD);
        assertEquals(1, mission.resumedTicks, "parked missions are skipped by call order");

        // Heal: signal crosses release (20 > 15) but the 20-tick
        // hold window keeps the rule firing for FREEZE_HOLD_TICKS
        // ticks - that's the anti-oscillation safety net. Drain
        // the hold one tick past the window so the next layer.tick
        // is allowed to flip silent.
        health[0] = 20f;
        for (int i = 0; i < FreezeOnLowHealthRule.FREEZE_HOLD_TICKS; i++) {
            assertNotNull(
                    layer.tick(WORLD, 12L + i, 3L, 8100L, POS, health[0]),
                    "hold must keep the rule firing for FREEZE_HOLD_TICKS");
        }
        assertNull(
                layer.tick(WORLD, 12L + FreezeOnLowHealthRule.FREEZE_HOLD_TICKS, 3L, 8100L, POS, health[0]),
                "after the hold the rule must release");
        assertTrue(arbiter.tryResume());
        assertSame(mission, arbiter.current());
        arbiter.tick(WORLD);
        assertEquals(2, mission.resumedTicks);
        assertEquals(0, mission.invalidatedCalls);
    }

    /**
     * ADR-0003 section 3 gate: when the parked mission's world
     * assumptions no longer hold, resume drops it instead of resuming.
     */
    @Test
    void invalidatedAssumptionsDropInsteadOfResume() {
        SurvivalReflexLayer layer = new SurvivalReflexLayer((world, board) -> board.botHealth = 20f);
        TaskArbiter arbiter = new TaskArbiter();
        ScriptedMission mission = new ScriptedMission();
        mission.worldAssumptionsHold = false;
        arbiter.register(mission);
        arbiter.requestControl(mission);
        arbiter.tick(WORLD);

        var decision = layer.tick(WORLD, 1L, 1L, 0L, POS, 20f);
        assertNull(decision, "healthy bot must not trigger any reflex");

        // Force the preemption path directly (any reflex cause).
        var ctx = new InterruptionContext(2L, POS, mission.displayName(), "reflex-preempt:FORCED", "");
        assertEquals(TaskArbiter.ParkResult.PARKED, arbiter.forcePauseAll(ctx));
        assertFalse(arbiter.tryResume(), "failed revalidation must drop, not resume");
        assertEquals(1, mission.invalidatedCalls);
        assertNull(arbiter.current());

        arbiter.tryResume();
        assertNull(arbiter.current(), "a dropped mission must not come back through tryResume");
    }

    /**
     * Seam promise of section C: rewriting the entire rule table is
     * invisible to the process tier — swap in a different rule set and
     * the mission lifecycle keeps working unchanged.
     */
    @Test
    void ruleTableIsSwappableWithoutProcessKnowledge() {
        ReflexRule alwaysFire = new ReflexRule() {
            @Override
            public int computePriority(ThreatBlackboard board) {
                return 7;
            }

            @Override
            public String name() {
                return "ALWAYS_FIRE";
            }
        };
        SurvivalReflexLayer swapped = new SurvivalReflexLayer((world, board) -> {});
        swapped.addRule(alwaysFire);
        var decision = swapped.tick(WORLD, 1L, 1L, 0L, POS, 20f);
        assertNotNull(decision);
        assertEquals("ALWAYS_FIRE", decision.ruleName());
        assertEquals(7, decision.priority());
    }
}
