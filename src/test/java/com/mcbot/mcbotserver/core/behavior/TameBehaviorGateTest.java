package com.mcbot.mcbotserver.core.behavior;

import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.applySlotFeedback;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.inventory;
import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.selectedSlotOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.process.Tame;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the tame behavior: equip the species' tame item
 * before any press, press the use-on-entity chain on the interval,
 * go quiet when the item is gone, and reset the cadence when the
 * directive drops (the sit-toggle guard's other half).
 */
class TameBehaviorGateTest {

    private static final CellPos TARGET = new CellPos(6, 64, 6);

    private static final String BONE = "minecraft:bone";

    private static final String WOLF = "minecraft:wolf";

    @Test
    void equipsTameItemBeforePressing() {
        TameBehavior tame = new TameBehavior("tame");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, null, BONE));

        tame.tick(world, order(), actor);

        assertEquals(3, selectedSlotOf(actor), "the bone slot must be claimed first");
        assertTrue(interactClaims(actor).isEmpty(), "no press before the equip lands");
    }

    /**
     * With the item equipped, presses land one per interval - each a
     * single claim (the adapter's rising edge releases it), spaced by
     * PRESS_INTERVAL_TICKS.
     */
    @Test
    void pressesOnTheInterval() {
        TameBehavior tame = new TameBehavior("tame");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, null, BONE));

        tame.tick(world, order(), actor);
        applySlotFeedback(world, actor);

        tame.tick(world, order(), actor);
        assertEquals(1, interactClaims(actor).size(), "the first press lands after equip");
        assertEquals("wolf-1", interactClaims(actor).get(0).entityId(), "the press names the ordered animal");

        for (int i = 0; i < TameBehavior.PRESS_INTERVAL_TICKS; i++) {
            tame.tick(world, order(), actor);
        }
        assertEquals(1, interactClaims(actor).size(), "the interval holds - no double press");

        tame.tick(world, order(), actor);
        assertEquals(2, interactClaims(actor).size(), "the next press lands one interval later");
    }

    /** A spent tame item goes quiet - the process owns the typed verdict. */
    @Test
    void spentItemStopsThePresses() {
        TameBehavior tame = new TameBehavior("tame");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, null, BONE));

        tame.tick(world, order(), actor);
        applySlotFeedback(world, actor);
        tame.tick(world, order(), actor);
        int claimsBefore = interactClaims(actor).size();
        int slotsBefore = slotClaims(actor).size();

        // The bone stack burned out mid-mission.
        world.setInventory(inventory(0, null, null, null, (String) null));
        tame.tick(world, order(), actor);

        assertEquals(claimsBefore, interactClaims(actor).size(), "no tame item means no press");
        assertEquals(slotsBefore, slotClaims(actor).size(), "and no re-equip churn");
    }

    /**
     * A dropped directive resets the cadence: the next engagement
     * presses immediately, and no claim fires while the order is
     * gone.
     */
    @Test
    void droppedDirectiveResetsTheCadence() {
        TameBehavior tame = new TameBehavior("tame");
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, null, null, null, BONE));

        tame.tick(world, order(), actor);
        applySlotFeedback(world, actor);
        tame.tick(world, order(), actor);
        assertEquals(1, interactClaims(actor).size());

        tame.tick(world, null, actor);
        assertTrue(interactClaims(actor).size() == 1, "no order means no claim");

        tame.tick(world, order(), actor);
        assertEquals(2, interactClaims(actor).size(), "a fresh order presses immediately");
    }

    private static Directive order() {
        return new Directive(new GoalNear(TARGET, 2), new Overrides(null, null, new Tame("wolf-1", WOLF)));
    }

    private static List<Intent.InteractEntity> interactClaims(RecordingActor actor) {
        List<Intent.InteractEntity> out = new ArrayList<>();
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.INTERACT && c.intent() instanceof Intent.InteractEntity e) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Claim> slotClaims(RecordingActor actor) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.SLOT) {
                out.add(c);
            }
        }
        return out;
    }
}
