package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestAsserts.check;
import static com.mcbot.mcbotserver.gametest.GametestAsserts.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.SETTLE_TICKS;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.spawnHostile;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.VanillaWeaponCatalog;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.inventory.BindingInventory;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.actor.Channel;
import com.mcbot.mcbotserver.api.actor.Claim;
import com.mcbot.mcbotserver.api.actor.Intent;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.behavior.CombatBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
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
// One @GameTest scenario = one method, plus the shared private
// assertion helpers; the method count tracks scenario coverage the
// workplan owns as one combat slice, not god-class drift (same
// ruling shape as MineProcess).
@SuppressWarnings("PMD.TooManyMethods")
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
    // feature: combat.melee.vanilla_weapon_catalog
    @GameTest(template = "empty16x8x16", timeoutTicks = 100)
    public static void equipmentMirrorFeedsAttributes(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        var body = rig.body();
        var container = body.getInventory().container();

        container.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        container.setItem(BindingInventory.ARMOR_START, new ItemStack(Items.DIAMOND_HELMET));

        helper.startSequence()
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
    // feature: combat.hostile_acquisition.tactical_engagement
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!mission.missionSucceeded(), "a refusal is a failure, not a success");
                    checkEquals(
                            DefendProcess.REASON_REFUSED,
                            mission.failureReasonOrNull(),
                            "the refusal reason must be structured");
                    BotEvent failed = GametestAsserts.eventsOf(rig.events()).stream()
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
                    check(
                            !GametestAsserts.eventSeen(rig.events(), EventKind.TASK_PAUSED),
                            "a standoff-range skeleton must not trip the engage " + "reflex (no TASK_PAUSED expected)");
                    rig.body().discard();
                    // The skeleton survives the standoff assertion - it
                    // must not survive the scenario: a leaked hostile
                    // wanders the wall-less structures and jams later
                    // geometry (the sneakcrawl gap family).
                    skeleton.discard();
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
    // feature: combat.melee.damage_chain
    // capability: combat.melee
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
                LevelThreatSensor.rangedTypes(),
                new VanillaWeaponCatalog());
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
        return GametestAsserts.eventsOf(events).stream()
                .filter(e -> EventKind.TASK_FAILED.equals(e.kind()) || EventKind.TASK_COMPLETED.equals(e.kind()))
                .filter(e -> e.attrs().getOrDefault("task", "").startsWith("reflex-engage"))
                .findFirst()
                .orElse(null);
    }

    /**
     * Scenario 4b: the intruder fights BACK. The zombie keeps its AI;
     * with the carrier-side presence pass it acquires the body on
     * sight and attacks on its
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
    // feature: combat.melee.damage_chain
    // capability: combat.melee
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
    // feature: combat.line_of_sight.terrain_clip
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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
     * pass (monsters see the body
     * the way they would see a player, at their own follow range,
     * through their own line-of-sight ray).
     *
     * <p>Discriminative chain: target acquisition (z.getTarget is the
     * body) proves the presence pass; the kill then proves the
     * reflex-engage chain still fires when the threat closes in.
     * Weakened to 8 health like the retaliation scenario - the pin is
     * the acquisition and the fight, not a duel.
     */
    // feature: combat.hostile_acquisition.omni_directional_scan
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
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
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

    /**
     * Scenario: the sword's attack cooldown gates damage, not just
     * animation - a 60-health zombie under a full diamond-sword
     * engagement must take its (at least six) hits spaced by the
     * ATTACK_SPEED cadence (1.6/s = 12.5 ticks), never
     * machine-gun-fast. The health-delta log is the oracle: each
     * recorded drop is one landed 9.0 swing (on-ground approach, no
     * crit, no sprint), so inter-drop gaps below 11 ticks can only
     * mean the cooldown gate regressed.
     */
    // feature: combat.melee.attack_cooldown
    // capability: combat.melee
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT + 100)
    public static void swordCooldownSpacesTheHits(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(6, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0);
        zombie.setHealth(60.0f);

        final List<Integer> hitTicks = new ArrayList<>();
        final float[] lastHealth = {60.0f};
        final int[] tick = {0};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    tick[0]++;
                    float health = zombie.getHealth();
                    if (health < lastHealth[0] - 0.5f) {
                        hitTicks.add(tick[0]);
                    }
                    lastHealth[0] = health;
                    check(zombie.isDeadOrDying() || zombie.isRemoved(), "waiting for the long fight");
                }))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(
                            hitTicks.size() >= 6,
                            "a 60-health zombie takes at least 6 nine-damage hits, got " + hitTicks.size());
                    for (int i = 1; i < hitTicks.size(); i++) {
                        int gap = hitTicks.get(i) - hitTicks.get(i - 1);
                        check(
                                gap >= 11,
                                "attack cooldown must space hits ~12.5 ticks apart; gap " + gap + " before hit "
                                        + (i + 1));
                    }
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a bot-killed hostile pays vanilla XP -
     * MeleeResolver's setLastHurtByPlayer attribution makes the
     * zombie seed experience orbs near its death spot. The pin is
     * the ATTRIBUTION chain: orbs must exist after a bot kill
     * (either already absorbed, or lying near the corpse; the
     * zombie's 5 XP scatters into orbs that drift beyond the ~1.5
     * pickup box, and the goto-to-corpse races the reflex mission
     * releasing the move channel, so which half fires is
     * environment noise). Absorption-to-levels is pinned separately
     * by diamondOreSeedsXpOrbs (same tickXpPickup) and
     * xpNeededPerLevelMatchesVanilla. The roof keeps daylight from
     * burning the kill away from the bot.
     */
    // feature: combat.melee.damage_chain
    // capability: combat.melee
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void killsGrantXpLevels(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.FLOOR_Y + 7, z), Blocks.SMOOTH_STONE);
            }
        }
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        // Zero knockback: engage spam lands several small-cooldown
        // hits, and each carries knockback that slides the NoAi
        // target out of the structure before it dies - the orbs then
        // spawn where no goto can economically camp. A stationary
        // kill keeps the orb cluster at the fight position.
        rig.body().getAttribute(Attributes.ATTACK_KNOCKBACK).setBaseValue(0.0);
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(7, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);
        zombie.setHealth(8f);
        final CellPos[] deathSpot = {null};

        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig, () -> check(zombie.isDeadOrDying() || zombie.isRemoved(), "waiting for the kill")))
                .thenExecuteAfter(0, () -> {
                    // Camp the orb cluster: orbs spawn at the death
                    // position and scatter, the pickup box is ~1.5, so
                    // the body must walk to where the zombie died
                    // before the absorption window means anything.
                    deathSpot[0] = new CellPos(zombie.getBlockX(), zombie.getBlockY(), zombie.getBlockZ());
                    GametestRig.submitGoto(rig, deathSpot[0]);
                })
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().experience().getTotal() >= 1 || orbCountNear(rig, deathSpot[0]) >= 1,
                                "a bot-killed zombie must seed XP: absorbed total="
                                        + rig.body().experience().getTotal()
                                        + ", orbs near death spot="
                                        + orbCountNear(rig, deathSpot[0]))))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    // Level-machinery movement is pinned where
                    // absorption is deterministic (the ore scenario);
                    // here the attribution chain is the contract.
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a raised shield blocks frontal MELEE damage, not just
     * arrows. The vanilla isDamageSourceBlocked gate reads the same
     * using-item state for every physical source (projectile or mob
     * attack), so a shield held against an attacking zombie must zero
     * the incoming hit. This pins the melee half that the arrow tests
     * leave open: the damage control (unshielded) proves the source
     * deals damage; the shielded phase proves the same source is
     * fully absorbed when the body faces it with the USE hold active.
     *
     * <p>Discriminative facts: (1) the unshielded body loses health
     * from a mobAttack source; (2) the shielded body loses zero health
     * from the identical source; (3) isBlocking() is true during the
     * shielded phase — a regression where the USE edge fails to raise
     * the shield fails here before the damage check.
     */
    // feature: combat.shield.body_blocking
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void raisedShieldBlocksFrontalMelee(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(5, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.SHIELD));
        rig.body().setYRot(-90f); // face east, toward the damage source

        Zombie attacker = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(8, GametestRig.WALK_Y, 8));
        attacker.setNoAi(true);
        net.minecraft.world.damagesource.DamageSource source =
                helper.getLevel().damageSources().mobAttack(attacker);

        helper.startSequence()
                // Phase 1 (damage control): no shield, the mobAttack source
                // must deal damage. This proves the source is live and the
                // body is not invulnerable.
                .thenExecuteAfter(0, () -> {
                    float before = rig.body().getHealth();
                    rig.body().hurt(source, 6.0f);
                    check(
                            rig.body().getHealth() < before,
                            "the control melee hit must deal damage unshielded, before=" + before + " after="
                                    + rig.body().getHealth());
                })
                // Phase 2: raise the shield and verify isBlocking is true.
                .thenExecuteFor(20, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:shield", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(
                        0, () -> check(rig.body().isBlocking(), "the body must be blocking before the melee hit"))
                // Phase 3: the identical source must deal zero damage through
                // the raised shield. Capture health before and after; the
                // shielded hit must not move the health bar.
                .thenExecuteAfter(0, () -> {
                    float before = rig.body().getHealth();
                    rig.body().hurt(source, 6.0f);
                    check(
                            rig.body().getHealth() >= before,
                            "a frontal melee hit against a raised shield must deal no damage, before=" + before
                                    + " after=" + rig.body().getHealth());
                })
                .thenExecuteAfter(0, () -> {
                    rig.body().discard();
                    attacker.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: a grounded, non-sprinting sword swing sweeps - the
     * bystander one cell off the main target takes the sweep tick
     * (1.0 damage with no Sweeping Edge enchant, shaved to ~0.94 by
     * the zombie's 2 natural armor), not a full 9.0 swing and not
     * nothing. The bystander's health is captured on
     * the exact poll the main target's death is first seen, before
     * the rescan can engage the bystander as a fresh main target.
     */
    // feature: combat.melee.sweep_attack
    // capability: combat.melee
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void sweepClipsBystanders(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        Zombie main = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(6, GametestRig.WALK_Y, 8));
        Zombie bystander = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(7, GametestRig.WALK_Y, 7));
        main.setNoAi(true);
        bystander.setNoAi(true);
        main.setHealth(8f);
        final float[] bystanderAtMainDeath = {20.0f};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    if (main.isDeadOrDying() || main.isRemoved()) {
                        bystanderAtMainDeath[0] = bystander.getHealth();
                    } else {
                        check(false, "waiting for the main-target kill");
                    }
                }))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                bystander.isDeadOrDying() || bystander.isRemoved(),
                                "waiting for the follow-up engagement to finish")))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    float captured = bystanderAtMainDeath[0];
                    check(captured <= 19.2f, "the bystander must take the sweep tick, health=" + captured);
                    check(captured >= 17.5f, "sweep damage is 1.0, not a full swing; health=" + captured);
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: swings landed while the body stands in water never
     * crit - no health drop on the target may exceed the plain 9.0
     * swing damage ceiling (a critical would read 13.5). The water
     * basin is two source layers deep over the paved floor; the bot
     * wades in on approach and the whole engagement runs with
     * isInWater true. Per-hit LOWER bounds are deliberately not
     * pinned: the engage chain re-presses attacks on rising edges,
     * and vanilla correctly scales click-spam damage down with the
     * cooldown factor, so individual water-fight hits may be far
     * below 9.0 - the crit ceiling is the discriminative fact. The
     * positive crit half (falling hits DO crit) is deferred: no
     * production path swings reliably mid-fall, and staging one
     * would pin bot-controlled physics, not the crit gate.
     */
    // feature: combat.melee.crit_hit
    // capability: combat.melee
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void underwaterSwingsNeverCrit(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 8));
        for (int x = 3; x <= 9; x++) {
            for (int z = 5; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, GametestRig.WALK_Y, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, GametestRig.WALK_Y + 1, z), Blocks.WATER);
            }
        }
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(6, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);

        final List<Float> damages = new ArrayList<>();
        final float[] lastHealth = {20.0f};

        helper.startSequence()
                .thenWaitUntil(driveUntil(rig, () -> {
                    float health = zombie.getHealth();
                    if (health < lastHealth[0] - 0.5f) {
                        damages.add(lastHealth[0] - health);
                    }
                    lastHealth[0] = health;
                    check(zombie.isDeadOrDying() || zombie.isRemoved(), "waiting for the wading fight");
                }))
                .thenExecuteFor(SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!damages.isEmpty(), "the wading fight must land at least one hit");
                    for (float damage : damages) {
                        check(damage <= 9.5f, "an in-water swing must never crit (1.5x = 13.5), saw " + damage);
                    }
                    rig.body().discard();
                })
                .thenSucceed();
    }
    /** Live XP-orb census in an 8-block cube around a cell, for
     * failure diagnostics: distinguishes "orbs never spawned" from
     * "orbs never absorbed".
     *
     * @param rig  the wired rig; never null
     * @param cell the center cell; null reports -1
     * @return orb count near the cell, or -1 without a cell
     */
    private static int orbCountNear(GametestRig.Rig rig, CellPos cell) {
        if (cell == null) {
            return -1;
        }
        var center = new net.minecraft.world.phys.Vec3(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
        var box = new net.minecraft.world.phys.AABB(center, center).inflate(8.0);
        return rig.body()
                .level()
                .getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)
                .size();
    }
    /**
     * Scenario: a raised shield blocks a frontal arrow - the vanilla
     * chain end to end (isDamageSourceBlocked reads the HURT body's
     * own using-item state, which is why the USE edge raises the
     * shield on the body, never the facade). Phase one fires an
     * arrow at the unshielded body as the damage control; phase two
     * fires an identical arrow with the USE claim holding and the
     * health must not move.
     */
    // feature: combat.shield.body_blocking
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void raisedShieldBlocksIncomingArrow(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(5, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.SHIELD));
        rig.body().setYRot(-90f); // face east, toward the archer
        final float[] preShielded = {20.0f};

        helper.startSequence()
                .thenExecuteAfter(0, () -> fireArrowAt(helper))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                rig.body().getHealth() < 20f,
                                "the control arrow must land unblocked, health="
                                        + rig.body().getHealth())))
                .thenExecuteFor(20, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:shield", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isBlocking(), "the body must be blocking");
                    preShielded[0] = rig.body().getHealth();
                    fireArrowAt(helper);
                })
                // The claim must stay armed THROUGH the flight: a
                // vanished USE claim releases the shield hold
                // (releaseUseHolds) before the arrow arrives.
                .thenExecuteFor(30, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:shield", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(0, () -> {
                    check(
                            rig.body().getHealth() >= preShielded[0],
                            "a frontal arrow against a raised shield must deal no damage, before=" + preShielded[0]
                                    + " after=" + rig.body().getHealth());
                    rig.body().discard();
                })
                .thenSucceed();
    }

    /**
     * Spawns an arrow six cells east of the body flying west at it.
     *
     * @param helper the running gametest; never null
     */
    private static void fireArrowAt(GameTestHelper helper) {
        var abs = helper.absolutePos(new BlockPos(11, GametestRig.WALK_Y, 8));
        var arrow = EntityType.ARROW.create(helper.getLevel());
        check(arrow != null, "arrow creation failed");
        arrow.moveTo(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, 90f, 0f);
        arrow.shoot(-1.0, 0.0, 0.0, 3.0f, 0.0f);
        helper.getLevel().addFreshEntity(arrow);
    }

    /**
     * Scenario: the bow's charge accumulates through the facade's
     * pumped use loop and the falling edge releases a real shot - a
     * 25-tick hold (full draw is 20) must land a heavy arrow on a
     * NoAi zombie six cells south. The tap control is deferred: the
     * weak-arrow floor interacts with the facade draw pacing, and
     * the heavy-damage pin alone proves charge flows into damage.
     */
    // feature: combat.bow_draw.draw_and_release
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void bowChargedShotDamagesDistantTarget(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(5, GametestRig.WALK_Y, 2));
        GametestRig.giveBowAndArrows(rig);
        // The production ranged engagement: no hand-pumped claims -
        // the defend mission and the ranged loadout own the draw
        // pacing, exactly what a harness /entities attack rides.
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(5, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);

        helper.startSequence()
                .thenExecuteAfter(0, () -> submitDefend(rig))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                zombie.getHealth() < 19f,
                                "the ranged engagement must land arrows, health=" + zombie.getHealth())))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the standoff must be safe");
                    rig.body().discard();
                    // NoAi does not exempt the zombie from the leak
                    // discipline: it still absorbs later scenarios'
                    // arrows and scans from its spot.
                    zombie.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the ranged stance follows the live loadout, not the
     * engage-time snapshot. A bow carrier holds the standoff while
     * ammunition lasts; once the arrows are gone the same fight
     * re-routes to the swing rim - the mission keeps its target and
     * only the goal flips (GoalRange standoff to GoalNear charge).
     * The zombie is NoAi and invulnerable: this scenario pins the
     * STANCE transition, not damage delivery, so the target cannot
     * die or fight back mid-flight and the geometry stays
     * deterministic.
     */
    // feature: combat.hostile_acquisition.mid_fight_loadout_reroute
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void arrowsRunOutMidFightReroutesToMelee(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.giveBowAndArrows(rig);
        var container = rig.body().getInventory().container();
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(8, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);
        zombie.setInvulnerable(true);

        helper.startSequence()
                .thenExecuteAfter(0, () -> submitDefend(rig))
                .thenWaitUntil(driveUntil(rig, () -> {
                    // Wall-less structures admit strays mid-run (the
                    // sneakcrawl jam family): sweep so the stance
                    // transition, not contamination luck, is what
                    // this scenario measures.
                    GametestRig.sweepForeignEntities(helper, rig.body(), zombie);
                    check(
                            Math.abs(rig.body().getBlockX() - zombie.getBlockX()) >= 8,
                            "the standoff must open before the reroute; dist="
                                    + Math.abs(rig.body().getBlockX() - zombie.getBlockX()));
                }))
                .thenExecuteAfter(0, () -> container.setItem(1, ItemStack.EMPTY))
                .thenWaitUntil(driveUntil(rig, () -> {
                    GametestRig.sweepForeignEntities(helper, rig.body(), zombie);
                    check(
                            Math.abs(rig.body().getBlockX() - zombie.getBlockX()) <= 4,
                            "arrows gone must charge the swing rim; dist="
                                    + Math.abs(rig.body().getBlockX() - zombie.getBlockX()));
                }))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the rerouted fight must stay survivable");
                    rig.body().discard();
                    zombie.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the range-band goal makes the bot back away when a
     * target is inside the minimum standoff. A bow-only carrier faces
     * a NoAi zombie at Chebyshev distance 5 — inside the reflex's
     * engage trigger (6) and inside GoalRange's lower bound (8). The
     * reflex-engaged DefendProcess must select the ranged loadout,
     * emit a GoalRange directive, and the body must walk west to
     * restore at least 8 blocks of separation while landing arrows.
     * This is the issue 0018 pin: GoalNear would satisfy at distance
     * &lt;=10 and never back the body up; GoalRange's lower bound
     * drives backward motion.
     *
     * <p>Rides the reflex engage path (no manual submitDefend) so the
     * mission identity, priority and weapon catalog match production
     * exactly — the same seam {@code defendsByKillingZombie} pins.
     */
    // feature: combat.hostile_acquisition.engagement_range
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void rangedBotBacksAwayWhenTargetClosesInsideMin(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.giveBowAndArrows(rig);

        // Distance 5: x 3 -> 8, same z. Inside reflex trigger (6) and
        // inside GoalRange min (8), so the predicate fails from tick one.
        Zombie zombie = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(8, GametestRig.WALK_Y, 8));
        zombie.setNoAi(true);
        // A stray entity inside the structure is a nearer hostile for
        // the threat sensor and a body for the kiting walk - pooled-run
        // cross-contamination class (ledger 46).
        GametestRig.sweepForeignEntities(helper, rig.body(), zombie);
        final float[] healthBefore = {zombie.getHealth()};

        helper.startSequence()
                // The reflex engages on the first ground tick the threat
                // sensor sees the zombie (distance 5 <= trigger 6). Then
                // DefendProcess locks on, emits GoalRange, and PathingBehavior
                // replans outward. Reaction window: lock-on (~2 ticks),
                // replan, worker compute, adoption, departure hold (4),
                // then movement. 120 ticks is generous.
                .thenWaitUntil(driveUntil(rig, () -> {
                    int dist = Math.abs(rig.body().getBlockX() - zombie.getBlockX());
                    check(
                            dist >= 8,
                            "still inside min: bodyX=" + rig.body().getBlockX() + " targetX=" + zombie.getBlockX()
                                    + " dist=" + dist);
                }))
                // Arrows get their own poll window: the first full draw
                // (20 charge ticks + flight) releases right around band
                // arrival - an instant check at settle time races the
                // impact and reads a clean kite as zero damage.
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                zombie.getHealth() < healthBefore[0],
                                "the kite must land arrows after restoring range; health=" + zombie.getHealth())))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isAlive(), "the body must survive the kiting encounter");
                    int finalDist = Math.abs(rig.body().getBlockX() - zombie.getBlockX());
                    check(
                            finalDist >= 8,
                            "after backing away the separation must be at least the band min; dist=" + finalDist
                                    + " bodyX=" + rig.body().getBlockX() + " targetX=" + zombie.getBlockX());
                    rig.body().discard();
                    // The kite only requires a health drop - the zombie
                    // walks away alive unless the tail retires it.
                    zombie.discard();
                })
                .thenSucceed();
    }
    /**
     * Scenario: a raised shield blocks only the frontal hemisphere -
     * an arrow from BEHIND lands full damage through the same raised
     * shield (vanilla LivingEntity.hurt's direction gate: the
     * source-to-body vector dotted against the view vector must be
     * negative to block; a rear source fails it). The body faces
     * east with the shield up; the archer stands west.
     */
    // feature: combat.shield.body_blocking
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void rearArrowBypassesTheRaisedShield(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(11, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.SHIELD));
        rig.body().setYRot(-90f); // face east; the archer fires from the west, behind
        final float[] before = {20.0f};

        helper.startSequence()
                .thenExecuteFor(20, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:shield", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(0, () -> {
                    check(rig.body().isBlocking(), "the shield must be up");
                    before[0] = rig.body().getHealth();
                    fireArrowFromWestAt(helper, rig);
                })
                .thenExecuteFor(30, () -> {
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:shield", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(
                        0,
                        () -> check(
                                rig.body().getHealth() < before[0],
                                "a rear arrow must land through the raised shield, before=" + before[0] + " after="
                                        + rig.body().getHealth()))
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /**
     * Spawns an arrow six cells west of the body flying east at it.
     *
     * @param helper the running gametest; never null
     * @param rig    the wired rig; never null
     */
    private static void fireArrowFromWestAt(GameTestHelper helper, GametestRig.Rig rig) {
        var local = GametestRig.toLocal(helper, rig.body().blockPosition());
        var abs = helper.absolutePos(new BlockPos(local.getX() - 6, GametestRig.WALK_Y, local.getZ()));
        var arrow = EntityType.ARROW.create(helper.getLevel());
        check(arrow != null, "arrow creation failed");
        arrow.moveTo(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, -90f, 0f);
        arrow.shoot(1.0, 0.0, 0.0, 3.0f, 0.0f);
        helper.getLevel().addFreshEntity(arrow);
    }
    /**
     * Scenario: the charge flows into the damage - a 4-tick tap
     * release stays in the weak band (below the 0.1 charge floor no
     * arrow spawns at all; just above it the damage is a fraction of
     * the full draw) on one hand-pumped shot per NoAi zombie.
     *
     * <p>The target sits SEVEN cells out, deliberately outside
     * {@code EngageOnHostileProximityRule.TRIGGER_DISTANCE} (6): at
     * the trigger boundary the reflex mints its own defend mission
     * and the pipeline's CombatBehavior fights the hand pump for the
     * USE channel - the release edges corrupt and the tap lands full
     * draw (the 2026-09-01 double RED, the bowtap 14.096 flake
     * family). Hand-pumped scenarios own their channels exclusively.
     */
    // feature: combat.bow_draw.draw_and_release
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT + 100)
    public static void bowTapShotDealsWeakDamage(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(4, GametestRig.WALK_Y, 2));
        GametestRig.giveBowAndArrows(rig);
        // ONE target straight south, both shots down the same column:
        // a second target at an angle sends stray tap arrows out of
        // the structure into neighbouring pooled scenarios (the lava
        // trench cross-contamination class, ledger 46). Seven cells,
        // not six: outside the engage reflex's trigger (see Javadoc).
        Zombie fullDraw = spawnHostile(helper, EntityType.ZOMBIE, new BlockPos(4, GametestRig.WALK_Y, 9));
        fullDraw.setNoAi(true);
        // The scenario pins the BOW's damage, not the target's armor: a
        // vanilla zombie carries 2 armor points, and their 1.6% reduction
        // on a base-6 shot (the crit-0 edge of the full draw) lands at
        // health 14.096 - above the 14.05 bound and the source of the
        // flake. Zero the armor attribute so "6+ base" reads directly
        // off the health bar.
        var armorAttr = fullDraw.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armorAttr != null) {
            armorAttr.setBaseValue(0.0);
        }
        // The full-draw miss with health untouched is the pooled-run
        // contamination class (a leaked entity absorbing the shot) -
        // sweep the structure so the only thing on the firing column
        // is the intended target.
        GametestRig.sweepForeignEntities(helper, rig.body(), fullDraw);
        // The hand pump owns ROT as well as USE: without an aim claim
        // the arrow flies wherever the idle look channel points, not
        // at the target (the 7-cell clean-miss class). Same bearing
        // and ballistic lift the behavior tier computes.
        double dx = fullDraw.getX() - rig.body().getX();
        double dy = fullDraw.getY() + 1.0 - rig.body().getEyeY();
        double dz = fullDraw.getZ() - rig.body().getZ();
        double lift = com.mcbot.mcbotserver.core.behavior.CombatBehavior.ARROW_DROP_PER_BLOCK_SQUARED
                * (dx * dx + dy * dy + dz * dz);
        float aimYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float aimPitch = (float) -Math.toDegrees(Math.atan2(dy + lift, Math.max(Math.hypot(dx, dz), 0.001)));
        final float[] afterFull = {20.0f};

        helper.startSequence()
                .thenExecuteFor(25, () -> {
                    rig.actor().submit(new Claim(Channel.ROT, 50, "test:aim", new Intent.Look(aimYaw, aimPitch)));
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:draw", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteFor(2, () -> {
                    rig.actor().submit(new Claim(Channel.ROT, 50, "test:aim", new Intent.Look(aimYaw, aimPitch)));
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:draw", new Intent.Use(false)));
                    GametestRig.driveTick(rig);
                })
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                fullDraw.getHealth() <= 14.05f,
                                "the full draw must land heavy damage (6+ base; the crit bonus is"
                                        + " random 0-4 so exactly 6 is legal), health=" + fullDraw.getHealth())))
                .thenExecuteAfter(0, () -> afterFull[0] = fullDraw.getHealth())
                .thenExecuteFor(4, () -> {
                    rig.actor().submit(new Claim(Channel.ROT, 50, "test:aim", new Intent.Look(aimYaw, aimPitch)));
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:tap", new Intent.Use(true)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteFor(2, () -> {
                    rig.actor().submit(new Claim(Channel.ROT, 50, "test:aim", new Intent.Look(aimYaw, aimPitch)));
                    rig.actor().submit(new Claim(Channel.USE, 50, "test:tap", new Intent.Use(false)));
                    GametestRig.driveTick(rig);
                })
                .thenExecuteFor(60, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    float tapDelta = afterFull[0] - fullDraw.getHealth();
                    check(
                            tapDelta <= 4.0f,
                            "a 4-tick tap must stay in the weak band (<=4 damage, or no arrow at all"
                                    + " below the 0.1 charge floor), delta=" + tapDelta);
                    check(
                            reflexEngageVerdict(rig.events()) == null,
                            "the hand pump must own the USE channel: a reflex-engage mission here means"
                                    + " the target spawned inside the trigger radius");
                    rig.body().discard();
                    // The weak-tap assertion leaves the zombie standing;
                    // retire it with the body.
                    fullDraw.discard();
                })
                .thenSucceed();
    }

    /**
     * Scenario: the reactive shield answers an incoming projectile
     * through the production chain - the projectile scan (arrows are
     * invisible to the living-entity scan), the closest-approach
     * geometry, and the behavior-tier block path raising the body
     * shield before impact. The damage control (sword only, no
     * shield) proves the arrow delivers; the shielded phase proves
     * the identical geometry deals nothing while the guard is up, and
     * that the guard lowers after the flight clears.
     *
     * <p>The combat order's goal sits at the bot's own feet on
     * purpose: the attack tier runs without locomotion so the arrow
     * geometry stays fixed. The behavior instance ticks beside the
     * rig - the seated assembly holds no directive, so nothing else
     * claims USE or SLOT. The arrow flies at 0.65 blocks per tick
     * with drag-corrected gravity compensation: the ~17-tick flight
     * locks the threat around tick 6, leaving the vanilla 5-tick arm
     * delay (isBlocking needs five held ticks) four spare ticks over
     * the 2-tick reaction budget.
     *
     * <p>Discriminative facts: (1) unshielded, the arrow drops
     * health; (2) with the shield carried, isBlocking() turns true
     * before impact and the identical arrow moves nothing; (3) after
     * the flight clears and the linger expires, the guard releases.
     */
    // feature: combat.shield.projectile_reaction
    // capability: combat.shield
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void blocksIncomingArrowsMidCombat(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(5, GametestRig.WALK_Y, 5));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_SWORD));
        // The goal cell is the body's own (absolute) cell: a combat
        // order with the goal already reached runs the attack tier
        // without locomotion, keeping the arrow geometry fixed.
        BlockPos feet = rig.body().blockPosition();
        Directive order = new Directive(
                new GoalNear(new CellPos(feet.getX(), feet.getY(), feet.getZ()), 1),
                new Overrides(new Attack("gametest:shield")));
        CombatBehavior combat =
                new CombatBehavior("shield-reaction", () -> eyeOf(rig.body()), new VanillaWeaponCatalog());
        final float[] controlBefore = {20.0f};
        final float[] shieldedBefore = {20.0f};

        helper.startSequence()
                // Phase 1 (damage control): sword only, no shield in
                // the hotbar - the arrow must land and deal damage.
                .thenExecuteAfter(0, () -> controlBefore[0] = rig.body().getHealth())
                .thenExecuteAfter(
                        0,
                        () -> spawnArrowToward(
                                helper,
                                new BlockPos(5, GametestRig.WALK_Y + 1, 14),
                                rig.body().getEyePosition()))
                .thenExecuteFor(20, () -> {
                    combat.tick(rig.view(), order, rig.actor());
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(
                        0,
                        () -> check(
                                rig.body().getHealth() < controlBefore[0],
                                "the control arrow must deal damage without a shield, before=" + controlBefore[0]
                                        + " after=" + rig.body().getHealth()))
                // Phase 2: the shield enters the hotbar and the
                // identical geometry must be answered by a raised
                // guard before impact.
                .thenExecuteAfter(
                        0, () -> rig.body().getInventory().container().setItem(1, new ItemStack(Items.SHIELD)))
                .thenExecuteAfter(0, () -> {
                    shieldedBefore[0] = rig.body().getHealth();
                    spawnArrowToward(
                            helper,
                            new BlockPos(5, GametestRig.WALK_Y + 1, 14),
                            rig.body().getEyePosition());
                })
                .thenWaitUntil(() -> {
                    combat.tick(rig.view(), order, rig.actor());
                    GametestRig.driveTick(rig);
                    check(rig.body().isBlocking(), "the guard must raise against the incoming arrow");
                })
                .thenExecuteFor(30, () -> {
                    combat.tick(rig.view(), order, rig.actor());
                    GametestRig.driveTick(rig);
                })
                .thenExecuteAfter(
                        0,
                        () -> check(
                                rig.body().getHealth() >= shieldedBefore[0],
                                "the blocked arrow must deal no damage, before=" + shieldedBefore[0] + " after="
                                        + rig.body().getHealth()))
                .thenWaitUntil(() -> {
                    combat.tick(rig.view(), order, rig.actor());
                    GametestRig.driveTick(rig);
                    check(!rig.body().isBlocking(), "the guard must release after the flight clears");
                })
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /**
     * The body's eye pose as the combat aim source - the same origin
     * the production assembly wires (arrows launch from eye height).
     *
     * @param body the acting body; never null
     * @return the eye position in api coordinates; never null
     */
    private static com.mcbot.mcbotserver.api.types.Vec3 eyeOf(BotBodyEntity body) {
        net.minecraft.world.phys.Vec3 eye = body.getEyePosition();
        return new com.mcbot.mcbotserver.api.types.Vec3(eye.x, eye.y, eye.z);
    }

    /**
     * A manually aimed arrow at 0.65 blocks per tick with drag-
     * corrected gravity compensation: the aim point rises by the
     * free-fall drop over the estimated flight (times a small
     * correction factor - drag lengthens the flight, deepening the
     * drop), so the slow arrow still arrives at the target height.
     *
     * @param helper the test host, for the level
     * @param from   the launch cell (structure-local; converted here)
     * @param target the intended impact point (world coordinates)
     * @return the spawned arrow; never null
     */
    private static Arrow spawnArrowToward(GameTestHelper helper, BlockPos from, net.minecraft.world.phys.Vec3 target) {
        var abs = helper.absolutePos(from);
        Arrow arrow = new Arrow(helper.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        net.minecraft.world.phys.Vec3 straight = target.subtract(arrow.position());
        double ticks = straight.length() / 0.65;
        double lift = 1.05 * 0.5 * 0.05 * ticks * ticks;
        net.minecraft.world.phys.Vec3 launch = target.add(0.0, lift, 0.0)
                .subtract(arrow.position())
                .normalize()
                .scale(0.65);
        arrow.setDeltaMovement(launch);
        helper.getLevel().addFreshEntity(arrow);
        return arrow;
    }
}
