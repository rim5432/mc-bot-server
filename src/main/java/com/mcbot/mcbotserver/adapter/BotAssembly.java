package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.rescue.RescueMissionFactory;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.BlockTraits;
import com.mcbot.mcbotserver.api.world.BlockTraitsRegistry;
import com.mcbot.mcbotserver.core.behavior.CombatBehavior;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.command.AttackCommandHandler;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.DigCommandHandler;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.command.MineCommandHandler;
import com.mcbot.mcbotserver.core.command.TameCommandHandler;
import com.mcbot.mcbotserver.core.command.VerbTaskHandler;
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
import com.mcbot.mcbotserver.core.tick.DigClaimBehavior;
import com.mcbot.mcbotserver.core.tick.ReflexClaimInjector;
import com.mcbot.mcbotserver.core.tick.ToolSelector;
import com.mcbot.mcbotserver.core.world.MapBlockTraitsRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

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
 * and boundaries.md section D (submit/event/state channels). The
 * tick ORDER around the assembled controller is owned here too:
 * both the server listener and the gametest drive call
 * {@link #tickOnce}, so the lockstep this factory promises is
 * structural. The order used to be a convention restated in two
 * consumers and it drifted - the rig ticked only the goto handler
 * and read green where production would not.
 *
 * <p>Implementation note: adapter layer by necessity (server level,
 * entity, effect reads); everything it assembles from {@code core/}
 * stays MC-free.
 */
// contract: see boundaries.md section D (channel triple) and ADR-0004
//            D2 (claim surface per channel)
public final class BotAssembly {

    /** Passive mobs worth hunting (issue 0010 D3 food sources). */
    private static final java.util.Set<String> PASSIVE_FOOD_TYPES = java.util.Set.of(
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit");

    /**
     * Tick budget for one reflex-owned engage mission: long enough for
     * a melee fight plus its grace window, short enough that an
     * unwinnable scrap cannot pin the body - the mission's own
     * leash/escape verdicts usually land first. Public because
     * gametest scenarios size their timeoutTicks against it.
     */
    public static final long ENGAGE_MISSION_TIMEOUT_TICKS = 600L;

    private BotAssembly() {}

    /**
     * Everything assembled around one body, ready to tick. The typed
     * {@code gotoHandler} accessor exists for the seams that need
     * that SPECIFIC verb (the state-summary line, the rig's
     * submitGoto); the {@code taskHandlers} list is what every
     * generic drive iterates - tick, cancel routing, the stop sweep -
     * so a new verb registers into all of them by joining the list,
     * and the server listener and the rig never learn about it.
     */
    public record Assembled(
            BotBodyEntity body,
            BindingWorldView view,
            BindingActor actor,
            InMemoryEventQueue events,
            TaskArbiter arbiter,
            SurvivalReflexLayer reflex,
            BotController controller,
            CommandBus bus,
            List<VerbTaskHandler<?>> taskHandlers,
            GotoCommandHandler gotoHandler,
            ChangeDetectingStateChannel state,
            com.mcbot.mcbotserver.adapter.entity.BotChunkTicket chunkTicket) {}

    /**
     * Wires the full production pipeline around one spawned body.
     *
     * @param level the body's server level; never null
     * @param body  the spawned carrier; never null
     * @return the assembled pipeline; never null
     */
    public static Assembled assemble(ServerLevel level, BotBodyEntity body) {
        // Beyond-head resetAt across queue recreations (issue 0015):
        // the epoch allocator lives in the overworld's SavedData, so
        // every /botspawn draws a marker strictly beyond anything any
        // prior queue ever reported.
        var epochSource = EventEpochStore.of(level.getServer().overworld());
        EventClock stamps = EventClock.of(level);
        InMemoryEventQueue events = new InMemoryEventQueue(stamps.day(), stamps.tod(), epochSource::nextEpoch);
        TaskArbiter arbiter = new TaskArbiter();
        // Baseline trait annotations the swim vocabulary cannot work
        // without. Code-level floor for now; the datapack JSON pipeline
        // (decision 10) supersedes this when block traits get their
        // reload listener - tracked as a workplan follow-up.
        // Climbable set mirrors vanilla BlockTags.CLIMBABLE (1.20.1):
        // ladder, vine, weeping_vines, twisting_vines. The ClimbUp
        // movement reads these; without them ladder shafts are invisible
        // to the planner and the bot cannot route vertically through them.
        BlockTraitsRegistry traits = new MapBlockTraitsRegistry()
                .register("minecraft:water", BlockTraits.liquidOnly())
                .register("minecraft:lava", BlockTraits.dangerousLiquid())
                .register("minecraft:ladder", BlockTraits.climbableOnly())
                .register("minecraft:vine", BlockTraits.climbableOnly())
                .register("minecraft:weeping_vines", BlockTraits.climbableOnly())
                .register("minecraft:twisting_vines", BlockTraits.climbableOnly())
                .seal();
        BindingWorldView view =
                new BindingWorldView(level, traits, () -> body.getInventory().snapshot());
        BindingActor actor = new BindingActor(body, events, stamps.day(), stamps.tod());

        // Best-food ranking (issue 0010 D5): highest nutrition wins,
        // ties keep the lower hotbar slot - cooked beats raw, raw
        // still beats starving.
        var foodCatalog = new VanillaFoodCatalog();
        java.util.function.IntSupplier bestFoodSlot = bestFoodSlotOf(body, foodCatalog);

        // Water-bucket slot + MLG rule: the supplier is shared by the
        // sensor stamp and the controller's injector feed - one source
        // of truth for both ends (the eat-slot pattern).
        java.util.function.IntSupplier waterBucketSlot = waterBucketSlotOf(body);
        // Best-healing-potion slot: same shared-supplier pattern as
        // food and water-bucket - the sensor stamps it on the blackboard
        // for the rule's availability gate, the injector reads it for
        // the SLOT+USE claim pair.
        java.util.function.IntSupplier bestPotionSlot = bestPotionSlotOf(body);
        SurvivalReflexLayer reflex = reflexLayer(level, body, bestFoodSlot, waterBucketSlot, bestPotionSlot);

        Behavior mover = new PathingBehavior(
                "mover",
                () -> finePoseOf(body),
                // Live yaw frame: the combat aim's ROT claim (priority
                // 20) beats the mover's look claim, so the MOVE
                // decomposition must run against the body's actual
                // facing or a standoff kite marches into its target
                // (ledger 56).
                steer -> body.getYRot(),
                () -> body.onGround(),
                com.mcbot.mcbotserver.core.pathing.BasicMoves::from,
                new com.mcbot.mcbotserver.core.pathing.PlanWorker());
        // CombatBehavior must be seated here too: without it the
        // engage reflex submits defend missions whose Attack overrides
        // nobody executes - the production gap behind the idle
        // night-cave death.
        // The combat aim origin is the EYE, not the feet: arrows
        // launch from eye height (shootFromRotation), and a feet-level
        // dy overstates the target elevation by the full eye height -
        // every ranged shot sailed high over close targets until this
        // split from the pathing supplier.
        // One shared ranking: the combat behavior's held-weapon policy
        // and DefendProcess's engagement decision must read the same
        // damage table, or the two tiers can disagree about whether a
        // carried sword beats the bow.
        var weaponCatalog = new VanillaWeaponCatalog();
        Behavior combat = new CombatBehavior("combat", () -> eyePoseOf(body), weaponCatalog);
        Behavior fisher = new com.mcbot.mcbotserver.core.behavior.FishBehavior("fish");
        Behavior tamer = new com.mcbot.mcbotserver.core.behavior.TameBehavior("tame");

        // One fresh reflex-owned defend per engage submission; the
        // assembly owns identity, budget and type sets.
        var engageCounter = new AtomicInteger();
        Supplier<com.mcbot.mcbotserver.api.process.BotProcess> engageFactory = () -> new DefendProcess(
                "reflex-engage-" + engageCounter.incrementAndGet(),
                PriorityBands.DEFEND_PRIORITY,
                ENGAGE_MISSION_TIMEOUT_TICKS,
                () -> poseOf(body),
                LevelThreatSensor.hostileTypes(),
                LevelThreatSensor.rangedTypes(),
                weaponCatalog);

        // One fresh reflex-owned rescue per ESCAPE submission: the
        // factory reads body state (lava vs fire) and scans for the
        // nearest shore / water-adjacent cell. Returns null when no
        // target is in range; the controller then degrades ESCAPE to
        // the FREEZE hold (park and escalate).
        Supplier<com.mcbot.mcbotserver.api.process.BotProcess> rescueFactory = new RescueMissionFactory(view, body);

        // One fresh reflex-owned forage mission per acquire
        // submission (ledger 34): the sensor runs once per mission,
        // and a berry-less range lands FOOD_STRATEGY_EXHAUSTED.
        var forageCounter = new AtomicInteger();
        Supplier<com.mcbot.mcbotserver.api.process.BotProcess> forageFactory =
                () -> new com.mcbot.mcbotserver.core.process.HungryProcess(
                        "reflex-forage-" + forageCounter.incrementAndGet(),
                        com.mcbot.mcbotserver.api.process.PriorityBands.requireLegal(50),
                        600,
                        () -> poseOf(body),
                        () -> com.mcbot.mcbotserver.adapter.sensing.ForageSensor.nearestBerryBush(
                                level, poseOf(body), 16),
                        new VanillaFoodCatalog(),
                        PASSIVE_FOOD_TYPES);

        // The seated dig-family missions' per-tick aim+dig claims ride
        // the stage-3 behavior loop (issue 0013 R1 protocol); one
        // injector instance is shared with the controller's reflex
        // paths so the claim shapes have exactly one home.
        ReflexClaimInjector claimInjector = new ReflexClaimInjector(actor, () -> poseOf(body));
        var digClaims =
                new DigClaimBehavior(arbiter::current, new ToolSelector(new VanillaToolCatalog()), claimInjector);

        BotController controller = new BotController(
                reflex,
                arbiter,
                List.of(mover, combat, fisher, tamer, digClaims),
                actor,
                () -> poseOf(body),
                body::getHealth,
                clockOf(level),
                events,
                CrashReporter.consoleFallback(),
                claimInjector,
                engageFactory,
                rescueFactory,
                forageFactory,
                new BotController.SurvivalInputs(
                        body::isInLava,
                        body::getAirSupply,
                        () -> body.getFoodData().getFoodLevel(),
                        bestFoodSlot,
                        bestPotionSlot,
                        waterBucketSlot));
        CommandBus bus = new CommandBus(events);
        GotoCommandHandler gotoHandler = new GotoCommandHandler(arbiter, events, stamps.day(), stamps.tod());
        gotoHandler.attach(bus);
        DigCommandHandler digHandler = new DigCommandHandler(arbiter, events, stamps.day(), stamps.tod());
        digHandler.attach(bus);
        MineCommandHandler mineHandler =
                new MineCommandHandler(arbiter, events, stamps.day(), stamps.tod(), () -> poseOf(body));
        mineHandler.attach(bus);
        AttackCommandHandler attackHandler = new AttackCommandHandler(
                arbiter, events, stamps.day(), stamps.tod(), () -> poseOf(body), weaponCatalog);
        attackHandler.attach(bus);
        TameCommandHandler tameHandler =
                new TameCommandHandler(arbiter, events, stamps.day(), stamps.tod(), () -> poseOf(body));
        tameHandler.attach(bus);
        List<VerbTaskHandler<?>> taskHandlers =
                List.of(gotoHandler, digHandler, mineHandler, attackHandler, tameHandler);
        // The bus has ONE cancel-listener slot: route to every verb
        // handler's public cancel method (each self-guards by its
        // missions map, so no verb dispatch is needed).
        bus.setCancelListener((taskId, verb) -> {
            for (VerbTaskHandler<?> handler : taskHandlers) {
                handler.onCancel(taskId, verb);
            }
        });

        ChangeDetectingStateChannel state = new ChangeDetectingStateChannel(
                () -> BotStateSnapshots.of(body, gotoHandler, level), events, stamps.day(), stamps.tod());

        var chunkTicket = new com.mcbot.mcbotserver.adapter.entity.BotChunkTicket(level);
        return new Assembled(
                body,
                view,
                actor,
                events,
                arbiter,
                reflex,
                controller,
                bus,
                taskHandlers,
                gotoHandler,
                state,
                chunkTicket);
    }

    /**
     * Drives one production tick in the contract order: every
     * registered verb handler (in assembly order), the state
     * pull-through, the crashed-state vitals, then the pipeline. The
     * server listener (ADR-0004 D1 END phase) and the gametest rig
     * both call this, so the order lives in exactly one place.
     *
     * @param a the assembled pipeline; never null
     */
    public static void tickOnce(Assembled a) {
        // Ticket first: a body that just crossed into an unticketed
        // chunk must regain entity ticking before anything below
        // reads or drives it (issue 0015 ticket gap).
        a.chunkTicket().tick(a.body());
        for (VerbTaskHandler<?> handler : a.taskHandlers()) {
            handler.tick();
        }
        // Pull-through state capture: change detection pushes STATE_PUSH
        // onto the stream only when the snapshot actually moved, so a
        // per-tick drive cannot flood it.
        a.state().current();
        a.controller().onTick(a.view());
    }

    /**
     * The production reflex layer: the full threat sensor over the
     * live body plus the rule set with its priority reasoning. One
     * rule = one comment = one addRule, so the ladder stays auditable
     * against ReflexPriorityOrderGateTest.
     */
    private static SurvivalReflexLayer reflexLayer(
            ServerLevel level,
            BotBodyEntity body,
            java.util.function.IntSupplier bestFoodSlot,
            java.util.function.IntSupplier waterBucketSlot,
            java.util.function.IntSupplier bestPotionSlot) {
        SurvivalReflexLayer reflex = new SurvivalReflexLayer(new LevelThreatSensor(
                () -> poseOf(body),
                body::getAirSupply,
                body::isInLava,
                body::getRemainingFireTicks,
                body::getTicksFrozen,
                body::isInWall,
                () -> suffocationBlockOf(body),
                body.getFoodData()::getFoodLevel,
                bestFoodSlot,
                () -> body.getDeltaMovement().y < -0.25,
                () -> mlgGroundCellOf(level, body),
                waterBucketSlot,
                bestPotionSlot));
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
        // MLG water bucket (the coverage-review P0 slice): while
        // descending over sensed ground with a water bucket in the
        // hotbar, place the water before impact. Priority 120 sits
        // between DIG_ON_SUFFOCATION (115) and LAVA_ESCAPE (130):
        // falling damage is deferred but certain, and the placement
        // window is a handful of ticks. Self-limiting after one
        // placement (the bucket empties, the slot read turns -1).
        reflex.addRule(new com.mcbot.mcbotserver.core.reflex.WaterBucketOnFallRule());
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
        // Drink reflex sits below ENGAGE (combat missions own their
        // consumable pacing) and above EAT (health is more urgent than
        // hunger). Trigger 12 gives a 2-HP window before FREEZE (10)
        // locks in with its hysteresis release at 15.
        reflex.addRule(new com.mcbot.mcbotserver.core.reflex.DrinkOnLowHealthRule());
        // Eat reflex sits below ENGAGE (0010 D6: combat first) and
        // only fires while the sensor sees both hunger and food.
        reflex.addRule(new com.mcbot.mcbotserver.core.reflex.EatWhenHungryRule());
        // Acquisition is the eat rule's complement (0010): fires only
        // hungry-without-food, hands off to the forage seat.
        reflex.addRule(new com.mcbot.mcbotserver.core.reflex.AcquireFoodWhenHungryRule());
        return reflex;
    }

    /**
     * Best-food hotbar scan (issue 0010 D5): highest nutrition wins,
     * ties keep the lower hotbar slot - cooked beats raw, raw still
     * beats starving.
     */
    private static java.util.function.IntSupplier bestFoodSlotOf(BotBodyEntity body, VanillaFoodCatalog foodCatalog) {
        return () -> {
            var container = body.getInventory().container();
            int best = -1;
            int bestNutrition = -1;
            for (int slot = 0; slot < com.mcbot.mcbotserver.api.inventory.InventoryView.HOTBAR_SIZE; slot++) {
                var stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                var facts = foodCatalog.facts(net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getKey(stack.getItem())
                        .toString());
                if (facts != null && facts.nutrition() > bestNutrition) {
                    best = slot;
                    bestNutrition = facts.nutrition();
                }
            }
            return best;
        };
    }

    /**
     * Water-bucket hotbar scan: the MLG rule's slot feed, -1 when
     * none (which silences the rule - no phantom placements).
     */
    private static java.util.function.IntSupplier waterBucketSlotOf(BotBodyEntity body) {
        return () -> {
            var container = body.getInventory().container();
            for (int i = 0; i < 9; i++) {
                var stack = container.getItem(i);
                if (stack.is(net.minecraft.world.item.Items.WATER_BUCKET)) {
                    return i;
                }
            }
            return -1;
        };
    }

    /**
     * Best-healing-potion hotbar scan: drinkable PotionItem only
     * (splash/lingering are thrown, not consumed). Rank: instant_health
     * (instant heal, no waiting) > regeneration (heal over time). Ties
     * keep the lower hotbar slot. Returns -1 when no healing potion is
     * carried, which silences the drink reflex - no phantom sips.
     */
    private static java.util.function.IntSupplier bestPotionSlotOf(BotBodyEntity body) {
        return () -> {
            var container = body.getInventory().container();
            int best = -1;
            int bestRank = -1;
            for (int slot = 0; slot < com.mcbot.mcbotserver.api.inventory.InventoryView.HOTBAR_SIZE; slot++) {
                var stack = container.getItem(slot);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.PotionItem)) {
                    continue;
                }
                int rank = healingRankOf(stack);
                if (rank > bestRank) {
                    best = slot;
                    bestRank = rank;
                }
            }
            return best;
        };
    }

    /**
     * Rank a potion stack by its healing utility: 2 = instant_health,
     * 1 = regeneration, 0 = no healing effect (not selected).
     */
    private static int healingRankOf(net.minecraft.world.item.ItemStack stack) {
        for (var effect : net.minecraft.world.item.alchemy.PotionUtils.getMobEffects(stack)) {
            if (effect.getEffect() == net.minecraft.world.effect.MobEffects.HEAL) {
                return 2;
            }
            if (effect.getEffect() == net.minecraft.world.effect.MobEffects.REGENERATION) {
                return 1;
            }
        }
        return 0;
    }

    private static CellPos poseOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(), body.getBlockZ());
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
    private static com.mcbot.mcbotserver.api.types.CellPos mlgGroundCellOf(ServerLevel lvl, BotBodyEntity body) {
        var feet = body.blockPosition();
        for (int i = 0; i <= 6; i++) {
            var probe = feet.below(i);
            var state = lvl.getBlockState(probe);
            if (!state.isAir() && !state.getCollisionShape(lvl, probe).isEmpty()) {
                return new com.mcbot.mcbotserver.api.types.CellPos(probe.getX(), probe.getY(), probe.getZ());
            }
        }
        return null;
    }

    private static CellPos suffocationBlockOf(BotBodyEntity body) {
        if (!body.isInWall()) {
            return null;
        }
        var eyeCell = net.minecraft.core.BlockPos.containing(body.getEyePosition());
        return new CellPos(eyeCell.getX(), eyeCell.getY(), eyeCell.getZ());
    }

    private static com.mcbot.mcbotserver.api.types.Vec3 finePoseOf(BotBodyEntity body) {
        return new com.mcbot.mcbotserver.api.types.Vec3(body.getX(), body.getY(), body.getZ());
    }

    /** The combat aim origin: feet lifted to the eye line, matching
     * where projectiles actually launch from.
     *
     * @param body the carrier; never null
     * @return the eye position; never null
     */
    private static com.mcbot.mcbotserver.api.types.Vec3 eyePoseOf(BotBodyEntity body) {
        return new com.mcbot.mcbotserver.api.types.Vec3(body.getX(), body.getY() + body.getEyeHeight(), body.getZ());
    }

    /**
     * The day / time-of-day stamp pair every disclosure site shares -
     * previously nine hand-copied lambda pairs over the same two level
     * reads. Values stay live: each access re-reads the level clock.
     *
     * @param day in-game day counter; never null
     * @param tod time-of-day ticks 0..23999; never null
     */
    private record EventClock(LongSupplier day, LongSupplier tod) {

        private static EventClock of(ServerLevel level) {
            return new EventClock(() -> level.getDayTime() / 24000L, () -> level.getDayTime() % 24000L);
        }
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
