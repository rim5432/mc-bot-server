package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.AttackProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Land-game hunt yield acceptance, in-engine: the directed attack
 * task kills spawned prey on the pad and the prey's drop-table items
 * reach the carrier inventory. The kill alone is combat's evidence
 * (BotCombatGameTests owns the fight); this family asserts the
 * EXCURSION YIELD per the RE doc section 9 doctrine - the scenario
 * succeeds when the gain is in the bag, not when the mob dies.
 *
 * <p>Loot tables are data-driven vanilla; the scenario stakes no
 * claim beyond raw beef >= 1 (the cow table guarantees 1-3 with no
 * fire involved; leather 0-2 may legally be zero and is not
 * asserted). Harness wiring lives in {@link GametestRig}.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotHuntYieldGameTests {

    private BotHuntYieldGameTests() {}

    /**
     * Scenario: a cow spawned across the pad is hunted down by the
     * directed attack task, and raw beef from the cow's loot table
     * ends up in the carrier inventory - kill plus pickup, the full
     * yield chain, driven only by the production task pipeline. The
     * attack task retires the moment the prey drops (drops scatter
     * just past GroundPickup's reach envelope), so the scenario
     * completes the excursion the way the harness would: a goto
     * through the kill site walks the body over the drops.
     */
    // capability: acquisition.hunt_game
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void huntsCowAndCollectsBeefYield(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        Cow prey = GametestRig.spawnMob(helper, EntityType.COW, new BlockPos(11, GametestRig.WALK_Y, 8));
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.IRON_SWORD));
        AttackProcess mission = new AttackProcess(
                "gametest:hunt-cow",
                prey.getStringUUID(),
                50,
                GametestRig.TIMEOUT,
                () -> new CellPos(
                        rig.body().getBlockX(),
                        rig.body().getBlockY(),
                        rig.body().getBlockZ()));
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        helper.startSequence()
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                !prey.isAlive() && !mission.isActive(),
                                "waiting for the kill, cowAlive=" + prey.isAlive())))
                .thenExecuteAfter(
                        0,
                        () -> GametestRig.submitGoto(
                                rig, new CellPos(prey.getBlockX(), prey.getBlockY(), prey.getBlockZ())))
                .thenWaitUntil(driveUntil(
                        rig, () -> check(beefCount(rig) > 0, "waiting for beef pickup, beef=" + beefCount(rig))))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /** Count of raw beef currently in the carrier inventory. */
    private static int beefCount(GametestRig.Rig rig) {
        var container = rig.body().getInventory().container();
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(Items.BEEF)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
