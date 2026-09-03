package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestAsserts.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestAsserts.eventsOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.MISSION_BUDGET;
import static com.mcbot.mcbotserver.gametest.GametestRig.SETTLE_TICKS;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.spawnHostile;
import static com.mcbot.mcbotserver.gametest.GametestRig.spawnMob;
import static com.mcbot.mcbotserver.gametest.GametestRig.sweepForeignEntities;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.core.process.TameProcess;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * In-engine scenarios for the directed-taming slice: the real
 * {@code Player.interactOn} chain consumes real bones through real
 * wolf AI state, so the tame verdict is engine-verified, not mocked.
 * The adapter executor is untestable offline (the eatWhenHungry
 * precedent), which is exactly the gap these scenarios close.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotTamingGameTests {

    private BotTamingGameTests() {}

    /**
     * Scenario: the full tame loop lands in-engine. The body walks
     * to the wolf, equips the bone, and presses the use-on-entity
     * chain on the pacing interval; vanilla consumes one bone per
     * attempt and rolls the 1/3 wolf chance, so the tamed flag flips
     * inside the mission budget with overwhelming probability
     * (P(fail all 20 presses) < 3e-4). The discriminative assertions
     * are the COMPLETED verdict naming the target and the consumed
     * stack - a no-op executor or a dead wire shows as a TIMEOUT.
     */
    // capability: taming.chain
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void tamesWolfWithBones(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Wolf wolf = spawnMob(helper, EntityType.WOLF, new BlockPos(8, GametestRig.WALK_Y, 8));
        wolf.setNoAi(true);
        sweepForeignEntities(helper, rig.body(), wolf);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.BONE, 32));
        TameProcess mission = submitTame(rig, wolf.getStringUUID());

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the tame verdict")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "the tame must complete, not fail: " + mission.failureReasonOrNull());
                    check(wolf.isTame(), "the wolf must carry the tamed flag");
                    check(wolf.getOwnerUUID() != null, "the wolf must carry an owner");
                    check(
                            rig.body().getInventory().container().getItem(0).getCount() < 32,
                            "at least one bone must have been consumed by the tame presses");
                    BotEvent completed = eventsOf(rig.events()).stream()
                            .filter(e -> EventKind.TASK_COMPLETED.equals(e.kind()))
                            .findFirst()
                            .orElse(null);
                    check(completed != null, "TASK_COMPLETED must be in stream");
                    checkEquals(
                            wolf.getStringUUID(),
                            completed.attrs().get("targetId"),
                            "the verdict must name the tamed animal");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the cat tame path lands in-engine with a different item
     * family than the wolf - cod (or salmon) rides the same
     * Player.interactOn chain through Cat.mobInteract, which consumes
     * via usePlayerItem and rolls 1/3. This scenario closes the
     * item-family gap: the wolf scenario proves the chain with bones,
     * this one proves the catalog's cod/salmon mapping reaches the
     * vanilla cat tame branch.
     */
    // capability: taming.chain
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void tamesCatWithCod(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Cat cat = spawnMob(helper, EntityType.CAT, new BlockPos(8, GametestRig.WALK_Y, 8));
        cat.setNoAi(true);
        sweepForeignEntities(helper, rig.body(), cat);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.COD, 32));
        TameProcess mission = submitTame(rig, cat.getStringUUID());

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the cat tame verdict")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            mission.missionSucceeded(),
                            "the cat tame must complete, not fail: " + mission.failureReasonOrNull());
                    check(cat.isTame(), "the cat must carry the tamed flag");
                    check(cat.getOwnerUUID() != null, "the cat must carry an owner");
                    check(
                            rig.body().getInventory().container().getItem(0).getCount() < 32,
                            "at least one cod must have been consumed by the tame presses");
                    BotEvent completed = eventsOf(rig.events()).stream()
                            .filter(e -> EventKind.TASK_COMPLETED.equals(e.kind()))
                            .findFirst()
                            .orElse(null);
                    check(completed != null, "TASK_COMPLETED must be in stream");
                    checkEquals(
                            cat.getStringUUID(),
                            completed.attrs().get("targetId"),
                            "the verdict must name the tamed animal");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: an already-owned animal is refused on the first
     * sighting with a typed reason - the mission never presses, so
     * the tamed wolf's sit state and ownership stay untouched.
     */
    // capability: taming.chain
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void alreadyTamedWolfFailsFast(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Wolf wolf = spawnMob(helper, EntityType.WOLF, new BlockPos(8, GametestRig.WALK_Y, 8));
        wolf.setNoAi(true);
        wolf.setTame(true);
        wolf.setOwnerUUID(UUID.randomUUID());
        sweepForeignEntities(helper, rig.body(), wolf);
        TameProcess mission = submitTame(rig, wolf.getStringUUID());

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the refusal")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.missionSucceeded(), "a refusal is a failure, not a success");
                    checkEquals(
                            TameProcess.REASON_ALREADY_TAMED,
                            mission.failureReasonOrNull(),
                            "the refusal reason must be typed");
                    BotEvent failed = eventsOf(rig.events()).stream()
                            .filter(e -> EventKind.TASK_FAILED.equals(e.kind()))
                            .findFirst()
                            .orElse(null);
                    check(failed != null, "TASK_FAILED must be in stream");
                    checkEquals(
                            TameProcess.REASON_ALREADY_TAMED,
                            failed.attrs().get("reason"),
                            "the refusal reason must reach the harness");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a non-tameable target is refused on the first
     * sighting, before any approach - the tame task never chases a
     * species its presses cannot land on.
     */
    // capability: taming.chain
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void tameRejectsNonTameableTarget(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(11, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);
        sweepForeignEntities(helper, rig.body(), zombie);
        TameProcess mission = submitTame(rig, zombie.getStringUUID());

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> check(!mission.isActive(), "waiting for the refusal")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.missionSucceeded(), "a refusal is a failure, not a success");
                    checkEquals(
                            TameProcess.REASON_NOT_TAMEABLE,
                            mission.failureReasonOrNull(),
                            "the refusal reason must be typed");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Registers and takes control of one tame mission with the
     * standard scenario budget.
     *
     * @param rig      the wired rig; never null
     * @param targetId the animal's snapshot id; never null or blank
     * @return the active mission
     */
    private static TameProcess submitTame(GametestRig.Rig rig, String targetId) {
        TameProcess mission = new TameProcess(
                "gt-tame-" + Integer.toHexString(targetId.hashCode()),
                targetId,
                50,
                MISSION_BUDGET,
                () -> positionOf(rig.body()));
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }
}
