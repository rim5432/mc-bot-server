package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import com.mcbot.mcbotserver.core.behavior.CombatBehavior;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.DefendProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.AscendInLethalFluidRule;
import com.mcbot.mcbotserver.core.reflex.EngageOnHostileProximityRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.SurfaceOnLowAirRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.state.ChangeDetectingStateChannel;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.tick.CrashReporter;
import com.mcbot.mcbotserver.core.world.MapBlockTraitsRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The single assembly point for one bot's full pipeline: perception,
 * reflex layer with the production rule set, behaviors, controller,
 * and the boundary-D channel triple (command bus, goto handler,
 * state channel).
 *
 * <p>Why one class: the gametest rig and the {@code /botspawn} server
 * wiring used to duplicate the same {@code new} chain by hand, and the
 * copies drifted - the rig lacked the engage reflex, the engage
 * mission factory and the pre-tick lava flag, so production-only seams
 * had no in-engine coverage and rig greens said nothing about the
 * wiring they resembled. Both consumers now build through this
 * factory; a wiring change lands in exactly one place.
 *
 * <p>Contract: see ADR-0004 D1/D2 (tick order, four-channel actor)
 * and boundaries.md section D (submit/event/state channels). The tick
 * ORDER around the assembled controller is the consumer's job - the
 * server listener and the gametest drive both run
 * {@code gotoHandler.tick() -> state.current() ->
 * controller.setInLethalFluid(body.isInLava()) ->
 * controller.onTick(view)}; keep the two in lockstep.
 *
 * <p>Implementation note: adapter layer by necessity (server level,
 * entity, effect reads); everything it assembles from {@code core/}
 * stays MC-free.
 */
// contract: see boundaries.md section D (channel triple) and ADR-0004
//            D2 (claim surface per channel)
public final class BotAssembly {

    /**
     * Tick budget for one reflex-owned engage mission: long enough for
     * a melee fight plus its grace window, short enough that an
     * unwinnable scrap cannot pin the body - the mission's own
     * leash/escape verdicts usually land first. Public because
     * gametest scenarios size their timeoutTicks against it.
     */
    public static final long ENGAGE_MISSION_TIMEOUT_TICKS = 600L;

    private BotAssembly() {
    }

    /** Everything assembled around one body, ready to tick. */
    public record Assembled(BotBodyEntity body,
                            BindingWorldView view,
                            BindingActor actor,
                            InMemoryEventQueue events,
                            TaskArbiter arbiter,
                            SurvivalReflexLayer reflex,
                            BotController controller,
                            CommandBus bus,
                            GotoCommandHandler gotoHandler,
                            ChangeDetectingStateChannel state) {
    }

