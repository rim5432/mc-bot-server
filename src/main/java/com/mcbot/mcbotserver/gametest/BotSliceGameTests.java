package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingActor;
import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.tick.CrashReporter;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;

/**
 * Stage-1 acceptance, in-engine: the three slice scenarios from the
 * workplan gate. Each test builds a full pipeline around one
 * BotBodyEntity and drives it every gametest tick through the same
 * onTick entry the server wiring uses.
 *
 * <p>Contract: see workplan Stage 1 gate (walks-to-block /
 * recovers-when-shoved / fails-cleanly-unwalkable) and boundaries.md
 * decisions 16-18.
 *
 * <p>Template: gameteststructures/empty16x8x16.snbt - an empty box the
 * tests pave themselves. MC 1.20.1 ships no empty template; the SNBT
 * fallback path in StructureUtils resolves this file relative to the
 * gameTestServer working directory.
 *
 * <p>Assertion style: gametests live in the main source set where JUnit
 * is not on the classpath, so checks throw plain IllegalStateException -
 * GameTestSequence retries a thrown waitUntil each tick and treats a
 * thrown execute as test failure, which is exactly the contract needed.
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class BotSliceGameTests {

    private static final int FLOOR_Y = 0;
    private static final int WALK_Y = FLOOR_Y + 1;
    private static final int TIMEOUT = 400;
    private static final int MISSION_BUDGET = 350;

    private BotSliceGameTests() {
    }

    /** Everything one scenario needs, pre-wired around one body. */
    private record Rig(BotBodyEntity body, BindingWorldView view,
                       InMemoryEventQueue events,
                       TaskArbiter arbiter,
                       BotController controller) {
    }

    /**
     * Scenario 1: walks to a block on flat ground and reports success.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void walksToBlock(GameTestHelper helper) {
        Rig rig = rig(helper, new BlockPos(3, WALK_Y, 8));
        CellPos goalCell = localToCell(helper,
            new BlockPos(12, WALK_Y, 8));
        GotoProcess mission = submitGoto(rig, goalCell);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "waiting for arrival")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.isActive(),
                    "mission must retire after arrival");
                check(mission.missionSucceeded(), "must be a success");
                assertEventSeen(rig.events(), EventKind.TASK_COMPLETED);
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 2: shoved mid-path, the bot re-walks to the same goal.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void recoversWhenShoved(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, WALK_Y, 8);
        Rig rig = rig(helper, start);
        CellPos goalCell = localToCell(helper,
            new BlockPos(13, WALK_Y, 8));
        GotoProcess mission = submitGoto(rig, goalCell);

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig,
                () -> check(rig.body().getBlockX()
                    - helper.absolutePos(start).getX() >= 4,
                    "waiting to pass mid-point")))
            .thenExecute(() -> {
                var abs = helper.absolutePos(start);
                rig.body().teleportTo(abs.getX() + 0.5, abs.getY(),
                    abs.getZ() + 0.5);
                rig.body().setDeltaMovement(
                    net.minecraft.world.phys.Vec3.ZERO);
            })
            .thenWaitUntil(driveUntil(rig,
                () -> check(reached(rig.body(), goalCell),
                    "still re-walking after the shove")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                checkEquals(goalCell, poseOf(rig.body()),
                    "must still arrive after the shove");
                rig.body().discard();
            })
            .thenSucceed();
    }

    /**
     * Scenario 3: unreachable goal fails cleanly with a reason - no
     * hang, no silent stall.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void failsCleanlyWhenUnwalkable(GameTestHelper helper) {
        BlockPos start = new BlockPos(3, WALK_Y, 8);
        Rig rig = rig(helper, start);
        GotoProcess mission = submitGoto(rig,
            localToCell(helper, new BlockPos(13, WALK_Y, 8)));

        // Corridor rails plus a sealed end wall: the target cell is
        // unreachable, so the mover stalls against the wall face and
        // the displacement fuse must trip into a clean failure.
        for (int x = 3; x <= 14; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y + y, 7),
                    Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, FLOOR_Y + y, 9),
                    Blocks.SMOOTH_STONE);
            }
        }
        for (int z = 6; z <= 10; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(11, FLOOR_Y + y, z),
                    Blocks.SMOOTH_STONE);
            }
        }

        helper.startSequence()
            .thenWaitUntil(driveUntil(rig, () ->
                check(!mission.isActive(), "waiting for the fuse")))
            .thenExecuteFor(3, driveOnly(rig))
            .thenExecuteAfter(0, () -> {
                check(!mission.missionSucceeded(), "cannot succeed");
                checkEquals("STUCK", mission.failureReasonOrNull(),
                    "a walled-in walk must fail as STUCK");
                assertEventSeen(rig.events(), EventKind.TASK_FAILED);
                rig.body().discard();
            })
            .thenSucceed();
    }

    // ===== harness =====

    /**
     * Wait-until body: drives the pipeline every gametest tick, then
     * evaluates the keep-throwing condition - a throw means "not yet".
     */
    private static Runnable driveUntil(Rig rig, Runnable keepThrowing) {
        return () -> {
            rig.controller().onTick(rig.view());
            keepThrowing.run();
        };
    }

    /**
     * Keep-alive for the ticks between a waitUntil passing and the
     * assertions: the pipeline must keep ticking or the arbiter never
     * retires the mission and the transition event never fires (this
     * exact race cost three debug rounds).
     */
    private static Runnable driveOnly(Rig rig) {
        return () -> rig.controller().onTick(rig.view());
    }

    private static GotoProcess submitGoto(Rig rig, CellPos target) {
        String taskId = "gt-" + Integer.toHexString(
            System.identityHashCode(target));
        GotoProcess mission = new GotoProcess(taskId,
            new GoalBlock(target), 50, MISSION_BUDGET);
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    private static Rig rig(GameTestHelper helper, BlockPos spawnLocal) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z),
                    Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, FLOOR_Y + 1, z),
                    Blocks.AIR);
            }
        }

        var type = ForgeRegistries.ENTITY_TYPES.getValue(
            new ResourceLocation(McBotServer.MODID, "bot_body"));
        if (type == null) {
            helper.fail("bot_body entity type not registered");
        }
        BotBodyEntity body = (BotBodyEntity) type.create(level);
        if (body == null) {
            helper.fail("bot_body creation failed");
        }
        var abs = helper.absolutePos(spawnLocal);
        body.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f,
            0f);
        level.addFreshEntity(body);

        InMemoryEventQueue events = new InMemoryEventQueue(
            () -> level.getDayTime() / 24000L,
            () -> level.getDayTime() % 24000L);
        TaskArbiter arbiter = new TaskArbiter();
        BindingWorldView view = new BindingWorldView(level);
        BindingActor actor = new BindingActor(body);

        SurvivalReflexLayer reflex = new SurvivalReflexLayer(
            (world, board) -> board.botHealth = body.getHealth());
        reflex.addRule(new FreezeOnLowHealthRule());

        Behavior mover = new PathingBehavior("mover",
            () -> finePoseOf(body));

        BotController controller = new BotController(reflex, arbiter,
            List.of(mover), actor,
            () -> poseOf(body), body::getHealth,
            clockOf(level), events, CrashReporter.consoleFallback());

        return new Rig(body, view, events, arbiter, controller);
    }

    private static void assertEventSeen(InMemoryEventQueue events,
                                        String kind) {
        List<String> kinds = events.statusSnapshot(0).events().stream()
            .map(BotEvent::kind).toList();
        check(kinds.contains(kind),
            "expected " + kind + " in stream, got " + kinds);
    }

    /**
     * Wait-phase contract: GameTestSequence.tickAndContinue retries a
     * step only while it throws GameTestAssertException, so every
     * condition here throws exactly that - retry in thenWaitUntil,
     * clean failure inside thenExecute.
     */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private static void checkEquals(Object expected, Object actual,
                                    String message) {
        if (!Objects.equals(expected, actual)) {
            throw new GameTestAssertException(message + ": expected="
                + expected + " actual=" + actual);
        }
    }

    private static CellPos poseOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(),
            body.getBlockZ());
    }

    private static com.mcbot.mcbotserver.api.types.Vec3 finePoseOf(
            BotBodyEntity body) {
        return new com.mcbot.mcbotserver.api.types.Vec3(body.getX(),
            body.getY(), body.getZ());
    }

    /**
     * Goal-predicate echo for wait conditions; the pipeline itself
     * still owns SUCCESS via the same predicate.
     */
    private static boolean reached(BotBodyEntity body, CellPos goal) {
        return goal.equals(poseOf(body));
    }

    private static CellPos localToCell(GameTestHelper helper,
                                       BlockPos local) {
        BlockPos abs = helper.absolutePos(local);
        return new CellPos(abs.getX(), abs.getY(), abs.getZ());
    }

    private static BotController.GameClock clockOf(ServerLevel level) {
        return new BotController.GameClock() {
            @Override
            public long day() {
                return level.getDayTime() / 24000L;
            }

            @Override
            public long timeOfDayTicks() {
                return level.getDayTime() % 24000L;
            }
        };
    }
}
