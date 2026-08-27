package com.mcbot.mcbotserver.api.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Offline gates for the Phase 1 DropSelected intent: record shape,
 * channel-claim validation (INTERACT accepts DropSelected, other
 * channels reject it), and coexistence with Dig on the same channel.
 *
 * <p>Contract: see boundaries.md section A (Actor is intents-only)
 * and issue 0007 Phase 1. The sealed Intent vocabulary + the
 * Claim.matchesChannel gate are what make a misrouted intent fail at
 * construction time instead of at adapter dispatch.
 */
class DropSelectedIntentTest {

    private static final CellPos POS = new CellPos(0, 64, 0);

    @Test
    void dropSelectedRecordCarriesFullStackFlag() {
        var full = new Intent.DropSelected(true);
        var single = new Intent.DropSelected(false);
        assertTrue(full.fullStack());
        assertFalse(single.fullStack());
    }

    @Test
    void dropSelectedIsValidOnInteractChannel() {
        var claim = new Claim(Channel.INTERACT, 100, "test", new Intent.DropSelected(true));
        assertEquals(Channel.INTERACT, claim.channel());
        assertTrue(claim.intent() instanceof Intent.DropSelected);
    }

    @Test
    void digIsStillValidOnInteractChannel() {
        var claim = new Claim(Channel.INTERACT, 100, "test", new Intent.Dig(POS));
        assertTrue(claim.intent() instanceof Intent.Dig);
    }

    @Test
    void dropSelectedRejectedOnMoveChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Claim(Channel.MOVE, 100, "test", new Intent.DropSelected(true)));
    }

    @Test
    void dropSelectedRejectedOnRotChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Claim(Channel.ROT, 100, "test", new Intent.DropSelected(true)));
    }

    @Test
    void dropSelectedRejectedOnUseChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Claim(Channel.USE, 100, "test", new Intent.DropSelected(true)));
    }

    @Test
    void dropSelectedRejectedOnSlotChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Claim(Channel.SLOT, 100, "test", new Intent.DropSelected(true)));
    }

    @Test
    void nullIntentRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Claim(Channel.INTERACT, 100, "test", null));
    }

    @Test
    void blankHolderRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Claim(Channel.INTERACT, 100, "  ", new Intent.DropSelected(true)));
    }
}
