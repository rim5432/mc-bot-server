package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.CollisionShape;
import com.mcbot.mcbotserver.core.pathing.BasicMoves;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trait-driven swim gate: the water moves answer from the liquid
 * trait, never from the block id, and a body of water with no trait
 * annotation must stay invisible to them (decision 19 + the 19a
 * gate: precondition derivable from Shape plus Traits).
 *
 * <p>Scenario: a shore at BODY_Y west of a water channel. The water
 * column is collision-empty (passable) but never standable - which
 * is exactly why Walk/ClimbUp cannot see it and Swim must.
 */
class BasicMovesSwimTest {

    private static final int SURFACE_Y = 63;
    private static final CellPos SHORE = new CellPos(0, SURFACE_Y, 0);
    private static final CellPos WATER = new CellPos(1, SURFACE_Y, 0);

    /**
     * Solid ground under SHORE only; WATER column is air (the empty
     * collision shape comes from putShape, the liquid property from
     * putTraits) down to the channel floor three deep.
     */
    private static MockWorldView shoreAndWater() {
        MockWorldView w = new MockWorldView();
        w.putBlock(new BlockSnapshot(SHORE, "minecraft:grass_block"));
        for (int y = SURFACE_Y - 1; y <= SURFACE_Y + 1; y++) {
            w.putBlock(new BlockSnapshot(new CellPos(1, y, 0),
                "minecraft:water"));
            w.putTraits(new CellPos(1, y, 0), BlockTraits.liquidOnly());
            w.putShape(new CellPos(1, y, 0), CollisionShape.empty());
        }
        return w;
    }

    private static BasicMoves.Swim swimShoreToWater() {
        return new BasicMoves.Swim(SHORE, WATER);
    }

    @Test
    void swimAcrossWaterColumnIsViable() {
        MockWorldView w = shoreAndWater();
        // One cell into the channel: same setup one further east.
        w.putBlock(new BlockSnapshot(new CellPos(2, SURFACE_Y, 0),
            "minecraft:water"));
        w.putTraits(new CellPos(2, SURFACE_Y, 0),
            BlockTraits.liquidOnly());
        w.putShape(new CellPos(2, SURFACE_Y, 0), CollisionShape.empty());
        var move = new BasicMoves.Swim(WATER,
            new CellPos(2, SURFACE_Y, 0));
        assertTrue(move.isViable(w),
            "swimming between two liquid cells must be viable");
    }

    @Test
    void steppingOffShoreIntoWaterIsViable() {
        assertTrue(swimShoreToWater().isViable(shoreAndWater()),
            "entering water from dry land is a deliberate Swim");
    }

    @Test
    void swimmingUpTheWaterColumnIsViable() {
        MockWorldView w = shoreAndWater();
        var up = new BasicMoves.SwimUp(WATER,
            new CellPos(1, SURFACE_Y + 1, 0));
        w.putBlock(new BlockSnapshot(new CellPos(1, SURFACE_Y + 1, 0),
            "minecraft:water"));
        w.putTraits(new CellPos(1, SURFACE_Y + 1, 0),
            BlockTraits.liquidOnly());
        w.putShape(new CellPos(1, SURFACE_Y + 1, 0),
            CollisionShape.empty());
        assertTrue(up.isViable(w),
            "treading up inside a water column must be viable");
    }

    @Test
    void unannotatedWaterIsInvisibleToSwim() {
        MockWorldView w = shoreAndWater();
        // Strip the trait: passable shape stays, but without the
        // liquid annotation the move must refuse - an unknown fluid
        // is not automatically swimmable (decision 19a).
        w.putTraits(WATER, BlockTraits.defaults());
        assertFalse(swimShoreToWater().isViable(w),
            "no liquid trait, no swim - defaults must not engage it");
    }

    @Test
    void walkingOntoAWaterSurfaceIsRejected() {
        MockWorldView w = shoreAndWater();
        var walk = new BasicMoves.Walk(SHORE, WATER);
        assertFalse(walk.isViable(w),
            "a water surface has no walkable top; Walk must refuse");
    }

    @Test
    void droppingIntoWaterNeedsNoFloor() {
        MockWorldView w = shoreAndWater();
        var drop = new BasicMoves.Drop(SHORE,
            new CellPos(1, SURFACE_Y - 1, 0), 1);
        assertTrue(drop.isViable(w),
            "liquid holds the body: drop into water is viable "
                + "without a walkable top below");
    }
}
