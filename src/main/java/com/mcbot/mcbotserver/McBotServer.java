package com.mcbot.mcbotserver;

import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.BotCommands;
import com.mcbot.mcbotserver.adapter.BotControlSocket;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.state.ChangeDetectingStateChannel;
import com.mcbot.mcbotserver.core.tick.BotController;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Mod entry: the only MC-aware wiring point until the adapter grows
 * its own registration conventions. Owns entity registration, the
 * server-tick subscription (ADR-0004 D1 END phase) and the /botspawn
 * debug command that assembles a full pipeline around one body.
 *
 * <p>Stage 1 scope: one active bot session at a time; a second
 * /botspawn replaces the first. Multi-body orchestration is out of
 * project scope per boundaries.md.
 */
@Mod(McBotServer.MODID)
public class McBotServer {
    public static final String MODID = "mcbotserver";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    /** Registry handle for the physical carrier; read by the client
     *  renderer wiring. */
    public static final RegistryObject<EntityType<BotBodyEntity>>
        BOT_BODY = ENTITIES.register("bot_body",
            () -> EntityType.Builder.of(BotBodyEntity::new,
                    MobCategory.CREATURE)
                .sized(0.6f, 1.8f)
                .build("bot_body"));

    private BotController activeController;
    private BindingWorldView activeView;
    private BotBodyEntity activeBody;
    private GotoCommandHandler activeGotoHandler;
    private InMemoryEventQueue activeEvents;
    private CommandBus activeBus;
    private ChangeDetectingStateChannel activeState;

    private final com.mcbot.mcbotserver.adapter.ReflexRuleReloader
        ruleReloader = new com.mcbot.mcbotserver.adapter.ReflexRuleReloader();

    public McBotServer() {
        IEventBus modBus =
            FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(modBus);
        // EntityAttributeCreationEvent is a MOD-bus event; without this
        // listener the body spawns with no attributes and vanilla
        // logs "has no attributes" on every tick. addListener keeps
        // the game-bus @SubscribeEvent methods out of mod-bus scan.
        modBus.addListener(this::onAttributes);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("McBotServer initializing — device layer for MC 1.20.1");
    }

    /** Datapack reloads rewrite the reflex rule table in place. */
    @SubscribeEvent
    public void onAddReloadListeners(
            net.minecraftforge.event.AddReloadListenerEvent event) {
        ruleReloader.bind(null);
        event.addListener(ruleReloader);
    }

