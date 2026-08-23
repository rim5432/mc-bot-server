package com.mcbot.mcbotserver.client;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side appearance for the bot body. The device layer runs
 * fine without any client code - dedicated server and gametests
 * never load this package. The renderer exists so a human watching
 * in a dev client sees the body instead of crashing on it: a saved
 * {@code bot_body} from an earlier session crashed renderLevel with
 * a null EntityRenderer before this registration existed.
 *
 * <p>The zombie skin is a stand-in, not identity: geometry parity
 * with the 0.6 x 1.8 carrier is what matters; swapping in dedicated
 * art later touches only the SKIN constant.
 */
@Mod.EventBusSubscriber(modid = McBotServer.MODID,
    bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BotBodyRenderers {

    private BotBodyRenderers() {
    }

    /**
     * Registers the body's renderer during client render setup.
     *
     * @param event the forge registration event; never null
     */
    @SubscribeEvent
    public static void onRegisterRenderers(
        EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(McBotServer.BOT_BODY.get(),
            BotBodyRenderer::new);
    }

    /**
     * Renders the body as a humanoid using the vanilla zombie layer
     * and skin - matching proportions, zero new assets.
     */
    public static class BotBodyRenderer
        extends MobRenderer<BotBodyEntity, HumanoidModel<BotBodyEntity>> {

        /** Stand-in skin until dedicated art exists. */
        private static final ResourceLocation SKIN =
            new ResourceLocation("minecraft",
                "textures/entity/zombie/zombie.png");

        /**
         * Creates the renderer over a zombie-baked humanoid model.
         *
         * @param context the client render context; never null
         */
        public BotBodyRenderer(EntityRendererProvider.Context context) {
            super(context,
                new HumanoidModel<>(
                    context.bakeLayer(ModelLayers.ZOMBIE)),
                0.5f);
        }

        /**
         * Supplies the stand-in skin.
         *
         * @param entity the body being rendered; never null
         * @return the zombie texture location; never null
         */
        @Override
        public ResourceLocation getTextureLocation(
            BotBodyEntity entity) {
            return SKIN;
        }
    }
}
