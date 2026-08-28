package com.mcbot.mcbotserver;

import com.mcbot.mcbotserver.adapter.BotAssembly;
import com.mcbot.mcbotserver.adapter.BotCommands;
import com.mcbot.mcbotserver.adapter.BotControlSocket;
import com.mcbot.mcbotserver.adapter.MenuCommands;
import com.mcbot.mcbotserver.adapter.RecipeCatalog;
import com.mcbot.mcbotserver.adapter.ReflexRuleReloader;
import com.mcbot.mcbotserver.adapter.VanillaArmorCatalog;
import com.mcbot.mcbotserver.adapter.WorldCommands;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
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

/**
 * Mod entry: the only MC-aware wiring point. Owns entity
 * registration, the server-tick subscription (ADR-0004 D1 END
 * phase) and the /botspawn command that assembles a full pipeline
 * around one body.
 *
 * <p>Stage 1 scope: one active bot session at a time; a second
 * /botspawn replaces the first. Multi-body orchestration is out of
 * project scope per boundaries.md.
 */
@Mod(McBotServer.MODID)
public class McBotServer {
    /** Mod identifier; matches gradle.properties mod_id and the mixin config package. */
    public static final String MODID = "mcbotserver";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    /** Registry handle for the physical carrier; read by the client
     *  renderer wiring. */
    public static final RegistryObject<EntityType<BotBodyEntity>> BOT_BODY = ENTITIES.register(
            "bot_body",
            () -> EntityType.Builder.of(BotBodyEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .build("bot_body"));

    /**
     * One live bot session: the assembled pipeline plus the
     * spawn-level recipe catalog. Components are set together at
     * {@code /botspawn} and cleared together at {@code /botdespawn} -
     * the parallel {@code active*} fields this replaced drifted
     * exactly once (activeAttackHandler survived despawn), which is
     * the failure mode a single field cannot have.
     */
    private record BotSession(BotAssembly.Assembled pipeline, RecipeCatalog catalog) {}

    private BotSession active;

    private final ReflexRuleReloader ruleReloader = new ReflexRuleReloader();

    /**
     * Registers the mod with both Forge buses: the entity type and the
     * attribute listener go on the mod bus (a spawned body cannot move
     * without attributes), every {@code @SubscribeEvent} handler here on
     * the game bus.
     */
    public McBotServer() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(modBus);
        // EntityAttributeCreationEvent is a MOD-bus event; without this
        // listener the body spawns with no attributes and vanilla
        // logs "has no attributes" on every tick. addListener keeps
        // the game-bus @SubscribeEvent methods out of mod-bus scan.
        modBus.addListener(this::onAttributes);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("McBotServer initializing — device layer for MC 1.20.1");
    }

