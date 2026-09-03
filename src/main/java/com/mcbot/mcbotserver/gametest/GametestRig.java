package com.mcbot.mcbotserver.gametest;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.BindingActor;
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
// The rig is the scenario library's shared helper surface: one
// method per assertion/coordinate/drive helper the scenario classes
// reach for - helper breadth is its charter, not god-class drift
// (same ruling shape as MineProcess).
@SuppressWarnings("PMD.TooManyMethods")
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
    /**
     * The settle tick count: how long the pipeline runs after the
     * scenario's action before assertions read the world. Three ticks
     * lets one-tick intents flush, the arbiter pick a winner, and the
     * transition events land. Gauntlet's two-tick sites are
     * deliberate (mid-corridor pacing), not drift.
     */
    static final int SETTLE_TICKS = 3;

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
        GametestAsserts.check(mob != null, "hostile creation failed: " + type);
        var abs = helper.absolutePos(local);
        mob.moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    /**
     * Spawns one non-hostile mob at the local cell for interaction
     * scenarios: the same centering and creation-check conventions
     * as {@link #spawnHostile}, widened past the Monster bound so
     * tameable animals ride the same pin.
     *
     * @param helper the gametest helper; never null
     * @param type   the entity type to create; never null
     * @param local  structure-local spawn cell; never null
     * @param <T>    the spawned mob type
     * @return the added entity; never null
     */
    static <T extends net.minecraft.world.entity.Mob> T spawnMob(
            GameTestHelper helper, net.minecraft.world.entity.EntityType<T> type, BlockPos local) {
        T mob = type.create(helper.getLevel());
        GametestAsserts.check(mob != null, "mob creation failed: " + type);
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

    /**
     * Absolute world coordinates to the cell that contains them.
     *
     * @param abs absolute block position; never null
     * @return the owning cell; never null
     */
    static CellPos cellOf(BlockPos abs) {
        return new CellPos(abs.getX(), abs.getY(), abs.getZ());
    }

    static CellPos localToCell(GameTestHelper helper, BlockPos local) {
        return cellOf(helper.absolutePos(local));
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
     * Rigs the ranged loadout into the body's hotbar: bow in slot 0,
     * 64 arrows in slot 1 - the shared opening rig of the standoff
     * and ranged-routing scenarios.
     *
     * @param rig the wired rig; never null
     */
    static void giveBowAndArrows(Rig rig) {
        var container = rig.body().getInventory().container();
        container.setItem(0, new ItemStack(net.minecraft.world.item.Items.BOW));
        container.setItem(1, new ItemStack(net.minecraft.world.item.Items.ARROW, 64));
    }

    /**
     * Primes the body's FoodData to an exact scenario state: food
     * level, saturation, and optional pre-charged exhaustion - the
     * shared opening rig of every hunger/eating scenario.
     *
     * @param body       the body under test; never null
     * @param foodLevel  target food level, 0..20
     * @param saturation target saturation, 0..foodLevel
     * @param exhaustion exhaustion to pre-charge; 0 to skip
     */
    static void primeFood(BotBodyEntity body, int foodLevel, float saturation, float exhaustion) {
        net.minecraft.world.food.FoodData food = body.getFoodData();
        food.setFoodLevel(foodLevel);
        food.setSaturation(saturation);
        if (exhaustion > 0.0f) {
            food.addExhaustion(exhaustion);
        }
    }

    /**
     * Fills a liquid pool over the test floor across an inclusive
     * x/z rectangle: {@code depth} fluid layers downward from the
     * floor, topped by smooth stone. Shared terrain carve for hazard
     * and locomotion scenarios - full lanes pass z 0..15, pools bound
     * both axes.
     */
    public static void fillPool(
            net.minecraft.gametest.framework.GameTestHelper helper,
            int xFrom,
            int xToInclusive,
            int zFrom,
            int zToInclusive,
            int depth,
            net.minecraft.world.level.block.Block fluid) {
        for (int x = xFrom; x <= xToInclusive; x++) {
            for (int z = zFrom; z <= zToInclusive; z++) {
                for (int d = 0; d < depth; d++) {
                    helper.setBlock(new BlockPos(x, FLOOR_Y - d, z), fluid);
                }
                helper.setBlock(new BlockPos(x, FLOOR_Y - depth, z), Blocks.SMOOTH_STONE);
            }
        }
    }

    /**
     * Discards every entity inside this structure's 16x8x16 footprint
     * except the kept ones. Pooled runs share one level, and leaked
     * or stray entities walking through a neighbouring structure have
     * absorbed aimed shots and tripped proximity triggers (the bowtap
     * clean-miss class, the rangedBot stray-threat class - ledger 46
     * cross-contamination family). GameTestHelper.getBounds is
     * private in 1.20.1, so the footprint is hand-built from the
     * template origin exactly like ProductionWiringGameTests.
     *
     * @param helper owning scenario; never null
     * @param keep  entities that survive the sweep (the rig's own
     *              body and scenario targets); never null
     */
    static void sweepForeignEntities(
            net.minecraft.gametest.framework.GameTestHelper helper, net.minecraft.world.entity.Entity... keep) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        net.minecraft.world.phys.AABB structureBox = new net.minecraft.world.phys.AABB(
                origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 8, origin.getZ() + 16);
        java.util.Set<Integer> keepIds = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity e : keep) {
            keepIds.add(e.getId());
        }
        for (net.minecraft.world.entity.Entity e :
                helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class, structureBox)) {
            if (!keepIds.contains(e.getId())) {
                e.discard();
            }
        }
    }
}
