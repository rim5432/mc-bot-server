package com.mcbot.mcbotserver.api.menu;

/**
 * Raw progress data for furnace-family menus (furnace, blast_furnace,
 * smoker). Values are in ticks, read straight from the block entity's
 * {@code ContainerData} — no UI scaling. The harness scales to flame /
 * arrow pixels when it needs a display fraction.
 *
 * <p>Null for every menu kind that is not a furnace family member; the
 * serializer omits the key entirely when null so non-furnace snapshots
 * stay byte-identical to the pre-progress wire shape.
 *
 * @param burnTime      remaining burn time of the current fuel (ticks);
 *                      0 when the furnace is not lit
 * @param totalBurnTime total burn time of the currently burning fuel
 *                      (ticks); 0 when no fuel has burned yet
 * @param cookProgress  current recipe progress (ticks); 0 when not
 *                      cooking or no input
 * @param cookTotal     total ticks required for the current recipe; 0
 *                      when no recipe is active
 */
public record MenuProgress(int burnTime, int totalBurnTime, int cookProgress, int cookTotal) {}
