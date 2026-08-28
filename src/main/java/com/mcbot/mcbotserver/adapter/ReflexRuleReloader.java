package com.mcbot.mcbotserver.adapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mcbot.mcbotserver.core.reflex.ReflexRuleJson;
import com.mcbot.mcbotserver.core.reflex.SurvivalReflexLayer;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Datapack-driven reflex rule table: reads {@code
 * data/mcbotserver/reflex_rules.json} on every /reload and swaps the
 * live layer's rule table in one move. The seam ADR-0003 promises -
 * "rewriting the whole rule table is invisible to processes" - made
 * operational through the vanilla reload pipeline.
 *
 * <p>Contract: see boundaries.md section C; the layer swap runs on the
 * server thread (reload listeners execute there), the same thread as
 * the tick pipeline, so no locking is needed.
 *
 * <p>Bot is harness-blind (boundary D): a missing or invalid table
 * never disables the bot - it keeps the last good table, because an
 * empty safety reflex is worse than a stale one.
 */
public final class ReflexRuleReloader extends SimpleJsonResourceReloadListener {

    /** Datapack directory SimpleJsonResourceReloadListener scans. */
    public static final String DIRECTORY = "reflex_rules";

    private static final Logger LOGGER = LogUtils.getLogger();

    private SurvivalReflexLayer target;

    /**
     * Creates the reloader; register via
     * {@code AddReloadListenerEvent#addListener}.
     */
    // contract: see boundaries.md section C (rule table rewrite seam)
    public ReflexRuleReloader() {
        super(new Gson(), DIRECTORY);
    }

    /**
     * Bind the layer whose rules follow future reloads. Called by the
     * adapter when a bot spawns; null clears the binding.
     *
     * @param layer the layer to keep current, or null to unbind
     */
    // runs on server tick thread
    public void bind(SurvivalReflexLayer layer) {
        this.target = layer;
    }

    @Override
    protected void apply(
            Map<net.minecraft.resources.ResourceLocation, com.google.gson.JsonElement> files,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        com.google.gson.JsonElement doc =
                files.get(new net.minecraft.resources.ResourceLocation("mcbotserver", DIRECTORY));
        if (!(doc instanceof JsonObject table)) {
            return;
        }
        try {
            List<com.mcbot.mcbotserver.api.reflex.ReflexRule> rules = ReflexRuleJson.parse(table.toString());
            if (target != null && !rules.isEmpty()) {
                target.replaceRules(rules);
            }
        } catch (IllegalArgumentException badTable) {
            // Keep the last good table; a broken datapack must not
            // silently strip the bot's survival reflexes.
            LOGGER.error("mcbotserver reflex_rules.json rejected: {}", badTable.getMessage());
        }
    }
}
