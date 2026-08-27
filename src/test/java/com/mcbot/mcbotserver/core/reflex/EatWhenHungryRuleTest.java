package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the eat reflex trigger (Stage 3 Phase 4 eat
 * slice, issue 0010 D1/D5/D6): fires at/below the trigger only when
 * the sensor found food, stays silent hungry-without-food (the
 * escalation contract), and the blackboard's safe defaults read as
 * well-fed with nothing to eat.
 */
class EatWhenHungryRuleTest {

    private static final EatWhenHungryRule RULE = new EatWhenHungryRule(16, 85);

    private static ThreatBlackboard board(int foodLevel, int foodSlot) {
        ThreatBlackboard board = new ThreatBlackboard();
        board.foodLevel = foodLevel;
        board.foodSlot = foodSlot;
        return board;
    }

    @Test
    void firesAtTriggerWhenFoodIsSensed() {
        assertEquals(85, RULE.computePriority(board(16, 3)));
        assertEquals(85, RULE.computePriority(board(2, 0)));
    }

    @Test
    void silentAboveTriggerEvenWithFood() {
        assertEquals(-1, RULE.computePriority(board(17, 3)));
        assertEquals(-1, RULE.computePriority(board(20, 3)));
    }

    @Test
    void hungryWithoutFoodNeverFires() {
        assertEquals(-1, RULE.computePriority(board(16, -1)));
        assertEquals(-1, RULE.computePriority(board(0, -1)));
    }

    @Test
    void freshBlackboardDefaultsReadWellFedAndFoodless() {
        assertEquals(-1, RULE.computePriority(new ThreatBlackboard()));
        assertEquals(20, new ThreatBlackboard().foodLevel);
        assertEquals(-1, new ThreatBlackboard().foodSlot);
    }

    @Test
    void namesTheEatAction() {
        assertEquals("EAT_WHEN_HUNGRY", RULE.name());
        assertEquals(ReflexAction.EAT, RULE.action());
    }

    @Test
    void rejectsBadTuning() {
        assertThrows(IllegalArgumentException.class, () -> new EatWhenHungryRule(0, 85));
        assertThrows(IllegalArgumentException.class, () -> new EatWhenHungryRule(21, 85));
        assertThrows(IllegalArgumentException.class, () -> new EatWhenHungryRule(16, 0));
    }
}
