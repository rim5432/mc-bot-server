package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline gates for {@link DigProcess} (issue 0013 R1): completion is
 * a world read (target turns to air), the budget is a tick counter,
 * and an air-or-unknown target fails honestly before any progress.
 */
class DigProcessTest {

    private static final CellPos TARGET = new CellPos(3, 61, 3);

    @Test
    void completesWhenTargetTurnsToAir() {
        MockWorldView world = new MockWorldView()
            .putBlock(new BlockSnapshot(TARGET, "minecraft:stone"));
        DigProcess dig = new DigProcess("t1", TARGET, 50, 100);
        dig.onTick(world);
        assertEquals("minecraft:stone", dig.initialBlockId());
        assertTrue(dig.isActive());
        world.putAir(TARGET);
        Directive directive = dig.onTick(world);
        assertTrue(dig.missionSucceeded());
        assertFalse(dig.isActive());
        assertNull(dig.failureReasonOrNull());
        // Terminal tick still answers a directive: onTick must never
        // return null while the process exists (BotProcess contract).
        assertEquals(new com.mcbot.mcbotserver.api.goal.GoalNear(
            TARGET, 3), directive.goal());
    }

    @Test
    void airTargetFailsImmediatelyWithHonestReason() {
        MockWorldView world = new MockWorldView().putAir(TARGET);
        DigProcess dig = new DigProcess("t2", TARGET, 50, 100);
        dig.onTick(world);
        assertFalse(dig.isActive());
        assertFalse(dig.missionSucceeded());
        assertEquals("target is air", dig.failureReasonOrNull());
    }

    @Test
    void unloadedChunkFailsImmediately() {
        MockWorldView world = new MockWorldView()
            .markUnloaded(TARGET);
        DigProcess dig = new DigProcess("t3", TARGET, 50, 100);
        dig.onTick(world);
        assertEquals("chunk not loaded", dig.failureReasonOrNull());
    }

    @Test
    void budgetExpiryFailsWithTimeout() {
        MockWorldView world = new MockWorldView()
            .putBlock(new BlockSnapshot(TARGET, "minecraft:stone"));
        DigProcess dig = new DigProcess("t4", TARGET, 50, 10);
        for (int i = 0; i < 10 && dig.isActive(); i++) {
            dig.onTick(world);
        }
        assertFalse(dig.isActive());
        assertEquals("TIMEOUT", dig.failureReasonOrNull());
    }

    @Test
    void malformedConstructionRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new DigProcess("", TARGET, 50, 100));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new DigProcess("t5", TARGET, 50, 0));
    }
}
