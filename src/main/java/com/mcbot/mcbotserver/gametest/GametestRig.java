package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingActor;
import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.behavior.CombatBehavior;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.tick.CrashReporter;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;

/**
 * Shared in-engine harness for the gametest scenarios: one
 * pre-wired pipeline (body, view, arbiter, controller) per test,
 * plus the assertion and coordinate helpers every scenario needs.
 *
 * <p>Contract: see workplan Stage 1 gate. Each scenario drives the
 * same {@code onTick} entry the server wiring uses - no test-private
 * shortcuts into the pipeline.
 *
 * <p>Assertion style: gametests live in the main source set where
 * JUnit is not on the classpath, so checks throw plain
 * GameTestAssertException - GameTestSequence retries a thrown
 * waitUntil each tick and treats a thrown execute as test failure.
 */
final class GametestRig {

    /** Paved floor level inside the empty template. */
    static final int FLOOR_Y = 0;

    /** Body cell height while standing on the paved floor. */
    static final int WALK_Y = FLOOR_Y + 1;

    private static final int DEFAULT_MISSION_BUDGET = 350;

    private GametestRig() {
    }

    /** Everything one scenario needs, pre-wired around one body. */
    record Rig(BotBodyEntity body, BindingWorldView view,
               InMemoryEventQueue events,
               TaskArbiter arbiter,
               BotController controller) {
    }

    /**
     * Paves the 16x16 floor, spawns one body, wires the full
     * pipeline around it.
     *
     * @param helper     the running gametest; never null
     * @param spawnLocal template-local spawn cell; never null
     * @return the wired rig; never null
     */
    static Rig rig(GameTestHelper helper, BlockPos spawnLocal) {
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
        // Same baseline trait floor as the live wiring: water must
        // read liquid or the swim vocabulary never engages.
        BindingWorldView view = new BindingWorldView(level,
            new com.mcbot.mcbotserver.core.world.MapBlockTraitsRegistry()
                .register("minecraft:water",
                    com.mcbot.mcbotserver.api.world.BlockTraits
                        .liquidOnly())
                .register("minecraft:lava",
                    com.mcbot.mcbotserver.api.world.BlockTraits
                        .dangerousLiquid())
                .seal());
        BindingActor actor = new BindingActor(body);

        SurvivalReflexLayer reflex = new SurvivalReflexLayer(
            (world, board) -> {
                board.botHealth = body.getHealth();
                board.airSupply = body.getAirSupply();
            });
        reflex.addRule(new FreezeOnLowHealthRule());
        reflex.addRule(new SurfaceOnLowAirRule());

        Behavior mover = new PathingBehavior("mover",
            () -> finePositionOf(body),
            () -> body.onGround(),
            com.mcbot.mcbotserver.core.pathing.BasicMoves::from,
            new com.mcbot.mcbotserver.core.pathing.PlanWorker());
        Behavior combat = new CombatBehavior("combat",
            () -> finePositionOf(body));

        BotController controller = new BotController(reflex, arbiter,
            List.of(mover, combat), actor,
            () -> positionOf(body), body::getHealth,
            clockOf(level), events, CrashReporter.consoleFallback());

        return new Rig(body, view, events, arbiter, controller);
    }

    /**
     * Wait-until body: drives the pipeline every gametest tick, then
     * evaluates the keep-throwing condition - a throw means "not yet".
     */
    static Runnable driveUntil(Rig rig, Runnable keepThrowing) {
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
    static Runnable driveOnly(Rig rig) {
        return () -> rig.controller().onTick(rig.view());
    }

    /**
     * Registers and takes control of a goto mission with the default
     * tick budget.
     *
     * @param rig    the wired rig; never null
     * @param target arrival cell; never null
     * @return the active mission
     */
    static GotoProcess submitGoto(Rig rig, CellPos target) {
        return submitGoto(rig, target, DEFAULT_MISSION_BUDGET);
    }

    /**
     * Registers and takes control of a goto mission with an explicit
     * tick budget - long corridors need more than the default.
     *
     * @param rig    the wired rig; never null
     * @param target arrival cell; never null
     * @param budget mission-side hard cutoff in ticks; positive
     * @return the active mission
     */
    static GotoProcess submitGoto(Rig rig, CellPos target, int budget) {
        String taskId = "gt-" + Integer.toHexString(
            System.identityHashCode(target));
        GotoProcess mission = new GotoProcess(taskId,
            new GoalBlock(target), 50, budget);
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    static void checkEquals(Object expected, Object actual,
                            String message) {
        if (!Objects.equals(expected, actual)) {
            throw new GameTestAssertException(message + ": expected="
                + expected + " actual=" + actual);
        }
    }

    static CellPos positionOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(),
            body.getBlockZ());
    }

    static Vec3 finePositionOf(BotBodyEntity body) {
        return new Vec3(body.getX(), body.getY(), body.getZ());
    }

    /**
     * Goal-predicate echo for wait conditions; the pipeline itself
     * still owns SUCCESS via the same predicate.
     */
    static boolean reached(BotBodyEntity body, CellPos goal) {
        return goal.equals(positionOf(body));
    }

    static CellPos localToCell(GameTestHelper helper,
                               BlockPos local) {
        BlockPos abs = helper.absolutePos(local);
        return new CellPos(abs.getX(), abs.getY(), abs.getZ());
    }

    static BotController.GameClock clockOf(ServerLevel level) {
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