    /**
     * Datapack reloads rewrite the reflex rule table in place.
     *
     * @param event mod-bus callback registering that listener; never null
     */
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        ruleReloader.bind(null);
        event.addListener(ruleReloader);
    }

    /**
     * Attribute wiring so the body can actually move and survive.
     *
     * @param event mod-bus attribute-map builder fed by {@code put};
     *              never null
     */
    @SubscribeEvent
    public void onAttributes(EntityAttributeCreationEvent event) {
        // createMobAttributes does NOT include MOVEMENT_SPEED - each
        // mob type adds its own. Without it the binding's zza=1 drives
        // a speed-0 body: gravity works, walking never starts.
        // ATTACK_DAMAGE base 3.0 = zombie scale, preserving the
        // pre-attribute MeleeResolver constant for bare fists; weapons
        // add their mainhand modifier on top through the equipment
        // mirror (Phase 4 combat slice).
        event.put(
                BOT_BODY.get(),
                Mob.createMobAttributes()
                        .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.25)
                        .add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 3.0D)
                        .build());
    }

    /**
     * Marks device startup in the log once a server instance exists.
     *
     * @param event game-bus startup signal; payload unused
     */
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
     *
     * @param event per-tick bus event; only Phase.END advances the pipeline
     */
    // contract: see ADR-0004 D1 + ADR-0005 D2 (single tick entry;
    //          outer catch is the last-ditch seam for the harness
    //          wrapping the four-stage pipeline)
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (active == null) {
            return;
        }
        try {
            // The tick order lives in the assembly, not here - the
            // gametest rig drives the identical method.
            BotAssembly.tickOnce(active.pipeline());
        } catch (RuntimeException e) {
            LOGGER.error("mcbotserver tick harness failed; " + "emergency latching", e);
            active.pipeline().controller().emergencyLatch(e);
        }
    }

    /**
     * Debug seam: spawn one body with its full pipeline. Stage 1's
     * manual harness; a boundary-D transport replaces this later.
     *
     * @param event grants the server dispatcher for verb registration;
     *              never null
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BotCommands.register(event.getDispatcher(), this::channels);
        com.mcbot.mcbotserver.adapter.MenuCommands.register(event.getDispatcher(), this::menuLive);
        com.mcbot.mcbotserver.adapter.WorldCommands.register(event.getDispatcher(), this::worldLive);
        event.getDispatcher()
                .register(Commands.literal("botspawn")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            var level = ctx.getSource().getLevel();
                            var pos = ctx.getSource().getPosition();
                            // Replace semantics (class doc): at most one wired
                            // body. The old ENTITY must leave the world too, not
                            // just the wiring - leaving it turned every extra
                            // /botspawn into an orphaned zombie standing around.
                            if (active != null && active.pipeline().body().isAlive()) {
                                active.pipeline().body().discard();
                            }
                            BotBodyEntity body = BOT_BODY.get().create(level);
                            if (body == null) {
                                ctx.getSource().sendFailure(Component.literal("body creation failed"));
                                return 0;
                            }
                            body.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
                            level.addFreshEntity(body);

                            // Single assembly source: the gametest rig builds the
                            // identical pipeline through the same factory, so
                            // wiring drift between production and in-engine tests
                            // is impossible by construction.
                            var a = BotAssembly.assemble(level, body);
                            // Future /reload swaps follow the datapack table; the
                            // reloader is mod-instance state, not pipeline state.
                            ruleReloader.bind(a.reflex());

                            this.active = new BotSession(a, new RecipeCatalog(level));

                            String spawned = "bot spawned at " + body.blockPosition()
                                    + "; drive with /bot goto x y z tolerance timeoutTicks";
                            ctx.getSource().sendSuccess(() -> Component.literal(spawned), true);
                            return 1;
                        }));
        event.getDispatcher()
                .register(Commands.literal("botdespawn")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            var level = ctx.getSource().getLevel();
                            var bodies = level.getEntities(BOT_BODY.get(), b -> true);
                            bodies.forEach(BotBodyEntity::discard);
                            this.active = null;
                            int n = bodies.size();
                            String msg = "removed " + n + (n == 1 ? " bot body" : " bot bodies");
                            ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
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
        if (active == null) {
            return null;
        }
        var a = active.pipeline();
        return new BotCommands.Channels(
                a.events(), a.bus(), a.state(), () -> a.gotoHandler().stopAll(), () -> {
                    // ADR-0005 5a through the console verb: report whether
                    // a latch was actually cleared so a harness resetting
                    // a healthy bot learns it did nothing.
                    boolean wasCrashed = a.controller().isCrashed();
                    a.controller().reset();
                    return wasCrashed;
                });
    }

    /**
     * Live perception surface for the 0013 slice-1 read family.
     *
     * @return the perception surface, or null before /botspawn
     */
    private WorldCommands.Live worldLive() {
        if (active == null || !active.pipeline().body().isAlive()) {
            return null;
        }
        var a = active.pipeline();
        return new WorldCommands.Live(
                a.view(),
                () -> new CellPos(
                        a.body().getBlockX(), a.body().getBlockY(), a.body().getBlockZ()),
                () -> String.valueOf(a.body().getUUID()),
                a.actor().interactExecutor(),
                slot -> {
                    a.body().selectedSlot = slot;
                    a.body().getInventory().setSelectedSlot(slot);
                },
                a.body()::setSneakLatched,
                a.actor()::useHeldItemAir,
                a.actor()::sleepAt);
    }

    /**
     * Live menu surface for the 0012 D1 command batch. The catalog is
     * rebuilt per spawn because it reads the server RecipeManager of
     * the spawn level.
     *
     * @return the menu surface, or null before the first /botspawn
     */
    private MenuCommands.Live menuLive() {
        if (active == null || !active.pipeline().body().isAlive()) {
            return null;
        }
        var a = active.pipeline();
        return new MenuCommands.Live(
                a.actor().menuTransactions(),
                active.catalog(),
                new VanillaArmorCatalog(),
                (ServerLevel) a.body().level(),
                a.body()::blockPosition);
    }
}
