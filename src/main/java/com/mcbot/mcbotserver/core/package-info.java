/**
 * Offline-testable engine for the mc-bot-server device. Everything here
 * implements a contract from {@code com.mcbot.mcbotserver.api} and must
 * remain free of Minecraft imports.
 *
 * <p>Contract: see boundaries.md sections A/B/C/D.
 * Keeping this tree MC-free is what lets the Stage 0 acceptance gate run
 * as plain JUnit on any machine without a Forge runtime.
 *
 * <p>Implementation note: the only packages allowed to import Minecraft
 * types are the future adapter layer ({@code com.mcbot.mcbotserver.adapter})
 * and mixin-only code under {@code com.mcbot.mcbotserver.mixin}; until
 * Stage 1 lands, the mod entry class is the sole MC-aware file.
 */
package com.mcbot.mcbotserver.core;
