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
 * @param healthHearts       health bucketed to whole hearts
 *                           (0..10; 20 HP = 10 hearts, vanilla's
 *                           2 HP per heart); bucketing keeps the
 *                           change-detect cadence honest - a raw
 *                           float would push on every regen tick
 *                           (issue 0013 R5)
 * @param freeSlots          count of empty main-inventory slots; rides
 *                           the same snapshot loop as itemCounts
 * @param foodLevel          hunger 0..20, vanilla FoodData semantics
 *                           (ledger 34); entered through the H-R4
 *                           friction protocol with this component
 */
// contract: see boundaries.md Boundary D protocol (state snapshot shape)
public record BotState(
        CellPos pos,
        float yaw,
        float pitch,
        String dimension,
        Map<String, Integer> itemCounts,
        int selectedHotbarSlot,
        Map<String, Integer> effectAmplifiers,
        String currentTaskSummary,
        int healthHearts,
        int freeSlots,
        int foodLevel) {

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
     * @param healthHearts       0..10 (whole hearts; 20 HP = 10)
     * @param freeSlots          non-negative
     */
    public BotState {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        itemCounts = Map.copyOf(itemCounts);
        if (healthHearts < 0) {
            throw new IllegalArgumentException("healthHearts must not be negative");
        }
        if (freeSlots < 0) {
            throw new IllegalArgumentException("freeSlots must not be negative");
        }
        if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("selectedHotbarSlot must be 0..8");
        }
        effectAmplifiers = Map.copyOf(effectAmplifiers);
        if (currentTaskSummary == null) {
            throw new IllegalArgumentException("currentTaskSummary must not be null");
        }
    }
}
