package com.mcbot.mcbotserver.api.actor;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Direction;
import com.mcbot.mcbotserver.api.types.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline gates for the Phase 1 InteractBlock intent and the api-layer
 * Direction enum: record shape, null validation, channel-claim
 * validation (INTERACT accepts InteractBlock, other channels reject it),
 * Direction unit vectors and relative-cell arithmetic, and coexistence
 * with Dig / DropSelected on the same INTERACT channel.
 *
 * <p>Contract: see boundaries.md section A (Actor is intents-only, api
 * purity) and issue 0007 Phase 1. The in-engine placement (level.setBlock
 * + item shrink) is pinned by the dropsSelectedItem-style gametest; this
 * gate covers the pure-Java surface that must never import MC types.
 */
class InteractBlockIntentTest {

    private static final CellPos TARGET = new CellPos(3, 64, 5);
    private static final Vec3 HIT = new Vec3(3.5, 65.0, 5.5);

    // ---- Direction enum ----

    @Test
    void directionHasSixValuesInMcOrder() {
        Direction[] values = Direction.values();
        assertEquals(6, values.length);
        assertEquals(Direction.DOWN, values[0]);
        assertEquals(Direction.UP, values[1]);
        assertEquals(Direction.NORTH, values[2]);
        assertEquals(Direction.SOUTH, values[3]);
        assertEquals(Direction.WEST, values[4]);
        assertEquals(Direction.EAST, values[5]);
    }

    @Test
    void directionUnitVectorsAreCorrect() {
        assertEquals(0, Direction.DOWN.dx());
        assertEquals(-1, Direction.DOWN.dy());
        assertEquals(0, Direction.DOWN.dz());
        assertEquals(0, Direction.UP.dx());
        assertEquals(1, Direction.UP.dy());
        assertEquals(0, Direction.UP.dz());
        assertEquals(0, Direction.NORTH.dx());
        assertEquals(0, Direction.NORTH.dy());
        assertEquals(-1, Direction.NORTH.dz());
        assertEquals(0, Direction.SOUTH.dx());
        assertEquals(0, Direction.SOUTH.dy());
        assertEquals(1, Direction.SOUTH.dz());
        assertEquals(-1, Direction.WEST.dx());
        assertEquals(0, Direction.WEST.dy());
        assertEquals(0, Direction.WEST.dz());
        assertEquals(1, Direction.EAST.dx());
        assertEquals(0, Direction.EAST.dy());
        assertEquals(0, Direction.EAST.dz());
    }

    @Test
    void directionRelativeComputesAdjacentCell() {
        CellPos origin = new CellPos(10, 20, 30);
        assertEquals(new CellPos(10, 19, 30), Direction.DOWN.relative(origin));
        assertEquals(new CellPos(10, 21, 30), Direction.UP.relative(origin));
        assertEquals(new CellPos(10, 20, 29), Direction.NORTH.relative(origin));
        assertEquals(new CellPos(10, 20, 31), Direction.SOUTH.relative(origin));
        assertEquals(new CellPos(9, 20, 30), Direction.WEST.relative(origin));
        assertEquals(new CellPos(11, 20, 30), Direction.EAST.relative(origin));
    }

    // ---- Intent.InteractBlock record ----

    @Test
    void interactBlockRecordCarriesAllFields() {
        var intent = new Intent.InteractBlock(TARGET, Direction.UP, HIT);
        assertEquals(TARGET, intent.target());
        assertEquals(Direction.UP, intent.face());
        assertEquals(HIT, intent.hitPos());
    }

    @Test
    void interactBlockRejectsNullTarget() {
        assertThrows(IllegalArgumentException.class,
            () -> new Intent.InteractBlock(null, Direction.UP, HIT));
    }

    @Test
    void interactBlockRejectsNullFace() {
        assertThrows(IllegalArgumentException.class,
            () -> new Intent.InteractBlock(TARGET, null, HIT));
    }

    @Test
    void interactBlockRejectsNullHitPos() {
        assertThrows(IllegalArgumentException.class,
            () -> new Intent.InteractBlock(TARGET, Direction.UP, null));
    }

    // ---- Claim channel validation ----

    @Test
    void interactBlockIsValidOnInteractChannel() {
        var claim = new Claim(Channel.INTERACT, 100, "test",
            new Intent.InteractBlock(TARGET, Direction.UP, HIT));
        assertEquals(Channel.INTERACT, claim.channel());
        assertTrue(claim.intent() instanceof Intent.InteractBlock);
    }

    @Test
    void interactBlockRejectedOnMoveChannel() {
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.MOVE, 100, "test",
                new Intent.InteractBlock(TARGET, Direction.UP, HIT)));
    }

    @Test
    void interactBlockRejectedOnRotChannel() {
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.ROT, 100, "test",
                new Intent.InteractBlock(TARGET, Direction.UP, HIT)));
    }

    @Test
    void interactBlockRejectedOnUseChannel() {
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.USE, 100, "test",
                new Intent.InteractBlock(TARGET, Direction.UP, HIT)));
    }

    @Test
    void interactBlockRejectedOnSlotChannel() {
        assertThrows(IllegalArgumentException.class,
            () -> new Claim(Channel.SLOT, 100, "test",
                new Intent.InteractBlock(TARGET, Direction.UP, HIT)));
    }

    // ---- INTERACT channel coexistence ----

    @Test
    void allThreeInteractVariantsAreValidOnInteractChannel() {
        // Dig, DropSelected, and InteractBlock all ride INTERACT; the
        // per-tick arbiter picks one winner per tick. This gate only
        // proves the claim-construction gate accepts all three; the
        // arbitration itself is ChannelArbiter's job.
        new Claim(Channel.INTERACT, 100, "dig",
            new Intent.Dig(TARGET));
        new Claim(Channel.INTERACT, 100, "drop",
            new Intent.DropSelected(true));
        new Claim(Channel.INTERACT, 100, "place",
            new Intent.InteractBlock(TARGET, Direction.UP, HIT));
    }
}
