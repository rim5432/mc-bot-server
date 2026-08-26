package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.rescue.RescueMissionFactory;
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
import com.mcbot.mcbotserver.core.reflex.ClimbOutOfPowderSnowRule;
import com.mcbot.mcbotserver.core.reflex.DigOnSuffocationRule;
import com.mcbot.mcbotserver.core.reflex.EngageOnHostileProximityRule;
import com.mcbot.mcbotserver.core.reflex.EscapeLavaRule;
import com.mcbot.mcbotserver.core.reflex.ExtinguishFireRule;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
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
        BindingWorldView view = new BindingWorldView(level, traits,
            () -> body.getInventory().snapshot());
        BindingActor actor = new BindingActor(body);

        SurvivalReflexLayer reflex = new SurvivalReflexLayer(
            new LevelThreatSensor(view,
                () -> poseOf(body), body::getAirSupply,
                body::isInLava, body::getRemainingFireTicks,
                body::getTicksFrozen, body::isInWall,
                () -> suffocationBlockOf(body)));
        reflex.addRule(new FreezeOnLowHealthRule());
        // Air reflex outranks the freeze rule by default (SURFACE
        // _PRIORITY 110 vs FREEZE 100): freezing underwater converts
        // one lethal condition into two.
        reflex.addRule(new SurfaceOnLowAirRule());
        // Lava escape outranks air (LAVA_ESCAPE_PRIORITY 130 vs 110):
        // lava is 4 HP/tick with no interval vs drowning's 2 HP/tick
        // after air exhaustion. ESCAPE handoff (not pure ASCEND): the
        // reflex submits a rescue GotoProcess to the nearest shore
        // cell; the pathing behavior handles ascent + horizontal swim.
        // Replaces the earlier AscendInLethalFluidRule (ASCEND-only
        // floated the body to the surface but never reached shore).
        reflex.addRule(new EscapeLavaRule());
        // Suffocation self-rescue dig (issue 0008 D3, upgraded by
        // issue 0009): 1 HP/tick instant-class, but with dig
        // capability the rescue direction is KNOWN - the eye block -
        // so the rule digs it out instead of freezing (the stopgap
        // FREEZE was superseded). 115 keeps its slot between SURFACE
        // and LAVA per the 0008 F9 triage table. The TASK_PAUSED
        // preemption is still the siren for the unbreakable-wall case
        // the dig cannot answer.
        reflex.addRule(new DigOnSuffocationRule());
        // Fire find-water (issue 0008 D5-revised): fires only when
        // remaining fire damage >= current health (burn-to-death
        // band). Non-lethal fire is left to the route and natural
        // expiry, exactly as the original D5 "sense-only" ruling
        // intended. Priority 105 sits between SURFACE (110) and
        // FREEZE (100): burning to death outranks low-health freeze
        // because freezing does not stop the burn.
        reflex.addRule(new ExtinguishFireRule());
        // Powder-snow freeze climb (issue 0008 F3): the least urgent
        // vital (140-tick freeze budget, 1 HP/40t after). ASCEND holds
        // jump; powder snow is climbable (Entity.isStateClimbable) so
        // the same intent that surfaces a drowning body climbs a
        // freezing one out. Priority 95 sits below FREEZE (100): a
        // low-health freezing body parks for harness triage (47s
        // budget is ample response time); above ENGAGE (90) so a
        // freezing bot climbs instead of continuing a fight.
        reflex.addRule(new ClimbOutOfPowderSnowRule());
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

        // One fresh reflex-owned rescue per ESCAPE submission: the
        // factory reads body state (lava vs fire) and scans for the
        // nearest shore / water-adjacent cell. Returns null when no
        // target is in range; the controller then degrades ESCAPE to
        // the FREEZE hold (park and escalate).
        Supplier<com.mcbot.mcbotserver.api.process.BotProcess>
            rescueFactory = new RescueMissionFactory(view, body);

        BotController controller = new BotController(reflex, arbiter,
            List.of(mover, combat), actor,
            () -> poseOf(body), body::getHealth,
            clockOf(level), events,
            CrashReporter.consoleFallback(), engageFactory,
            rescueFactory);
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
        // Item summary from the live inventory binding (issue 0007 Phase
        // 1): aggregate main-slot counts by item id. Armor and offhand
        // are excluded from the summary map — they are equipment, not
        // transferable stack count.
        var inv = body.getInventory().snapshot();
        Map<String, Integer> items = new LinkedHashMap<>();
        int freeSlots = 0;
        for (var slot : inv.main()) {
            if (!slot.isEmpty()) {
                items.merge(slot.itemId(), slot.count(), Integer::sum);
            } else {
                freeSlots++;
            }
        }
        Map<String, Integer> effects = new LinkedHashMap<>();
        for (MobEffectInstance instance : body.getActiveEffects()) {
            effects.put(BuiltInRegistries.MOB_EFFECT
                    .getKey(instance.getEffect()).toString(),
                instance.getAmplifier());
        }
        // Health bucketed to whole hearts: 1 heart = 2 half-heart
        // units, rounding half up so a fresh bot reads 20.
        return new BotState(poseOf(body), body.getYRot(),
            body.getXRot(), level.dimension().location().getPath(),
            items, inv.selectedSlot(), effects,
            gotoHandler.activeTaskSummary(),
            Math.round(body.getHealth() / 2.0f), freeSlots);
    }

    private static CellPos poseOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(),
            body.getBlockZ());
    }

    /**
     * The solid cell containing the eye while it suffocates - the dig
     * self-rescue's target. Mirrors what vanilla's isInWall checks
     * (the eye's own cell); null while the eye is clear so the board
     * never carries a rescue target without the vital that motivates
     * it.
     *
     * @param body the live carrier; never null
     * @return the eye's cell, or null when the eye is not in a wall
     */
    private static CellPos suffocationBlockOf(BotBodyEntity body) {
        if (!body.isInWall()) {
            return null;
        }
        var eyeCell = net.minecraft.core.BlockPos.containing(
            body.getEyePosition());
        return new CellPos(eyeCell.getX(), eyeCell.getY(),
            eyeCell.getZ());
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
