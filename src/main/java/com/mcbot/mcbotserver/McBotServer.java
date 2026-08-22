package com.mcbot.mcbotserver;

import com.mcbot.mcbotserver.adapter.BindingActor;
import com.mcbot.mcbotserver.adapter.BindingWorldView;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.adapter.sensing.LevelThreatSensor;
import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.behavior.PathingBehavior;
import com.mcbot.mcbotserver.core.command.CommandBus;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import com.mcbot.mcbotserver.core.event.InMemoryEventQueue;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.reflex.FreezeOnLowHealthRule;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mcbot.mcbotserver.core.tick.BotController;
import com.mcbot.mcbotserver.core.tick.CrashReporter;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
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

    private static final RegistryObject<EntityType<BotBodyEntity>>
        BOT_BODY = ENTITIES.register("bot_body",
            () -> EntityType.Builder.of(BotBodyEntity::new,
                    MobCategory.CREATURE)
                .sized(0.6f, 1.8f)
                .build("bot_body"));

    private BotController activeController;
    private BindingWorldView activeView;
    private GotoCommandHandler activeGotoHandler;

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
     * ADR-0004 D1: single tick entry on the server tick END phase.
     * World state is settled here; outputs apply during next tick's
     * physics (the documented one-tick latency).
     */
    // contract: see ADR-0004 D1 (single tick entry, fixed order)
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (activeController != null && activeView != null) {
            if (activeGotoHandler != null) {
                activeGotoHandler.tick();
            }
            activeController.onTick(activeView);
        }
    }

    /**
     * Debug seam: spawn one body with its full pipeline. Stage 1's
     * manual harness; a boundary-D transport replaces this later.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("botspawn")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                var level = ctx.getSource().getLevel();
                var pos = ctx.getSource().getPosition();
                BotBodyEntity body = BOT_BODY.get().create(level);
                if (body == null) {
                    ctx.getSource().sendFailure(Component.literal(
                        "body creation failed"));
                    return 0;
                }
                body.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
                level.addFreshEntity(body);

                InMemoryEventQueue events = new InMemoryEventQueue(
                    () -> level.getDayTime() / 24000L,
                    () -> level.getDayTime() % 24000L);
                TaskArbiter arbiter = new TaskArbiter();
                BindingWorldView view = new BindingWorldView(level);
                Actor actor = new BindingActor(body);

                SurvivalReflexLayer reflex = new SurvivalReflexLayer(
                    new LevelThreatSensor(view,
                        () -> poseOf(body)));
                reflex.addRule(new FreezeOnLowHealthRule());

                Behavior mover = new PathingBehavior("mover",
                    () -> finePoseOf(body),
                    com.mcbot.mcbotserver.core.pathing.BasicMoves::from,
                    new com.mcbot.mcbotserver.core.pathing.PlanWorker());

                BotController controller = new BotController(reflex,
                    arbiter, java.util.List.of(mover), actor,
                    () -> poseOf(body), body::getHealth,
                    clockOf(level), events,
                    CrashReporter.consoleFallback());
                CommandBus bus = new CommandBus(events);
                GotoCommandHandler gotoHandler = new GotoCommandHandler(
                    arbiter, events,
                    () -> level.getDayTime() / 24000L,
                    () -> level.getDayTime() % 24000L);
                gotoHandler.attach(bus);
                this.activeGotoHandler = gotoHandler;

                this.activeController = controller;
                this.activeView = view;

                String spawned = "bot spawned at " + body.blockPosition()
                    + "; use /goto x y z [tolerance] [timeoutTicks]";
                ctx.getSource().sendSuccess(
                    () -> Component.literal(spawned), true);
                return 1;
            }));
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

    private static BotController.GameClock clockOf(
            net.minecraft.server.level.ServerLevel level) {
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
