package com.mcbot.mcbotserver.core.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Trait-driven climb gate: the ClimbUp move answers from the climbable
 * trait, never from the block id, and a ladder column with no trait
 * annotation must stay invisible to it (decision 19 + the 19a gate:
 * precondition derivable from Shape plus Traits).
 *
 * <p>Scenario: a floor at LADDER_Y, a ladder column rising three cells
 * above it. The ladder cells are collision-empty (passable) but never
 * standable — which is exactly why Walk/JumpUp cannot see them and
 * ClimbUp must.
 */
class BasicMovesClimbTest {

    private static final int LADDER_Y = 64;
    private static final CellPos FLOOR = new CellPos(0, LADDER_Y, 0);
    private static final CellPos LADDER_0 = new CellPos(0, LADDER_Y + 1, 0);
    private static final CellPos LADDER_1 = new CellPos(0, LADDER_Y + 2, 0);
    private static final CellPos LADDER_2 = new CellPos(0, LADDER_Y + 3, 0);
    private static final CellPos ABOVE_LADDER_2 = new CellPos(0, LADDER_Y + 4, 0);
    private static final CellPos ABOVE_LADDER_1 = new CellPos(0, LADDER_Y + 3, 0);

    /**
     * Solid floor at LADDER_Y; ladder column above is air (the empty
     * collision shape comes from putShape, the climbable property from
     * putTraits).
     */
    private static MockWorldView ladderColumn() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(FLOOR, "minecraft:stone"));
        for (CellPos cell : new CellPos[] {LADDER_0, LADDER_1, LADDER_2}) {
            w.putBlock(new BlockSnapshot(cell, "minecraft:ladder"));
            w.putTraits(cell, BlockTraits.climbableOnly());
            w.putShape(cell, CollisionShape.empty());
        }
        // Headroom above the top ladder cell.
        w.putBlock(new BlockSnapshot(ABOVE_LADDER_2, "minecraft:air"));
        w.putShape(ABOVE_LADDER_2, CollisionShape.empty());
        return w;
    }

    @Test
    void climbingUpALadderColumnIsViable() {
        MockWorldView w = ladderColumn();
        var climb = new BasicMoves.ClimbUp(LADDER_0, LADDER_1);
        assertTrue(climb.isViable(w), "climbing from one ladder cell to the one above must be viable");
    }

    @Test
    void climbingFromTheTopLadderCellIntoAirIsViable() {
        MockWorldView w = ladderColumn();
        var climb = new BasicMoves.ClimbUp(LADDER_2, ABOVE_LADDER_2);
        assertTrue(climb.isViable(w), "climbing off the top of a ladder into open air must be viable");
    }

    @Test
    void unannotatedLadderIsInvisibleToClimb() {
        MockWorldView w = ladderColumn();
        // Strip the trait: passable shape stays, but without the
        // climbable annotation the move must refuse — an unknown block
        // is not automatically climbable (decision 19a).
        w.putTraits(LADDER_0, BlockTraits.defaults());
        var climb = new BasicMoves.ClimbUp(LADDER_0, LADDER_1);
        assertFalse(climb.isViable(w), "no climbable trait, no climb - defaults must not engage it");
    }

    @Test
    void climbingIntoABlockedCellIsRejected() {
        MockWorldView w = ladderColumn();
        // Destination is a solid block — the body cannot fit.
        w.putBlock(new BlockSnapshot(LADDER_1, "minecraft:stone"));
        w.putShape(LADDER_1, CollisionShape.fullCube());
        var climb = new BasicMoves.ClimbUp(LADDER_0, LADDER_1);
        assertFalse(climb.isViable(w), "a blocked destination must reject ClimbUp");
    }

    @Test
    void climbingWithBlockedHeadroomIsRejected() {
        MockWorldView w = ladderColumn();
        // Destination is passable but the cell above is solid — the
        // body's head would clip.
        w.putBlock(new BlockSnapshot(ABOVE_LADDER_1, "minecraft:stone"));
        w.putShape(ABOVE_LADDER_1, CollisionShape.fullCube());
        var climb = new BasicMoves.ClimbUp(LADDER_0, LADDER_1);
        assertFalse(climb.isViable(w), "blocked headroom above the destination must reject ClimbUp");
    }

    @Test
    void walkingIntoAnUpperLadderCellIsRejected() {
        MockWorldView w = ladderColumn();
        // LADDER_1 has no walkable top below it (LADDER_0 is empty
        // shape), so it is not standable. The bottom ladder cell
        // (LADDER_0, directly above the floor) IS standable - that is
        // correct, you can stand at the base of a ladder.
        var walk = new BasicMoves.Walk(LADDER_0, LADDER_1);
        assertFalse(walk.isViable(w), "an upper ladder cell has no floor below; Walk must refuse");
    }

    @Test
    void jumpingUpIntoAnUpperLadderCellIsRejected() {
        MockWorldView w = ladderColumn();
        var jump = new BasicMoves.JumpUp(LADDER_0, LADDER_1);
        assertFalse(jump.isViable(w), "an upper ladder cell is not standable; JumpUp must refuse");
    }
}
