package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the acquisition trigger (issue 0010): fires only
 * hungry-without-food - the exact complement of EAT_WHEN_HUNGRY -
 * and hands off the forage action.
 */
class AcquireFoodWhenHungryRuleTest {

    private static final AcquireFoodWhenHungryRule RULE = new AcquireFoodWhenHungryRule(16, 80);

    private static ThreatBlackboard board(int foodLevel, int foodSlot) {
        ThreatBlackboard board = new ThreatBlackboard();
        board.foodLevel = foodLevel;
        board.foodSlot = foodSlot;
        return board;
    }

    @Test
    void firesHungryWithoutFood() {
        assertEquals(80, RULE.computePriority(board(16, -1)));
        assertEquals(80, RULE.computePriority(board(0, -1)));
    }

    @Test
    void silentWhenFoodIsSensedOrFed() {
        assertEquals(-1, RULE.computePriority(board(16, 3)));
        assertEquals(-1, RULE.computePriority(board(20, -1)));
    }

    @Test
    void complementsTheEatRuleExactly() {
        EatWhenHungryRule eat = new EatWhenHungryRule();
        for (int food = 0; food <= 20; food++) {
            for (int slot : new int[] {-1, 3}) {
                ThreatBlackboard board = board(food, slot);
                boolean eatFires = eat.computePriority(board) > 0;
                boolean acquireFires = RULE.computePriority(board) > 0;
                assert !(eatFires && acquireFires) : "eat and acquire must be mutually exclusive at food=" + food;
                assert eatFires || acquireFires || food > 16 : "hungry must trigger exactly one at food=" + food;
            }
        }
    }

    @Test
    void namesTheForageAction() {
        assertEquals("ACQUIRE_FOOD_WHEN_HUNGRY", RULE.name());
        assertEquals(ReflexAction.FORAGE, RULE.action());
    }

    @Test
    void rejectsBadTuning() {
        assertThrows(IllegalArgumentException.class, () -> new AcquireFoodWhenHungryRule(0, 80));
        assertThrows(IllegalArgumentException.class, () -> new AcquireFoodWhenHungryRule(16, 0));
    }
}
