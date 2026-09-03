package com.mcbot.mcbotserver.api.event;

import java.util.HashMap;
import java.util.Map;

/**
 * Single construction point for the fixed-shape boundary-D events
 * (the consume family, task cancellation, block-break disclosure,
 * crash disclosure):
 * plain values in, a validated {@link BotEvent} out. No MC types, no
 * side effects - the factory is pure, so the wire shape is offline-
 * testable. Emission itself - the push and its failure isolation -
 * stays with the caller.
 *
 * <p>Urgency is part of each kind's emission policy and is decided
 * here, not by callers: STARTED kinds are urgent (the reflex already
 * preempted the mission - the harness's "task is running" model is
 * wrong right now), terminal and failure kinds are not.
 *
 * <p>Attr schema home: WireVocabularyGateTest KIND_ATTRS (H-R4) owns
 * the per-kind key table; this class is a registered producer there,
 * and a runtime assertion calls every factory against the table, so
 * the keys below are held in set-equality with the registered
 * vocabulary offline. The attrs are built as one {@code .put} per key
 * (not a multi-key {@code Map.of}) so the gate's literal scan sees
 * every key.
 *
 * <p>Zero-wire-change rule: the attr keys, value formatting, text
 * renderings and urgent flags below are byte-identical to the emit
 * sites this class absorbed (BindingActor + BotController). Any edit
 * here is a wire-vocabulary change and reopens the harness-interaction
 * audit (boundaries.md Boundary D).
 */
public final class EventFacts {

    private EventFacts() {}

