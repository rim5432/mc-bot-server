package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.state.ChangeDetectingStateChannel;
import com.mcbot.mcbotserver.core.tick.BotController;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

/**
 * Shared in-engine harness for the gametest scenarios: one
 * pre-wired pipeline per test, plus the assertion and coordinate
 * helpers every scenario needs.
 *
 * <p>Contract: see workplan Stage 1 gate. The pipeline is built by
 * {@link BotAssembly#assemble} - the exact factory the
 * {@code /botspawn} server wiring uses - so a rig green means the
 * production wiring shape, not a hand-copied lookalike of it. The
 * per-tick drive ({@link #driveTick}) mirrors the server listener's
 * tick order (goto handler, state pull-through, lava flag, onTick);
 * keep the two in lockstep when either changes.
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

    /** Everything one scenario needs, wired by the shared factory. */
    record Rig(BotBodyEntity body, BindingWorldView view,
               InMemoryEventQueue events,
               TaskArbiter arbiter,
               BotController controller,
               GotoCommandHandler gotoHandler,
               ChangeDetectingStateChannel state) {
    }

    /**
     * Paves the 16x16 floor, spawns one body, wires the full
     * production pipeline around it via the shared assembly factory.
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

        BotAssembly.Assembled a = BotAssembly.assemble(level, body);
        return new Rig(a.body(), a.view(), a.events(), a.arbiter(),
            a.controller(), a.gotoHandler(), a.state());
    }

    /**
     * One production-shaped tick: the same call order the server
     * listener runs (goto handler, state pull-through, lava flag,
     * pipeline). Scenarios must tick through this - a bare
     * {@code controller.onTick} skips the harness seams and reads
     * green where production would not.
     *
     * @param rig the wired rig; never null
     */
    static void driveTick(Rig rig) {
        rig.gotoHandler().tick();
        rig.state().current();
        // Feed the crashed-state flags before the pipeline runs:
        // MinimalReflex (ADR-0005 D3) reads these to decide whether
        // to jump (lava) or ascend (low air). The normal reflex layer
        // derives fluid/vital state from ThreatBlackboard sensors
        // instead; these setters are the crashed-state parallel only.
        rig.controller().setInLethalFluid(rig.body().isInLava());
        rig.controller().setAirSupply(rig.body().getAirSupply());
        rig.controller().onTick(rig.view());
    }

    /**
     * Wait-until body: drives the pipeline every gametest tick, then
     * evaluates the keep-throwing condition - a throw means "not yet".
     */
    static Runnable driveUntil(Rig rig, Runnable keepThrowing) {
        return () -> {
            driveTick(rig);
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
        return () -> driveTick(rig);
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
}
