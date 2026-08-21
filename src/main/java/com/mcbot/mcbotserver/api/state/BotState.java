package com.mcbot.mcbotserver.api.state;

import com.mcbot.mcbotserver.api.types.CellPos;

import java.util.Map;

/**
 * Boundary-D state snapshot: only model-relevant categorical fields.
 *
 * <p>Contract: see boundaries.md Boundary D protocol, state snapshot
 * section. The in/out rule is deliberate and load-bearing: change
 * detection compares these fields, so anything continuous or
 * fast-moving (durability, enchantment progress, effect remaining
 * ticks) is deliberately absent — including them would push a packet
 * every tick and bury the harness. Durations may be sent once; the
 * consumer ticks them down locally.
 *
 * @param pos                block cell the bot occupies; never null
 * @param yaw                body yaw in degrees
 * @param pitch              view pitch in degrees
 * @param dimension          dimension key ("overworld" style); never null
 * @param itemCounts         inventory as item type to count; durability
 *                           and enchantments are not represented;
 *                           never null
 * @param selectedHotbarSlot active hotbar index 0..8
 * @param effectAmplifiers   active effects as type to amplifier level;
 *                           remaining ticks deliberately absent;
 *                           never null
 * @param currentTaskSummary one-line description of the active task or
 *                           "idle"; never null
 */
public record BotState(
    CellPos pos,
    float yaw,
    float pitch,
    String dimension,
    Map<String, Integer> itemCounts,
    int selectedHotbarSlot,
    Map<String, Integer> effectAmplifiers,
    String currentTaskSummary) {

    /**
     * Creates a validated snapshot.
     *
     * @param pos                must not be null
     * @param yaw                any angle
     * @param pitch              any angle
     * @param dimension          must not be null
     * @param itemCounts         must not be null
     * @param selectedHotbarSlot 0..8
     * @param effectAmplifiers   must not be null
     * @param currentTaskSummary must not be null
     */
    public BotState {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (dimension == null) {
            throw new IllegalArgumentException(
                "dimension must not be null");
        }
        itemCounts = Map.copyOf(itemCounts);
        if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException(
                "selectedHotbarSlot must be 0..8");
        }
        effectAmplifiers = Map.copyOf(effectAmplifiers);
        if (currentTaskSummary == null) {
            throw new IllegalArgumentException(
                "currentTaskSummary must not be null");
        }
    }
}
