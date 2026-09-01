package com.mcbot.mcbotserver.core.behavior;

import static com.mcbot.mcbotserver.core.behavior.UseClaimTestSupport.inventory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.tick.RecordingActor;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layer-1 gate for the directed melee strike: a combat order names
 * its victim by id, and the melee path answers with a USE-channel
 * {@code Intent.Strike} claim carrying THAT id - the addressed hit
 * that lets passive prey (the hunt face) and the defend target take
 * the swing, instead of the resolver's nearest-hostile cone which
 * never qualified a cow. Also pins the pacing (one claim per
 * cooldown window) and the hold-item / range gates.
 */
class CombatStrikeGateTest {

    private static final CellPos TARGET = new CellPos(6, 64, 6);

    private static final String COW_ID = "cow-uuid-1";

    private static final String SWORD = "minecraft:iron_sword";

    @Test
    void directedOrderStrikesTheOrderedEntity() {
        CombatBehavior combat = combatAt(TARGET);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, SWORD));

        runTicks(combat, world, actor, CombatBehavior.ATTACK_COOLDOWN_TICKS);

        List<Intent.Strike> strikes = strikeClaims(actor);
        assertEquals(1, strikes.size(), "the first strike lands one cooldown window after engagement");
        assertEquals(COW_ID, strikes.get(0).entityId(), "the strike names the ordered victim");
    }

    @Test
    void strikePacesOneClaimPerCooldownWindow() {
        CombatBehavior combat = combatAt(TARGET);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, SWORD));

        // A never-swung body is always ready: the first strike fires
        // on the first engagement tick.
        combat.tick(world, order(), actor);
        assertEquals(1, strikeClaims(actor).size(), "the ready body strikes immediately");

        // Then quiet until the cooldown window elapses.
        runTicks(combat, world, actor, CombatBehavior.ATTACK_COOLDOWN_TICKS - 1);
        assertEquals(1, strikeClaims(actor).size(), "no double strike inside the window");

        combat.tick(world, order(), actor);
        assertEquals(2, strikeClaims(actor).size(), "the next window presses again");
    }

    @Test
    void holdItemSuppressesTheStrike() {
        CombatBehavior combat = combatAt(TARGET);
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, SWORD));

        // A raised shield must not be interrupted by a swing press.
        world.setInventory(inventory(0, "minecraft:shield"));
        runTicks(combat, world, actor, CombatBehavior.ATTACK_COOLDOWN_TICKS * 3);

        assertTrue(strikeClaims(actor).isEmpty(), "a hold-item never swings");
    }

    @Test
    void outOfReachTargetNeverStrikes() {
        CombatBehavior combat = combatAt(new CellPos(TARGET.x() + 8, TARGET.y(), TARGET.z()));
        RecordingActor actor = new RecordingActor();
        MockWorldView world = new MockWorldView();
        world.setInventory(inventory(0, SWORD));

        runTicks(combat, world, actor, CombatBehavior.ATTACK_COOLDOWN_TICKS * 3);

        assertTrue(strikeClaims(actor).isEmpty(), "beyond melee reach the path paces, it never strikes");
    }

    /** A combat behavior standing at the given cell, sword drawn. */
    private static CombatBehavior combatAt(CellPos bodyCell) {
        return new CombatBehavior(
                "combat",
                () -> new Vec3(bodyCell.x() + 0.5, bodyCell.y(), bodyCell.z() + 0.5),
                com.mcbot.mcbotserver.api.inventory.WeaponCatalog.none());
    }

    /** The directed-attack directive aimed at the body cell. */
    private static Directive order() {
        return new Directive(new GoalNear(TARGET, 1), new Overrides(new Attack(COW_ID)));
    }

    private static void runTicks(CombatBehavior combat, MockWorldView world, RecordingActor actor, int ticks) {
        for (int i = 0; i < ticks; i++) {
            combat.tick(world, order(), actor);
        }
    }

    private static List<Intent.Strike> strikeClaims(RecordingActor actor) {
        List<Intent.Strike> strikes = new java.util.ArrayList<>();
        for (Claim c : actor.submitted) {
            if (c.channel() == Channel.USE && c.intent() instanceof Intent.Strike s) {
                strikes.add(s);
            }
        }
        return strikes;
    }
}
