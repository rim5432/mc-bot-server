package com.mcbot.mcbotserver.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mcbot.mcbotserver.api.reflex.ReflexAction;
import com.mcbot.mcbotserver.api.reflex.ThreatBlackboard;
import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the MLG rule: fires only on the full conjunction -
 * descending over sensed ground with a water bucket in the hotbar -
 * aims at the sensed ground cell, and stays silent when any leg is
 * missing (a no-loadout firing would stall the mission stage for
 * nothing every falling tick).
 */
class WaterBucketOnFallRuleTest {

    private static final CellPos GROUND = new CellPos(3, 60, 3);

    @Test
    void firesOnTheFullConjunction() {
        WaterBucketOnFallRule rule = new WaterBucketOnFallRule();
        ThreatBlackboard board = new ThreatBlackboard();
        board.descending = true;
        board.groundCell = GROUND;
        board.waterBucketSlot = 4;

        assertEquals(120, rule.computePriority(board));
        assertEquals(GROUND, rule.actionTarget(board));
        assertEquals(ReflexAction.WATER_BUCKET, rule.action());
        assertEquals("WATER_BUCKET_ON_FALL", rule.name());
    }

    @Test
    void silentWithoutTheBucket() {
        WaterBucketOnFallRule rule = new WaterBucketOnFallRule();
        ThreatBlackboard board = new ThreatBlackboard();
        board.descending = true;
        board.groundCell = GROUND;
        board.waterBucketSlot = -1;

        assertEquals(-1, rule.computePriority(board));
    }

    @Test
    void silentWhenNotDescending() {
        WaterBucketOnFallRule rule = new WaterBucketOnFallRule();
        ThreatBlackboard board = new ThreatBlackboard();
        board.descending = false;
        board.groundCell = GROUND;
        board.waterBucketSlot = 4;

        assertEquals(-1, rule.computePriority(board));
    }

    @Test
    void silentOverAVoidDrop() {
        WaterBucketOnFallRule rule = new WaterBucketOnFallRule();
        ThreatBlackboard board = new ThreatBlackboard();
        board.descending = true;
        board.groundCell = null;
        board.waterBucketSlot = 4;

        assertEquals(-1, rule.computePriority(board));
    }
}
