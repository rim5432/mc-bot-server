/**
 * Pure contracts for the mc-bot-server device: interfaces, records and
 * exceptions only. Zero Minecraft imports are allowed in this package
 * or anywhere below it.
 *
 * <p>Contract: see boundaries.md section A (WorldView is the read half,
 * Actor is the intent half). This package is the seam
 * a non-JVM harness could in principle bind to, so it must stay pure
 * Java. Enforcement: {@code ZeroMcImportGateTest} fails the build when
 * any file under {@code api} or {@code core} imports
 * {@code net.minecraft.*} / {@code net.minecraftforge.*}.
 *
 * <p>Implementation note: implementations of these contracts live in
 * {@code com.mcbot.mcbotserver.core}; the only MC-aware code in the mod
 * is the adapter layer (Stage 1) plus the mod entry class.
 */
package com.mcbot.mcbotserver.api;
