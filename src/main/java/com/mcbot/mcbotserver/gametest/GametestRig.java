package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingActor;
import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.types.Vec3;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.state.ChangeDetectingStateChannel;
import com.mcbot.mcbotserver.core.tick.BotController;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Shared in-engine harness for the gametest scenarios: one
 * pre-wired pipeline per test, plus the assertion and coordinate
 * helpers every scenario needs.
 *
 * <p>Contract: see workplan Stage 1 gate. The pipeline is built by
 * {@link BotAssembly#assemble} - the exact factory the
 * {@code /botspawn} server wiring uses - so a rig green means the
 * production wiring shape, not a hand-copied lookalike of it. The
 * per-tick drive ({@link #driveTick}) delegates to
 * {@link BotAssembly#tickOnce}, the same method the server listener
 * calls - the lockstep is structural, not a convention to maintain
 * by hand.
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

    /** Standard scenario annotation budget (workplan slice default). */
    static final int TIMEOUT = 400;

    /**
     * Mission-side goto budget for long corridors (deep pool leg).
     * The DEFAULT_MISSION_BUDGET covers plain walks without this
     * needing an explicit override.
     */
    static final int MISSION_BUDGET = 350;

    private static final int DEFAULT_MISSION_BUDGET = 350;

    private GametestRig() {}

    /**
     * Everything one scenario needs, wired by the shared factory. One
     * field - the assembled pipeline - with convenience accessors so
     * scenarios read {@code rig.body()} etc. without knowing the
     * record shape; the drive methods reach the whole pipeline through
     * {@code tickOnce}.
     */
    static final class Rig {
        private final BotAssembly.Assembled assembled;

        private Rig(BotAssembly.Assembled assembled) {
            this.assembled = assembled;
        }

        BotBodyEntity body() {
            return assembled.body();
        }

        BindingWorldView view() {
            return assembled.view();
        }

        BindingActor actor() {
            return assembled.actor();
        }

        InMemoryEventQueue events() {
            return assembled.events();
        }

        TaskArbiter arbiter() {
            return assembled.arbiter();
        }

        BotController controller() {
            return assembled.controller();
        }

        GotoCommandHandler gotoHandler() {
            return assembled.gotoHandler();
        }

        ChangeDetectingStateChannel state() {
            return assembled.state();
        }
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
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.SMOOTH_STONE);
                helper.setBlock(new BlockPos(x, FLOOR_Y + 1, z), Blocks.AIR);
            }
        }

        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(McBotServer.MODID, "bot_body"));
        if (type == null) {
            helper.fail("bot_body entity type not registered");
        }
        BotBodyEntity body = (BotBodyEntity) type.create(level);
        if (body == null) {
            helper.fail("bot_body creation failed");
        }
        var abs = helper.absolutePos(spawnLocal);
        body.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(body);

        BotAssembly.Assembled a = BotAssembly.assemble(level, body);
        return new Rig(a);
    }

    /**
     * One production-shaped tick: delegates to
     * {@link BotAssembly#tickOnce}, the exact call order the server
     * listener runs. Scenarios must tick through this - a bare
     * {@code controller.onTick} skips the harness seams and reads
     * green where production would not.
     *
     * @param rig the wired rig; never null
     */
    static void driveTick(Rig rig) {
        BotAssembly.tickOnce(rig.assembled);
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
     * Spawns one hostile mob at the local cell for combat scenarios:
     * centered on the cell (+0.5), NoAi left to the caller. Every
     * spawn rides this helper so the centering and creation-check
     * conventions stay pinned in one place.
     *
     * @param helper the gametest helper; never null
     * @param type   the entity type to create; never null
     * @param local  structure-local spawn cell; never null
     * @param <T>    the spawned mob type
     * @return the added entity; never null
     */
    static <T extends net.minecraft.world.entity.monster.Monster> T spawnHostile(
            GameTestHelper helper, net.minecraft.world.entity.EntityType<T> type, BlockPos local) {
        T mob = type.create(helper.getLevel());
        check(mob != null, "hostile creation failed: " + type);
        var abs = helper.absolutePos(local);
        mob.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(mob);
        return mob;
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
        String taskId = "gt-" + Integer.toHexString(System.identityHashCode(target));
        GotoProcess mission = new GotoProcess(taskId, new GoalBlock(target), 50, budget);
        rig.arbiter().register(mission);
        rig.arbiter().requestControl(mission);
        return mission;
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    static void checkEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new GameTestAssertException(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static CellPos positionOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(), body.getBlockZ());
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

    static CellPos localToCell(GameTestHelper helper, BlockPos local) {
        BlockPos abs = helper.absolutePos(local);
        return new CellPos(abs.getX(), abs.getY(), abs.getZ());
    }

    /**
     * World position to structure-local coordinates. Vanilla's
     * {@code relativePos} is NOT the inverse of {@code absolutePos}
     * for rotation-NONE tests (it applies a 180-degree transform and
     * returns mirrored negatives) - subtracting the origin is. Every
     * scenario that combines live body coordinates with
     * {@code helper.setBlock}/{@code getBlockState} must convert
     * through here; feeding world coordinates straight in places
     * blocks outside the box and the scenario silently tests nothing
     * (found live in the suffocation scenario's seal).
     *
     * @param helper  the running gametest; never null
     * @param worldPos position in world coordinates; never null
     * @return the same position in structure-local coordinates
     */
    static BlockPos toLocal(GameTestHelper helper, BlockPos worldPos) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new BlockPos(
                worldPos.getX() - origin.getX(), worldPos.getY() - origin.getY(), worldPos.getZ() - origin.getZ());
    }

    /**
     * Total count of one item kind across the whole binding container
     * (all 41 slots).
     *
     * @param rig  the wired rig; never null
     * @param item the kind to sum; never null
     * @return total stack-count across every container slot
     */
    static int countItems(Rig rig, net.minecraft.world.item.Item item) {
        var container = rig.body().getInventory().container();
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Asserts the given event kind already appeared on the stream;
     * usable inside wait-until conditions and terminal executes.
     *
     * @param events the rig's event stream; never null
     * @param kind   registered event-kind string; never null
     */
    static void assertEventSeen(InMemoryEventQueue events, String kind) {
        List<String> kinds =
                events.statusSnapshot(0).events().stream().map(BotEvent::kind).toList();
        check(kinds.contains(kind), "expected " + kind + " in stream, got " + kinds);
    }

    /**
     * Fills a water pool over the test floor across an inclusive x
     * lane: {@code waterDepth} water layers topped by smooth stone.
     * Shared terrain carve for hazard and locomotion scenarios.
     */
    public static void fillPool(
            net.minecraft.gametest.framework.GameTestHelper helper, int xFrom, int xToInclusive, int waterDepth) {
        for (int x = xFrom; x <= xToInclusive; x++) {
            for (int z = 0; z < 16; z++) {
                for (int d = 0; d < waterDepth; d++) {
                    helper.setBlock(new BlockPos(x, FLOOR_Y - d, z), Blocks.WATER);
                }
                helper.setBlock(new BlockPos(x, FLOOR_Y - waterDepth, z), Blocks.SMOOTH_STONE);
            }
        }
    }
}
