package com.mcbot.mcbotserver.perception;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockSnapshot;
import com.mcbot.mcbotserver.api.world.EntitySnapshot;
import com.mcbot.mcbotserver.api.world.ViewMode;
import com.mcbot.mcbotserver.core.actor.ChannelArbiter;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-0 perception/action gate: WorldView honesty about unknowns,
 * Actor claim resolution semantics, and the goal algebra's arrival
 * predicates all hold on pure Java.
 *
 * <p>Contract: see boundaries.md section A and decisions 7/17a/17b;
 * ADR-0004 D2 for the claim rules under test here.
 */
class PerceptionActionGateTest {

    /**
     * Decision 17a: a naive view would answer AIR for ungenerated
     * terrain; ours must distinguish unknown from air.
     */
    @Test
    void worldViewDistinguishesAirFromUnknown() {
        CellPos stone = new CellPos(4, 60, -2);
        CellPos hidden = new CellPos(100, 60, 100);
        MockWorldView view = new MockWorldView()
            .putBlock(new BlockSnapshot(stone, "minecraft:stone"))
            .markUnloaded(hidden);

        assertEquals("minecraft:stone",
            view.getBlock(stone, ViewMode.LIVE).blockId());
        assertTrue(view.getBlock(new CellPos(0, 64, 0), ViewMode.LIVE)
                .isAir(),
            "unwritten loaded cells default to air");
        assertNull(view.getBlock(hidden, ViewMode.SNAPSHOT),
            "unloaded cells must read as unknown, never as air");
        assertFalse(view.isLoaded(hidden));
        assertTrue(view.isLoaded(stone));
    }

    /** Entity scan filters by spherical range around a center. */
    @Test
    void entityScanHonorsRadius() {
        EntitySnapshot near = new EntitySnapshot("e-near",
            "minecraft:creeper", new CellPos(10, 64, 10), 20f, 20f);
        EntitySnapshot far = new EntitySnapshot("e-far",
            "minecraft:skeleton", new CellPos(40, 64, 40), 20f, 20f);
        MockWorldView view = new MockWorldView()
            .addEntity(near)
            .addEntity(far);

        List<EntitySnapshot> hits = view.getEntities(
            new CellPos(11, 64, 10), 5.0, ViewMode.LIVE);
        assertEquals(List.of("e-near"),
            hits.stream().map(EntitySnapshot::id).toList());
    }

    /** ADR-0004 D2: higher priority displaces; silence expires. */
    @Test
    void claimResolutionPrefersHigherPriorityAndExpires() {
        ChannelArbiter actor = new ChannelArbiter();
        Claim weak = new Claim(Channel.MOVE, 10, "wanderer",
            new Intent.Move(0.5, 0, false, false));
        Claim strong = new Claim(Channel.MOVE, 50, "goto",
            new Intent.Move(1.0, 0, false, false));

        actor.submit(weak);
        actor.submit(strong);
        Map<Channel, Claim> winners = actor.flush();
        assertEquals("goto", winners.get(Channel.MOVE).holder());

        assertTrue(actor.flush().isEmpty(),
            "claims must expire at flush; next tick starts silent");
    }

    /**
     * ADR-0004 D2 tie-break: at equal priority the previous flush's
     * winner retains the channel, killing alternation jitter.
     */
    @Test
    void tieBreakFavorsIncumbentHolder() {
        ChannelArbiter actor = new ChannelArbiter();
        Intent look = new Intent.Look(90f, 0f);
        actor.submit(new Claim(Channel.ROT, 30, "pathing", look));
        assertEquals("pathing", actor.flush().get(Channel.ROT).holder());

        actor.submit(new Claim(Channel.ROT, 30, "combat", look));
        actor.submit(new Claim(Channel.ROT, 30, "pathing", look));
        assertEquals("pathing", actor.flush().get(Channel.ROT).holder(),
            "incumbent must survive an equal-priority challenge");

        actor.submit(new Claim(Channel.ROT, 31, "combat", look));
        assertEquals("combat", actor.flush().get(Channel.ROT).holder(),
            "a strictly higher priority always displaces");
    }

    /**
     * ADR-0005 D2 step 3 shape: clearing drops buffered claims without
     * resolving them, leaving no winner behind.
     */
    @Test
    void clearAllIntentsDropsWithoutResolving() {
        ChannelArbiter actor = new ChannelArbiter();
        actor.submit(new Claim(Channel.SLOT, 99, "equip",
            new Intent.SelectSlot(3)));
        actor.clearAllIntents();
        assertTrue(actor.flush().isEmpty(),
            "cleared claims must never win a flush");
    }

    /**
     * G3 hardening: the sealed Intent vocabulary must pair with its
     * channel at construction — mismatches die here, not inside the
     * Stage 1 adapter's dispatch.
     */
    @Test
    void mismatchedIntentChannelPairsAreRejected() {
        ChannelArbiter actor = new ChannelArbiter();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> actor.submit(new Claim(Channel.SLOT, 1, "equip",
                new Intent.Look(0f, 0f))));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> actor.submit(new Claim(Channel.USE, 1, "combat",
                new Intent.Move(1, 0, false, false))));

        // The legal pairings for the two channels added in this round.
        actor.submit(new Claim(Channel.USE, 5, "combat",
            new Intent.Use(true)));
        actor.submit(new Claim(Channel.SLOT, 5, "equip",
            new Intent.SelectSlot(2)));
        var winners = actor.flush();
        assertTrue(winners.get(Channel.USE).intent()
            instanceof Intent.Use);
        assertTrue(winners.get(Channel.SLOT).intent()
            instanceof Intent.SelectSlot);
    }

    /** Decision 7: GoalBlock is exact; GoalNear is Chebyshev-inclusive. */
    @Test
    void goalPredicatesDecideArrival() {
        CellPos center = new CellPos(12, 64, -8);

        GoalBlock block = new GoalBlock(center);
        assertTrue(block.isInGoal(new CellPos(12, 64, -8)));
        assertFalse(block.isInGoal(new CellPos(13, 64, -8)));

        GoalNear near = new GoalNear(center, 2);
        assertTrue(near.isInGoal(new CellPos(14, 64, -8)),
            "chebyshev == range stays inside (inclusive)");
        assertTrue(near.isInGoal(new CellPos(14, 66, -8)),
            "diagonal chebyshev counts once, not twice");
        assertFalse(near.isInGoal(new CellPos(15, 64, -8)));

        assertTrue(block.describe().startsWith("GoalBlock("));
        assertTrue(near.describe().startsWith("GoalNear("));
    }
}
