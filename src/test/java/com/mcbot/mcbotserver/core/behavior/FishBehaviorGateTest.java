package com.mcbot.mcbotserver.core.behavior;

import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.inventory;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.pressSequence;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.selectedSlotOf;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.useClaims;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.inventory.InventoryView;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Fish;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BobberSnapshot;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the fish behavior (issue 0010 section 7): equip
 * the rod, cast when no bobber is out, arm the bite watch only after
 * the cast settles, reel on the dip, stop after the bite budget.
 * The dip - not a nibble flag - is the bite, because vanilla keeps
 * the nibble state private.
 */
class FishBehaviorGateTest {

    private static final CellPos WATER = new CellPos(4, 62, 4);

    private static final String ROD = "minecraft:fishing_rod";

    @Test
    void equipsRodBeforeCasting() {
        FishBehavior fish = new FishBehavior("fish");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(0, null, null, null, ROD));

        fish.tick(world, order(), actor);

        assertEquals(3, selectedSlotOf(actor), "the rod slot must be claimed first");
        assertTrue(useClaims(actor).isEmpty(), "no cast before the equip lands");
    }

    @Test
    void castsWhenNoBobberIsOut() {
        FishBehavior fish = new FishBehavior("fish");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, null, null, null, ROD));

        fish.tick(world, order(), actor);
        fish.tick(world, order(), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(List.of(true, false), presses, "the cast is a rising edge then a release");
    }

    @Test
    void bobberDipTriggersTheReel() {
        FishBehavior fish = new FishBehavior("fish");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, null, null, null, ROD));

        // Cast and land the edge.
        fish.tick(world, order(), actor);
        fish.tick(world, order(), actor);
        // Bobber flying: it descends during the grace window, which
        // must not read as a bite.
        for (int y = 70; y > 63; y--) {
            world.addBobber(new BobberSnapshot(new CellPos(5, y, 5), false));
            fish.tick(world, order(), actor);
        }
        // Settled bobber: stable long enough to arm the watch
        // (grace 30 + settle 5).
        for (int i = 0; i < 40; i++) {
            world.addBobber(new BobberSnapshot(new CellPos(5, 63, 5), false));
            fish.tick(world, order(), actor);
        }
        assertEquals(2, useClaims(actor).size(), "settling must not reel - only the cast pair exists");
        int claimsBeforeDip = useClaims(actor).size();

        // The bite: one sharp drop reels the rod in.
        world.addBobber(new BobberSnapshot(new CellPos(5, 62, 5), false));
        fish.tick(world, order(), actor);
        fish.tick(world, order(), actor);

        List<Boolean> presses = pressSequence(actor);
        assertEquals(claimsBeforeDip + 2, presses.size(), "the reel is two edge ticks");
        assertTrue(presses.get(presses.size() - 2), "the reel starts on a rising edge");
        assertFalse(presses.get(presses.size() - 1), "and releases the next tick");
    }

    @Test
    void biteBudgetStopsTheClaims() {
        FishBehavior fish = new FishBehavior("fish");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, null, null, null, ROD));

        fish.tick(world, order(), actor);
        fish.tick(world, order(), actor);
        world.addBobber(new BobberSnapshot(new CellPos(5, 63, 5), false));
        for (int i = 0; i < FishBehavior.BITE_BUDGET_TICKS + 10; i++) {
            world.addBobber(new BobberSnapshot(new CellPos(5, 63, 5), false));
            fish.tick(world, order(), actor);
        }
        int claimsAtEnd = useClaims(actor).size();
        for (int i = 0; i < 20; i++) {
            world.addBobber(new BobberSnapshot(new CellPos(5, 63, 5), false));
            fish.tick(world, order(), actor);
        }
        assertEquals(claimsAtEnd, useClaims(actor).size(), "a spent budget claims nothing");
    }

    @Test
    void droppedDirectiveStopsTheClaims() {
        FishBehavior fish = new FishBehavior("fish");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = world(inventory(3, null, null, null, ROD));

        fish.tick(world, order(), actor);
        fish.tick(world, order(), actor);
        // Directive drops to plain locomotion: no more USE traffic.
        fish.tick(world, Directive.of(new GoalNear(WATER, 1)), actor);
        fish.tick(world, Directive.of(new GoalNear(WATER, 1)), actor);

        assertEquals(2, useClaims(actor).size(), "only the original cast edge pair remains");
    }

    private static Directive order() {
        return new Directive(new GoalNear(WATER, 1), new Overrides(null, new Fish(WATER)));
    }

    private static MockWorldView world(InventoryView inventory) {
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory);
        return world;
    }
}
