package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Fishing machinery acceptance, in-engine: the rod's cast/reel edge
 * pair through the production USE channel spawns and retrieves the
 * bobber entity. The bite detection (dip threshold, settle arming,
 * post-reel reset) is pinned offline by FishBehaviorGateTest - this
 * family owns only what the engine can prove: the click becomes a
 * FishingHook in the ordered water, and the second click removes
 * it. The RNG bite itself (vanilla 100-600 tick wait) is deferred:
 * no deterministic staging exists for vanilla's hook pull without
 * cheating the entity, which would pin nothing real.
 *
 * <p>Contract: issue 0010 section 4.3/4.5 (fish behavior owns the
 * cast/reel micro-execution); the 2026-08-30 ranged/survival QA run
 * REQ for the fishing surface. Harness wiring lives in
 * {@link GametestRig}.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotFishingGameTests {

    private BotFishingGameTests() {}

    /**
     * Scenario: the rod's air-use verb end to end - one verb call
     * casts (a FishingHook floats in the aimed pool), a second verb
     * call reels (the hook is gone). The cast and the reel are TWO
     * SEPARATE verb invocations with settle between them: vanilla
     * FishingRodItem.use toggles cast/reel on its player.fishing
     * state, so a same-tick double press spawns and instantly
     * retrieves (the diagnostic that first read as an owner bug -
     * the hook was never unresolvable, just already reeled).
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void rodCastsAndReelsTheBobber(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.fillPool(helper, 6, 10, 5, 11, 1, Blocks.WATER);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.FISHING_ROD));
        rig.body().setYRot(-90f); // face east, straight at the pool

        helper.startSequence()
                .thenExecuteAfter(
                        0, () -> GametestRig.check(rig.actor().useHeldItemAir(), "the cast verb must consume"))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                bobbersInPool(helper) >= 1,
                                "the cast must leave a bobber in the pool, pool=" + bobbersInPool(helper) + " world="
                                        + hooksInWorld(helper))))
                .thenExecuteFor(20, GametestRig.driveOnly(rig))
                .thenExecuteAfter(
                        0, () -> GametestRig.check(rig.actor().useHeldItemAir(), "the reel verb must consume"))
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                hooksInWorld(helper) == 0,
                                "the reel must remove the bobber, world=" + hooksInWorld(helper))))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, GametestRig.driveOnly(rig))
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /** World-wide bobber census, for failure diagnostics. */
    private static int hooksInWorld(GameTestHelper helper) {
        var origin = helper.absolutePos(BlockPos.ZERO);
        var box = new AABB(
                origin.getX() - 64,
                origin.getY() - 32,
                origin.getZ() - 64,
                origin.getX() + 64,
                origin.getY() + 32,
                origin.getZ() + 64);
        return helper.getLevel().getEntitiesOfClass(FishingHook.class, box).size();
    }

    private static int bobbersInPool(GameTestHelper helper) {
        var origin = helper.absolutePos(BlockPos.ZERO);
        var box = new AABB(
                origin.getX() + 5,
                origin.getY() - 2,
                origin.getZ() + 4,
                origin.getX() + 15,
                origin.getY() + 4,
                origin.getZ() + 12);
        return helper.getLevel().getEntitiesOfClass(FishingHook.class, box).size();
    }
}
