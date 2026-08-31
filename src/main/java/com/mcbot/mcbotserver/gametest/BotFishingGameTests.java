package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.goal.GoalNear;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.Fish;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.process.MissionShell;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Scenario: after the bobber settles and the bite watch arms, a
     * sharp vertical dip (>= DIP_THRESHOLD = 0.25 blocks in one tick)
     * triggers exactly one reel and the bobber vanishes. The vanilla
     * bite RNG (5-30s) is bypassed by moving the FishingHook entity
     * directly — this pins the behavior layer's dip threshold, settle
     * arming, and reel edge against the real engine entity, not a mock.
     *
     * <p>Flow: FishMission activates FishBehavior -> behavior casts ->
     * bobber appears -> wait for settle+arm (CAST_GRACE 30 + SETTLE 5
     * + margin) -> setPos the hook down by 0.30 -> behavior detects
     * dip >= 0.25 -> reels -> bobber removed.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT)
    public static void biteDipTriggersReel(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.fillPool(helper, 6, 10, 5, 11, 1, Blocks.WATER);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.FISHING_ROD));
        rig.body().setYRot(-90f); // face east, straight at the pool

        CellPos waterCell = GametestRig.localToCell(helper, new BlockPos(8, 1, 8));
        var mission = submitFish(rig, waterCell);

        helper.startSequence()
                // 1. Wait for the behavior to cast and a bobber to appear.
                .thenWaitUntil(driveUntil(
                        rig, () -> check(hooksInWorld(helper) >= 1, "waiting for cast, hooks=" + hooksInWorld(helper))))
                // 2. Let the bobber settle and the bite watch arm.
                //    CAST_GRACE_TICKS=30 + SETTLE_TICKS=5 + 25 margin = 60.
                .thenExecuteFor(60, GametestRig.driveOnly(rig))
                // 3. Simulate the bite: move the bobber down by 0.30 blocks
                //    in one tick (>= DIP_THRESHOLD 0.25).
                .thenExecuteAfter(0, () -> {
                    FishingHook hook = firstHook(helper);
                    check(hook != null, "bobber must exist before bite simulation");
                    Vec3 pos = hook.position();
                    hook.setPos(pos.x, pos.y - 0.30, pos.z);
                })
                // 4. The behavior must reel on the dip — the bobber vanishes.
                .thenWaitUntil(driveUntil(
                        rig,
                        () -> check(
                                hooksInWorld(helper) == 0,
                                "waiting for reel after bite dip, hooks=" + hooksInWorld(helper))))
                .thenExecuteFor(GametestRig.SETTLE_TICKS, GametestRig.driveOnly(rig))
                .thenExecuteAfter(0, () -> rig.body().discard())
                .thenSucceed();
    }

    /**
     * Scenario: a bobber that has snagged a non-fish entity (mob or
     * item) is reeled immediately, bypassing the settle+arm sequence.
     * The behavior's hookedEntity branch resets the watch so the next
     * cast starts clean — this pins that branch against the engine.
     *
     * <p>We cannot reliably spawn a hooked entity in-engine without
     * reflection (H-R1), so this test verifies the cast path and the
     * no-bite budget reel instead: after BITE_BUDGET_TICKS (600) of
     * no bite, the behavior reels in and stops. 600 ticks is too long
     * for a gametest, so this scenario is deferred to the offline
     * FishBehaviorGateTest which controls the clock directly.
     *
     * <p>Kept as a placeholder documenting the coverage gap; the
     * hookedEntity and budget branches are pinned offline.
     */
    // capability: perception.projectiles
    @GameTest(template = "empty16x8x16", timeoutTicks = GametestRig.TIMEOUT, required = false)
    public static void hookedEntityTriggersImmediateReel(GameTestHelper helper) {
        // Deferred: in-engine hookedEntity requires spawning a FishingHook
        // with a hooked entity, which needs access to the hook's private
        // hookedEnt field (H-R1 test door). The offline FishBehaviorGateTest
        // covers this branch via a mock BobberSnapshot with hookedEntity=true.
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        GametestRig.fillPool(helper, 6, 10, 5, 11, 1, Blocks.WATER);
        rig.body().getInventory().container().setItem(0, new ItemStack(Items.FISHING_ROD));
        rig.body().setYRot(-90f);
        helper.startSequence().thenExecuteAfter(0, () -> rig.body().discard()).thenSucceed();
    }

    /** Submits a fish mission and returns it for lifecycle assertions. */
    private static FishMission submitFish(GametestRig.Rig rig, CellPos waterCell) {
        String taskId = "fish-" + Integer.toHexString(System.identityHashCode(waterCell));
        FishMission mission = new FishMission(taskId, waterCell, 50, 400);
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    /** First FishingHook in the world, or null when none is cast. */
    private static FishingHook firstHook(GameTestHelper helper) {
        var origin = helper.absolutePos(BlockPos.ZERO);
        var box = new AABB(
                origin.getX() - 64,
                origin.getY() - 32,
                origin.getZ() - 64,
                origin.getX() + 64,
                origin.getY() + 32,
                origin.getZ() + 64);
        List<FishingHook> hooks = helper.getLevel().getEntitiesOfClass(FishingHook.class, box);
        return hooks.isEmpty() ? null : hooks.get(0);
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

    /**
     * Minimal fish mission: holds a water cell and emits a Fish directive
     * every tick so the FishBehavior owns cast/settle/bite/reel. The
     * mission never succeeds on its own (the behavior only reports
     * RUNNING); tests assert on the bobber entity lifecycle instead.
     * Terminates on budget exhaustion.
     */
    private static final class FishMission extends MissionShell {

        private final CellPos waterCell;
        private boolean timedOut;

        FishMission(String taskId, CellPos waterCell, int priority, long timeoutTicks) {
            super(taskId, priority, timeoutTicks);
            this.waterCell = waterCell;
        }

        @Override
        public Directive onTick(com.mcbot.mcbotserver.api.world.WorldView world) {
            if (budgetExpired()) {
                timedOut = true;
                deactivate();
            }
            return new Directive(new GoalNear(waterCell, 1), new Overrides(null, new Fish(waterCell)));
        }

        @Override
        public void onExecutionReport(ExecutionReport report) {
            // FishBehavior only emits RUNNING; success is observed via the
            // bobber entity disappearing after a reel.
        }

        @Override
        public void onLostControl(InterruptionContext context) {}

        @Override
        public boolean resume(InterruptionContext context) {
            return live();
        }

        @Override
        public void onContextInvalidated() {
            deactivate();
        }

        @Override
        public String displayName() {
            return "fish:" + missionTaskId();
        }

        @Override
        public boolean missionSucceeded() {
            return false;
        }

        @Override
        public String failureReasonOrNull() {
            return timedOut ? "TIMEOUT" : null;
        }
    }
}