    /**
     * Wires the full production pipeline around one spawned body.
     *
     * @param level the body's server level; never null
     * @param body  the spawned carrier; never null
     * @return the assembled pipeline; never null
     */
    public static Assembled assemble(ServerLevel level, BotBodyEntity body) {
        InMemoryEventQueue events = new InMemoryEventQueue(
            () -> level.getDayTime() / 24000L,
            () -> level.getDayTime() % 24000L);
        TaskArbiter arbiter = new TaskArbiter();
        // Baseline trait annotations the swim vocabulary cannot work
        // without. Code-level floor for now; the datapack JSON pipeline
        // (decision 10) supersedes this when block traits get their
        // reload listener - tracked as a workplan follow-up.
        BlockTraitsRegistry traits =
            new MapBlockTraitsRegistry()
                .register("minecraft:water",
                    BlockTraits.liquidOnly())
                .register("minecraft:lava",
                    BlockTraits.dangerousLiquid())
                .seal();
        BindingWorldView view = new BindingWorldView(level, traits);
        BindingActor actor = new BindingActor(body);

        SurvivalReflexLayer reflex = new SurvivalReflexLayer(
            new LevelThreatSensor(view,
                () -> poseOf(body), body::getAirSupply,
                body::isInLava, body::getRemainingFireTicks,
                body::getTicksFrozen, body::isInWall));
        reflex.addRule(new FreezeOnLowHealthRule());
        // Air reflex outranks the freeze rule by default (SURFACE
        // _PRIORITY 110 vs FREEZE 100): freezing underwater converts
        // one lethal condition into two.
        reflex.addRule(new SurfaceOnLowAirRule());
        // Lava reflex outranks air (LAVA_PRIORITY 130 vs 110): lava is
        // 4 HP/tick with no interval vs drowning's 2 HP/tick after
        // air exhaustion. The board.inLethalFluid field was dead
        // since ADR-0003 (issue 0008 F2); this rule + the vitals
        // sensor pass close the live-pipeline lava blind spot.
        reflex.addRule(new AscendInLethalFluidRule());
        // Suffocation halt (issue 0008 D3): 1 HP/tick instant-class,
        // and the rescue direction is unknown - a reflex guessing a
        // direction can push the body deeper into the glitch, so
        // freezing is strictly correct. 115 sits between SURFACE and
        // LAVA per the 0008 F9 triage table. On this codebase inWall
        // is almost always a pathing bug - the reflex is also the
        // siren; the fix lives upstream.
        reflex.addRule(new FreezeOnSuffocationRule());
        // Idle-combat reflex (2026-08-24 night-cave death): engages a
        // hostile that closes to melee range even when no mission is
        // running. Sits BELOW the survival holds: at three health
        // points the right reflex is to stop, not to start a fight.
        reflex.addRule(new EngageOnHostileProximityRule());

        Behavior mover = new PathingBehavior("mover",
            () -> finePoseOf(body),
            () -> body.onGround(),
            com.mcbot.mcbotserver.core.pathing.BasicMoves::from,
            new com.mcbot.mcbotserver.core.pathing.PlanWorker());
        // CombatBehavior must be seated here too: without it the
        // engage reflex submits defend missions whose Attack overrides
        // nobody executes - the production gap behind the idle
        // night-cave death.
        Behavior combat = new CombatBehavior("combat",
            () -> finePoseOf(body));

        // One fresh reflex-owned defend per engage submission; the
        // assembly owns identity, budget and type sets.
        var engageCounter = new AtomicInteger();
        Supplier<com.mcbot.mcbotserver.api.process.BotProcess>
            engageFactory = () -> new DefendProcess(
                "reflex-engage-" + engageCounter.incrementAndGet(),
                PriorityBands.DEFEND_PRIORITY,
                ENGAGE_MISSION_TIMEOUT_TICKS,
                () -> poseOf(body),
                LevelThreatSensor.hostileTypes(),
                LevelThreatSensor.rangedTypes());

        BotController controller = new BotController(reflex, arbiter,
            List.of(mover, combat), actor,
            () -> poseOf(body), body::getHealth,
            clockOf(level), events,
            CrashReporter.consoleFallback(), engageFactory);
        CommandBus bus = new CommandBus(events);
        GotoCommandHandler gotoHandler = new GotoCommandHandler(
            arbiter, events,
            () -> level.getDayTime() / 24000L,
            () -> level.getDayTime() % 24000L);
        gotoHandler.attach(bus);

        ChangeDetectingStateChannel state =
            new ChangeDetectingStateChannel(
                () -> snapshotOf(body, gotoHandler, level),
                events,
                () -> level.getDayTime() / 24000L,
                () -> level.getDayTime() % 24000L);

        return new Assembled(body, view, actor, events, arbiter, reflex,
            controller, bus, gotoHandler, state);
    }

    /**
     * Captures the boundary-D state snapshot from the live body.
     *
     * @param body        the spawned body; never null
     * @param gotoHandler workload owner for the task summary; never
     *                    null
     * @param level       the body's level; never null
     * @return fresh snapshot; never null
     */
    private static BotState snapshotOf(BotBodyEntity body,
                                       GotoCommandHandler gotoHandler,
                                       ServerLevel level) {
        // Item fields stay empty until an inventory mechanic exists -
        // inventory skills are a function-map DEFERRED row, and an
        // honest empty map beats a fake loadout.
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> effects = new LinkedHashMap<>();
        for (MobEffectInstance instance : body.getActiveEffects()) {
            effects.put(BuiltInRegistries.MOB_EFFECT
                    .getKey(instance.getEffect()).toString(),
                instance.getAmplifier());
        }
        return new BotState(poseOf(body), body.getYRot(),
            body.getXRot(), level.dimension().location().getPath(),
            items, 0, effects, gotoHandler.activeTaskSummary());
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
