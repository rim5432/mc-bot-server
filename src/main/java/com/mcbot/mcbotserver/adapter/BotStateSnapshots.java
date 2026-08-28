package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.state.BotState;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.command.GotoCommandHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Builds the boundary-D state snapshot (the {@code /bot status} body
 * and the STATE_PUSH channel payload) from the live body. Extracted
 * from BotAssembly so the composition root stops carrying
 * state-serialization logic (2026-08-28 paydown).
 *
 * <p>Implementation note: adapter layer by necessity (live body
 * reads); the produced {@link BotState} is boundary-D vocabulary.
 */
public final class BotStateSnapshots {

    private BotStateSnapshots() {}

    /**
     * Captures the boundary-D state snapshot from the live body.
     *
     * @param body        the spawned body; never null
     * @param gotoHandler workload owner for the task summary; never
     *                    null
     * @param level       the body's level; never null
     * @return fresh snapshot; never null
     */
    public static BotState of(BotBodyEntity body, GotoCommandHandler gotoHandler, ServerLevel level) {
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
            effects.put(
                    BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect()).toString(), instance.getAmplifier());
        }
        // Health bucketed to whole hearts: 1 heart = 2 half-heart
        // units, rounding half up so a fresh bot reads 20.
        return new BotState(
                poseOf(body),
                body.getYRot(),
                body.getXRot(),
                level.dimension().location().getPath(),
                items,
                inv.selectedSlot(),
                effects,
                gotoHandler.activeTaskSummary(),
                Math.round(body.getHealth() / 2.0f),
                freeSlots,
                body.getFoodData().getFoodLevel());
    }

    private static CellPos poseOf(BotBodyEntity body) {
        return new CellPos(body.getBlockX(), body.getBlockY(), body.getBlockZ());
    }
}
