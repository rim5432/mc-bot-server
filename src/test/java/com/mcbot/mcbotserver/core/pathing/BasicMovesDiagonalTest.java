package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Diagonal corner-clearance gate. The 1x1 grid point body is
 * approximated as a 0.6x0.6 hitbox, and the player's full height
 * is 1.8 - so a diagonal move sweeps both the foot and the head
 * level at each corner cell. A purely foot-level check would let
 * the head clip a 1-cell obstruction directly above a foot-clear
 * corner.
 *
 * <p>Contract: see Stage 1 review item C (Diagonal corner check).
 * Pre-fix: only edgeA (foot) and edgeB (foot) were checked, plus
 * the destination's standability. Post-fix: edgeAhead and
 * edgeBhead close the head-level hole.
 */
class BasicMovesDiagonalTest {

    private static final int FLOOR_Y = 63;
    private static final int BODY_Y = 64;

    /**
     * Flat ground for one diagonal hop: floor at y=FLOOR_Y, air
     * everywhere from BODY_Y upward. Caller adds obstructions.
     */
    private static MockWorldView flatGround() {
        MockWorldView world = new MockWorldView();
        for (int x = -2; x <= 4; x++) {
            for (int z = -2; z <= 4; z++) {
                world.putBlock(new BlockSnapshot(new CellPos(x, FLOOR_Y, z), "minecraft:smooth_stone"));
            }
        }
        return world;
    }

    private static BasicMoves.Diagonal diag(int x, int z) {
        return new BasicMoves.Diagonal(new CellPos(0, BODY_Y, 0), new CellPos(x, BODY_Y, z));
    }

    /** Clean air all around: the diagonal is viable. */
    @Test
    void cleanAirAllowsDiagonal() {
        MockWorldView world = flatGround();
        assertTrue(diag(1, 1).isViable(world), "no obstructions: diag must be viable");
        assertTrue(diag(1, -1).isViable(world));
        assertTrue(diag(-1, 1).isViable(world));
        assertTrue(diag(-1, -1).isViable(world));
    }

    /** Foot-level corner A is blocked: the diagonal is not viable. */
    @Test
    void blockedFootCornerARejectsDiagonal() {
        MockWorldView world = flatGround();
        world.putBlock(new BlockSnapshot(new CellPos(1, BODY_Y, 0), "minecraft:stone"));
        assertFalse(diag(1, 1).isViable(world), "wall at foot corner A must block the diagonal");
    }

    /** Foot-level corner B is blocked: the diagonal is not viable. */
    @Test
    void blockedFootCornerBRejectsDiagonal() {
        MockWorldView world = flatGround();
        world.putBlock(new BlockSnapshot(new CellPos(0, BODY_Y, 1), "minecraft:stone"));
        assertFalse(diag(1, 1).isViable(world), "wall at foot corner B must block the diagonal");
    }

    /**
     * Head-level corner A is blocked, foot-level clear: pre-fix
     * this would have falsely returned true. The post-fix check
     * catches it.
     */
    @Test
    void blockedHeadCornerARejectsDiagonal() {
        MockWorldView world = flatGround();
        world.putBlock(new BlockSnapshot(new CellPos(1, BODY_Y + 1, 0), "minecraft:stone"));
        assertFalse(diag(1, 1).isViable(world), "wall at head corner A must block the diagonal");
    }

    /**
     * Head-level corner B is blocked, foot-level clear: same as
     * above, mirrored across the diagonal axis.
     */
    @Test
    void blockedHeadCornerBRejectsDiagonal() {
        MockWorldView world = flatGround();
        world.putBlock(new BlockSnapshot(new CellPos(0, BODY_Y + 1, 1), "minecraft:stone"));
        assertFalse(diag(1, 1).isViable(world), "wall at head corner B must block the diagonal");
    }

    /**
     * Destination's head space is blocked: standable() already
     * covers this, but the gate pins it explicitly so a future
     * refactor cannot silently drop the head check from
     * standable.
     */
    @Test
    void destinationHeadSpaceBlockStillRejects() {
        MockWorldView world = flatGround();
        world.putBlock(new BlockSnapshot(new CellPos(1, BODY_Y + 1, 1), "minecraft:stone"));
        assertFalse(diag(1, 1).isViable(world), "wall at destination head must block the diagonal");
    }
}