    /**
     * Builds an EAT_STARTED event (urgent: the eat reflex preempted the
     * mission this tick).
     *
     * @param slot      hotbar slot the reflex is about to select; 0..8
     * @param foodLevel current food level 0..20 at start
     * @param ruleName  firing reflex rule; never null
     * @param source    emission source tag; never null
     * @param day       game day at emission
     * @param tod       time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent eatStarted(int slot, int foodLevel, String ruleName, String source, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("foodLevel", Integer.toString(foodLevel));
        attrs.put("slot", Integer.toString(slot));
        attrs.put("ruleName", ruleName);
        attrs.put("source", source);
        return new BotEvent(
                EventKind.EAT_STARTED,
                day,
                tod,
                true,
                Map.copyOf(attrs),
                "eat started: foodLevel=" + foodLevel + " slot=" + slot + " rule=" + ruleName);
    }

    /**
     * Builds an EAT_COMPLETED event.
     *
     * @param itemId         registry id of the eaten item; never null
     * @param slot           hotbar slot it was eaten from; 0..8
     * @param nutrition      vanilla nutrition value of the item
     * @param satBefore      saturation before the bite
     * @param satAfter       saturation after the bite; the gained attr
     *                       is {@code satAfter - satBefore}
     * @param foodBefore     food level before the bite; 0..20
     * @param foodAfter      food level after the bite; 0..20
     * @param containerType  container id left behind, or ""; never null
     * @param source         emission source tag; never null
     * @param day            game day at emission
     * @param tod            time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent eatCompleted(
            String itemId,
            int slot,
            int nutrition,
            float satBefore,
            float satAfter,
            int foodBefore,
            int foodAfter,
            String containerType,
            String source,
            long day,
            long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("itemId", itemId);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("nutrition", Integer.toString(nutrition));
        attrs.put("saturationGained", Float.toString(satAfter - satBefore));
        attrs.put("foodLevelBefore", Integer.toString(foodBefore));
        attrs.put("foodLevelAfter", Integer.toString(foodAfter));
        attrs.put("saturationBefore", Float.toString(satBefore));
        attrs.put("saturationAfter", Float.toString(satAfter));
        attrs.put("containerType", containerType);
        attrs.put("source", source);
        return new BotEvent(
                EventKind.EAT_COMPLETED,
                day,
                tod,
                false,
                Map.copyOf(attrs),
                "ate " + itemId + " (" + nutrition + " nutrition, food " + foodBefore + "->" + foodAfter + ")");
    }

    /**
     * Builds an EAT_FAILED event.
     *
     * @param reason typed failure reason (e.g. NOT_EDIBLE); never null
     * @param slot   hotbar slot the attempt targeted; 0..8
     * @param itemId registry id of the refused item; never null
     * @param source emission source tag; never null
     * @param day    game day at emission
     * @param tod    time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent eatFailed(String reason, int slot, String itemId, String source, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("reason", reason);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("itemId", itemId);
        attrs.put("source", source);
        return new BotEvent(
                EventKind.EAT_FAILED,
                day,
                tod,
                false,
                Map.copyOf(attrs),
                "eat failed: " + reason + " (slot " + slot + ", item " + itemId + ")");
    }

    /**
     * Builds a DRINK_STARTED event (urgent: the drink reflex preempted
     * the mission this tick).
     *
     * @param potionId registry id of the potion; never null
     * @param slot     hotbar slot it is drunk from; 0..8
     * @param health   body health at start, vanilla 0..20 scale
     * @param source   emission source tag; never null
     * @param day      game day at emission
     * @param tod      time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent drinkStarted(String potionId, int slot, float health, String source, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("potionId", potionId);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("health", Float.toString(health));
        attrs.put("source", source);
        return new BotEvent(
                EventKind.DRINK_STARTED,
                day,
                tod,
                true,
                Map.copyOf(attrs),
                "drink started: " + potionId + " (slot " + slot + ", health " + health + ")");
    }

    /**
     * Builds a DRINK_COMPLETED event.
     *
     * @param potionId         registry id of the potion; never null
     * @param slot             hotbar slot it was drunk from; 0..8
     * @param effects          comma-separated "id:amplifier:duration"
     *                         triples, "" when none; never null
     * @param containerType    container id left behind, or ""; never
     *                         null
     * @param containerDropped whether the empty container landed in the
     *                         body inventory (vanilla drops it when the
     *                         inventory is full)
     * @param source           emission source tag; never null
     * @param day              game day at emission
     * @param tod              time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent drinkCompleted(
            String potionId,
            int slot,
            String effects,
            String containerType,
            boolean containerDropped,
            String source,
            long day,
            long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("potionId", potionId);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("effects", effects);
        attrs.put("containerType", containerType);
        attrs.put("containerDropped", Boolean.toString(containerDropped));
        attrs.put("source", source);
        return new BotEvent(
                EventKind.DRINK_COMPLETED,
                day,
                tod,
                false,
                Map.copyOf(attrs),
                "drank " + potionId + " (effects: " + effects + ")");
    }

    /**
     * Builds a DRINK_FAILED event.
     *
     * @param reason typed failure reason (e.g. NO_POTION); never null
     * @param slot   hotbar slot the attempt targeted; 0..8
     * @param itemId registry id of the refused item; never null
     * @param source emission source tag; never null
     * @param day    game day at emission
     * @param tod    time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent drinkFailed(String reason, int slot, String itemId, String source, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("reason", reason);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("itemId", itemId);
        attrs.put("source", source);
        return new BotEvent(
                EventKind.DRINK_FAILED,
                day,
                tod,
                false,
                Map.copyOf(attrs),
                "drink failed: " + reason + " (slot " + slot + ", item " + itemId + ")");
    }

    /**
     * Builds a POTION_THROWN event.
     *
     * @param potionId  registry id of the thrown potion; never null
     * @param slot      hotbar slot it was thrown from; 0..8
     * @param throwType splash or lingering; never null
     * @param effects   comma-separated "id:amplifier:duration" triples;
     *                  never null
     * @param source    emission source tag; never null
     * @param day       game day at emission
     * @param tod       time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent potionThrown(
            String potionId, int slot, String throwType, String effects, String source, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("potionId", potionId);
        attrs.put("slot", Integer.toString(slot));
        attrs.put("throwType", throwType);
        attrs.put("effects", effects);
        attrs.put("source", source);
        return new BotEvent(
                EventKind.POTION_THROWN,
                day,
                tod,
                false,
                Map.copyOf(attrs),
                "threw " + throwType + " " + potionId + " (effects: " + effects + ")");
    }
    /**
     * Builds a TASK_CANCELLED event (harness cancellation of one task
     * verb; not urgent - the cancel is the harness's own action).
     *
     * @param task     display name of the cancelled mission; never null
     * @param taskId   boundary-D task id; never null
     * @param day      game day at emission
     * @param tod      time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent taskCancelled(String task, String taskId, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("task", task);
        attrs.put("taskId", taskId);
        return new BotEvent(
                EventKind.TASK_CANCELLED, day, tod, false, Map.copyOf(attrs), task + ": cancelled by harness");
    }

    /**
     * Builds a BLOCK_BROKEN event (dig/mine disclosure; not urgent -
     * the harness polls yields, it does not preempt on them).
     *
     * @param taskId  owning mission's boundary-D task id; never null
     * @param x       broken block cell x
     * @param y       broken block cell y
     * @param z       broken block cell z
     * @param blockId registry id of the broken block; never null
     * @param day     game day at emission
     * @param tod     time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent blockBroken(String taskId, int x, int y, int z, String blockId, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("taskId", taskId);
        attrs.put("posX", String.valueOf(x));
        attrs.put("posY", String.valueOf(y));
        attrs.put("posZ", String.valueOf(z));
        attrs.put("blockId", blockId);
        return new BotEvent(EventKind.BLOCK_BROKEN, day, tod, false, Map.copyOf(attrs), taskId + ": broke " + blockId);
    }

    /**
     * Builds a BOT_CRASHED event (urgent: the crash latch froze the
     * pipeline - the harness must learn before its next poll).
     *
     * @param causeSummary one-line crash cause summary; never null
     * @param day          game day at emission
     * @param tod          time-of-day ticks at emission
     * @return the validated event; never null
     */
    public static BotEvent botCrashed(String causeSummary, long day, long tod) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("cause", causeSummary);
        return new BotEvent(EventKind.BOT_CRASHED, day, tod, true, Map.copyOf(attrs), "bot crashed: " + causeSummary);
    }
}
