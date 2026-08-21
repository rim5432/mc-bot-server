package com.mcbot.mcbotserver;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(McBotServer.MODID)
public class McBotServer {
    public static final String MODID = "mcbotserver";
    private static final Logger LOGGER = LogUtils.getLogger();

    public McBotServer() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("McBotServer initializing — device layer for MC 1.20.1");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("McBotServer: server started, bot device active");
    }
}