    /** Attribute wiring so the body can actually move and survive. */
    @SubscribeEvent
    public void onAttributes(EntityAttributeCreationEvent event) {
        // createMobAttributes does NOT include MOVEMENT_SPEED - each
        // mob type adds its own. Without it the binding's zza=1 drives
        // a speed-0 body: gravity works, walking never starts.
        event.put(BOT_BODY.get(), Mob.createMobAttributes()
            .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED,
                0.25)
            .build());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("McBotServer: server started, bot device active");
    }

    /**
     * Opens the control socket on servers vanilla RCON does not
     * cover (the dev client's integrated server), so the external
     * harness drives either server kind through the same tool.
     *
     * @param event the started event; never null
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        BotControlSocket.startIfEnabled(event.getServer());
    }

    /**
     * Closes the control socket before the world saves.
     *
     * @param event the stopping event; never null
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BotControlSocket.stop();
    }

    /**
     * ADR-0004 D1: single tick entry on the server tick END phase.
     * World state is settled here; outputs apply during next tick's
     * physics (the documented one-tick latency).
     *
     * <p>The outer try-catch is the last-ditch seam: the in-pipeline
     * try-catch in {@link BotController#onTick} is the primary
     * defense (ADR-0005 D1), but it only wraps the four stages.
     * A failure in the harness wrapping onTick itself -
     * {@code GotoCommandHandler.tick}, an init NPE, a future seam
     * we add here - would otherwise escape to the Forge bus and
     * surface as a {@code ReportedException} on the MC main thread.
     * Catching here and calling {@code emergencyLatch} keeps the
     * contract from ADR-0005 D2 closed at the listener boundary.
     */
    // contract: see ADR-0004 D1 + ADR-0005 D2 (single tick entry;
    //          outer catch is the last-ditch seam for the harness
    //          wrapping the four-stage pipeline)
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (activeController == null || activeView == null
                || activeBody == null) {
            return;
        }
        try {
            if (activeGotoHandler != null) {
                activeGotoHandler.tick();
            }
            if (activeState != null) {
                // Pull-through state capture: change detection pushes
                // STATE_PUSH onto the stream only when the snapshot
                // actually moved, so a per-tick drive cannot flood it.
                activeState.current();
            }
            // Feed the crashed-state vitals before the pipeline runs:
            // MinimalReflex reads these to decide whether to jump
            // (lava or low air). The normal reflex layer derives
            // fluid/air state from ThreatBlackboard sensors; these
            // flags are the crashed-state parallel that cannot depend
            // on the sensor stack (ADR-0005 D3).
            activeController.setInLethalFluid(activeBody.isInLava());
            activeController.setAirSupply(activeBody.getAirSupply());
            activeController.onTick(activeView);
        } catch (RuntimeException e) {
            LOGGER.error("mcbotserver tick harness failed; "
                + "emergency latching", e);
            activeController.emergencyLatch(e);
        }
    }

    /**
     * Debug seam: spawn one body with its full pipeline. Stage 1's
     * manual harness; a boundary-D transport replaces this later.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BotCommands.register(event.getDispatcher(), this::channels);
        event.getDispatcher().register(Commands.literal("botspawn")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                var level = ctx.getSource().getLevel();
                var pos = ctx.getSource().getPosition();
                // Replace semantics (class doc): at most one wired
                // body. The old ENTITY must leave the world too, not
                // just the wiring - leaving it turned every extra
                // /botspawn into an orphaned zombie standing around.
                if (activeBody != null && activeBody.isAlive()) {
                    activeBody.discard();
                }
                BotBodyEntity body = BOT_BODY.get().create(level);
                if (body == null) {
                    ctx.getSource().sendFailure(Component.literal(
                        "body creation failed"));
                    return 0;
                }
                body.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
                level.addFreshEntity(body);

                // Single assembly source: the gametest rig builds the
                // identical pipeline through the same factory, so
                // wiring drift between production and in-engine tests
                // is impossible by construction.
                var a = com.mcbot.mcbotserver.adapter.BotAssembly
                    .assemble(level, body);
                // Future /reload swaps follow the datapack table; the
                // reloader is mod-instance state, not pipeline state.
                ruleReloader.bind(a.reflex());

                this.activeEvents = a.events();
                this.activeBus = a.bus();
                this.activeState = a.state();
                this.activeController = a.controller();
                this.activeView = a.view();
                this.activeBody = body;
                this.activeGotoHandler = a.gotoHandler();

                String spawned = "bot spawned at " + body.blockPosition()
                    + "; drive with /bot goto x y z tolerance timeoutTicks";
                ctx.getSource().sendSuccess(
                    () -> Component.literal(spawned), true);
                return 1;
            }));
        event.getDispatcher().register(Commands.literal("botdespawn")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                var level = ctx.getSource().getLevel();
                var bodies = level.getEntities(BOT_BODY.get(),
                    b -> true);
                bodies.forEach(BotBodyEntity::discard);
                this.activeEvents = null;
                this.activeBus = null;
                this.activeState = null;
                this.activeController = null;
                this.activeView = null;
                this.activeBody = null;
                this.activeGotoHandler = null;
                int n = bodies.size();
                String msg = "removed " + n
                    + (n == 1 ? " bot body" : " bot bodies");
                ctx.getSource().sendSuccess(
                    () -> Component.literal(msg), true);
                return n;
            }));
    }

    /**
     * Live channel triple for the console command surface.
     *
     * @return channels of the active bot, or null before the first
     *         /botspawn
     */
    private BotCommands.Channels channels() {
        if (activeEvents == null || activeBus == null
                || activeState == null) {
            return null;
        }
        return new BotCommands.Channels(activeEvents, activeBus,
            activeState,
            () -> activeGotoHandler != null
                ? activeGotoHandler.stopAll() : 0,
            () -> {
                // ADR-0005 5a through the console verb: report whether
                // a latch was actually cleared so a harness resetting
                // a healthy bot learns it did nothing.
                boolean wasCrashed = activeController != null
                    && activeController.isCrashed();
                if (activeController != null) {
                    activeController.reset();
                }
                return wasCrashed;
            });
    }
}
