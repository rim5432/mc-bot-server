package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.spawnHostile;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Combat acceptance, in-engine: structural refusal of ranged
 * threats, reflex-owned engagements through the production chain,
 * retaliation survival, sight-blocked standoff honesty, and the
 * world-started fight. Harness wiring lives in {@link GametestRig};
 * this class owns only the scenarios and their assertions.
 *
 * <p>Contract: see workplan Stage 2 skeleton combat slice, ledger 23
 * (reflex-carried idle combat) and issue 0006 (verdict honesty).
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotCombatGameTests {

    /**
     * Upper bound on ticks from mission start to a structural
     * refusal: the refusal decision is made on the mission's first
     * directive pass, so a healthy run lands in single digits - the
     * window only exists to absorb sequence-overhead ticks.
     */
    private static final int REFUSAL_WINDOW_TICKS = 15;

    private BotCombatGameTests() {}

    /**
     * Scenario: the equipment mirror feeds the vanilla attribute
     * machinery (Phase 4 combat slice). A diamond sword held in the
     * selected hotbar slot raises ATTACK_DAMAGE to the zombie-scale
     * base (3.0) plus the sword modifier (7.0), and a worn helmet
     * raises ARMOR: LivingEntity.detectEquipmentUpdates applies item
     * modifiers for exactly this mirror view one tick after the
     * container changes, so melee damage and armor reduction ride
     * vanilla with no wiring beyond the mirror itself.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void equipmentMirrorFeedsAttributes(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var body = rig.body();
        var container = body.getInventory().container();

        container.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        container.setItem(BindingInventory.ARMOR_START, new ItemStack(Items.DIAMOND_HELMET));

        helper.startSequence()
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            Items.DIAMOND_SWORD.equals(
                                    body.getItemBySlot(EquipmentSlot.MAINHAND).getItem()),
                            "the equipment mirror must see the selected hotbar stack as mainhand");
                    checkEquals(
                            9.0,
                            body.getAttributeValue(Attributes.ATTACK_DAMAGE),
                            "ATTACK_DAMAGE must be base 3.0 + diamond sword 6.0");
                    checkEquals(
                            3.0,
                            body.getAttributeValue(Attributes.ARMOR),
                            "a worn diamond helmet must raise ARMOR through the mirror");
                })
                .thenSucceed();
    }

    /**
     * Scenario 5: a ranged hostile is REFUSED, not chased. The planner
     * sees the structural mismatch (melee-only vs kite-and-shoot) and
     * fails the mission on the engage tick with a structured reason -
     * before the body spends a single tick under fire.
     *
     * <p>The skeleton sits at its natural ranged standoff (8 cells,
     * outside {@code EngageOnHostileProximityRule}'s 6-cell trigger)
     * - under the production rule set a skeleton that closed to
     * melee range would trip the idle-combat reflex instead, which is
     * the scenario 4 shape, not this one. The discriminative
     * assertions are the structured refusal reason plus the tick
     * window: a regression to chasing keeps the mission alive past
     * the window and fails here, not at timeout.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void refusesRangedItCannotAnswer(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Skeleton skeleton = spawnHostile(helper, EntityType.SKELETON, new BlockPos(11, GametestRig.WALK_Y, 8));
        skeleton.setNoAi(true);

        DefendProcess mission = submitDefend(rig);
        int[] waited = {0};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    waited[0]++;
                    check(!mission.isActive(), "waiting for the refusal");
                }))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.missionSucceeded(), "a refusal is a failure, not a success");
                    checkEquals(
                            DefendProcess.REASON_REFUSED,
                            mission.failureReasonOrNull(),
                            "the refusal reason must be structured");
                    BotEvent failed = rig.events().statusSnapshot(0).events().stream()
                            .filter(e -> EventKind.TASK_FAILED.equals(e.kind()))
                            .findFirst()
                            .orElse(null);
                    check(failed != null, "TASK_FAILED must be in stream");
                    checkEquals(
                            "ENGAGEMENT_REFUSED", failed.attrs().get("reason"), "refusal reason must be structured");
                    checkEquals(
                            "minecraft:skeleton",
                            failed.attrs().get("threatType"),
                            "the refused type must reach the harness");
                    check(
                            waited[0] <= REFUSAL_WINDOW_TICKS,
                            "a structural refusal lands on the engage tick, not " + "after a chase; waited " + waited[0]
                                    + " ticks");
                    List<String> kinds = rig.events().statusSnapshot(0).events().stream()
                            .map(BotEvent::kind)
                            .toList();
                    check(
                            !kinds.contains(EventKind.TASK_PAUSED),
                            "a standoff-range skeleton must not trip the engage " + "reflex (no TASK_PAUSED expected)");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 4: an intruder inside the engage radius gets engaged,
     * chased the last step, and beaten down - through the PRODUCTION
     * path: no mission is submitted by the test; the idle-combat
     * reflex ({@code EngageOnHostileProximityRule}, 6-cell trigger)
     * notices the zombie and the controller's ENGAGE handling mints a
     * reflex-owned defend mission from the wiring's factory. This is
     * the exact chain whose absence caused the 2026-08-24 night-cave
     * death, so it stays pinned in-engine end to end.
     *
     * <p>The reflex mission ends with TARGET_ESCAPED once the target
     * stops existing — the bot cannot distinguish a killed target from
     * an escaped one (no death flag on EntitySnapshot; health=0 is not
     * filtered), so it reports the conservative failure verdict. The
     * behavioral assertions below (zombie dead, body alive) prove the
     * bot actually won the fight; the mission verdict is the honest
     * uncertainty signal, not a behavioral failure. See issue 0006.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void defendsByKillingZombie(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(7, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(zombie.isDeadOrDying() || zombie.isRemoved(), "waiting for the fight to end")))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reflexEngageVerdict(rig.events()) != null, "waiting for the reflex mission verdict")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    BotEvent verdict = reflexEngageVerdict(rig.events());
                    check(
                            verdict != null,
                            "the fight must be reflex-owned (a reflex-engage " + "mission verdict in the stream)");
                    checkEquals(
                            DefendProcess.REASON_ESCAPED,
                            verdict.attrs().get("reason"),
                            "the absence-after-grace verdict must be TARGET_ESCAPED");
                    check(zombie.isDeadOrDying() || zombie.isRemoved(), "the zombie must not survive the engagement");
                    check(rig.body().isAlive(), "the body must walk away from this fight");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    private static DefendProcess submitDefend(GametestRig.Rig rig) {
        String taskId = "gt-df-" + Integer.toHexString(System.identityHashCode(rig.body()));
        DefendProcess mission = new DefendProcess(
                taskId,
                60,
                GametestRig.MISSION_BUDGET,
                () -> GametestRig.positionOf(rig.body()),
                LevelThreatSensor.hostileTypes(),
                LevelThreatSensor.rangedTypes());
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    /**
     * First terminal event of a reflex-owned engage mission, or null
     * while the fight is still running. The wiring's factory mints
     * task ids prefixed {@code reflex-engage-}, which is what makes
     * the production engage chain observable from outside.
     *
     * @param events the rig's event stream; never null
     * @return the verdict event, or null when no reflex mission has
     *         retired yet
     */
    private static BotEvent reflexEngageVerdict(InMemoryEventQueue events) {
        return events.statusSnapshot(0).events().stream()
                .filter(e -> EventKind.TASK_FAILED.equals(e.kind()) || EventKind.TASK_COMPLETED.equals(e.kind()))
                .filter(e -> e.attrs().getOrDefault("task", "").startsWith("reflex-engage"))
                .findFirst()
                .orElse(null);
    }

    /**
     * Scenario 4b: the intruder fights BACK. The zombie keeps its AI;
     * with the carrier-side presence pass it acquires the body on
     * sight (world-treatment ruling 2026-08-25) and attacks on its
     * own, and any hit the body lands adds HurtByTargetGoal heat on
     * top - the fight is two-sided from the start. Weakened to 8
     * health on purpose: the pin is "engages, survives being fought,
     * wins", not a fair duel, and a short fight keeps the body
     * comfortably above the freeze threshold.
     *
     * <p>Roof: the whole floor gets a stone ceiling one layer below
     * the template top. The arena runs at daytime and an AI zombie is
     * sun-sensitive - without the roof the target burns instead of
     * fighting, which would test daylight, not retaliation.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void survivesRetaliatingZombie(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + 7, z), Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(7, GametestRig.WALK_Y, 8));
        zombie.setHealth(8f);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                zombie.isDeadOrDying() || zombie.isRemoved(),
                                "waiting for the retaliation fight to end")))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reflexEngageVerdict(rig.events()) != null, "waiting for the reflex mission verdict")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    BotEvent verdict = reflexEngageVerdict(rig.events());
                    check(verdict != null, "the retaliation fight must be reflex-owned");
                    checkEquals(
                            DefendProcess.REASON_ESCAPED,
                            verdict.attrs().get("reason"),
                            "a killed target ends as TARGET_ESCAPED");
                    check(zombie.isDeadOrDying() || zombie.isRemoved(), "the retaliating zombie must not survive");
                    check(rig.body().isAlive(), "the body must survive being fought back");
                    check(
                            rig.body().getHealth()
                                    > com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                            "survival means staying above the freeze threshold, " + "got "
                                    + rig.body().getHealth());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 4c: a target behind a full wall is engaged (the threat
     * scan is distance-based and wall-blind - by design, see
     * LevelThreatSensor) but cannot be damaged: the bot closes to its
     * standoff, swings on cooldown, and every swing is eaten by
     * {@code MeleeResolver.sightBlocked}. The mission then fails
     * honestly by its own tick budget instead of pretending to win.
     *
     * <p>The zombie stays at exactly 20 health for the whole scenario
     * - that zero-damage fact is the pin. If the LOS clip regresses,
     * swings land and this fails with a damaged zombie.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = (int) BotAssembly.ENGAGE_MISSION_TIMEOUT_TICKS + 100)
    public static void holdsFireWhenSightBlocked(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        for (int z = 0; z < 16; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(8, GametestRig.FLOOR_Y + y, z), Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(9, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reflexEngageVerdict(rig.events()) != null,
                                "waiting for the walled-off engagement to time out")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    BotEvent verdict = reflexEngageVerdict(rig.events());
                    check(verdict != null, "a verdict must be present");
                    checkEquals(
                            DefendProcess.REASON_TIMEOUT,
                            verdict.attrs().get("reason"),
                            "an undamageable target must fail by budget, " + "not by success or escape");
                    checkEquals(20f, zombie.getHealth(), "no swing may land through a full wall");
                    check(rig.body().isAlive(), "the standoff must be harmless to the body too");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario 4d: the WORLD starts the fight. The zombie spawns 7
     * cells out - outside the engage reflex's 6-cell trigger - with
     * clear line of sight and its AI on. Nothing the bot does can
     * start this engagement; the only path into the fight is the
     * zombie acquiring the body through the carrier-side presence
     * pass (world-treatment ruling 2026-08-25: monsters see the body
     * the way they would see a player, at their own follow range,
     * through their own line-of-sight ray).
     *
     * <p>Discriminative chain: target acquisition (z.getTarget is the
     * body) proves the presence pass; the kill then proves the
     * reflex-engage chain still fires when the threat closes in.
     * Weakened to 8 health like the retaliation scenario - the pin is
     * the acquisition and the fight, not a duel.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void hostilesAggroOnSight(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + 7, z), Blocks.SMOOTH_STONE);
            }
        }
        // Isolation wall (2 high, perimeter): the empty template has
        // no walls, so with 27 concurrent tests a NEIGHBOURING
        // structure's body can have clear line of sight to this
        // zombie from inside its 40-block presence box and win the
        // acquisition race (both bots scan every 20 ticks; whoever
        // hits the target==null zombie first claims it). The wall
        // makes the foreign sight-line fail while the in-structure
        // fight (7 blocks, clear interior) is untouched.
        for (int x = 0; x < 16; x++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y, 0), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + y, 15), Blocks.SMOOTH_STONE);
            }
        }
        for (int z = 1; z < 15; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(0, GametestRig.FLOOR_Y + y, z), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(15, GametestRig.FLOOR_Y + y, z), Blocks.SMOOTH_STONE);
            }
        }
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(10, GametestRig.WALK_Y, 8));
        zombie.setHealth(8f);

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(zombie.getTarget() == rig.body(), "waiting for the world to acquire the body")))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                zombie.isDeadOrDying() || zombie.isRemoved(),
                                "waiting for the sight-started fight to end")))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                reflexEngageVerdict(rig.events()) != null, "waiting for the reflex mission verdict")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    BotEvent verdict = reflexEngageVerdict(rig.events());
                    check(verdict != null, "the sight-started fight must be reflex-owned");
                    checkEquals(
                            DefendProcess.REASON_ESCAPED,
                            verdict.attrs().get("reason"),
                            "a killed target ends as TARGET_ESCAPED");
                    check(zombie.isDeadOrDying() || zombie.isRemoved(), "the aggressor must not survive");
                    check(rig.body().isAlive(), "the body must survive being hunted");
                    check(
                            rig.body().getHealth()
                                    > com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule.FREEZE_THRESHOLD,
                            "survival means staying above the freeze threshold, " + "got "
                                    + rig.body().getHealth());
                    rig.body().discard();
                })
                .thenSucceed();
    }
}
