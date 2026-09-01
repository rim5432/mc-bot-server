package com.mcbot.mcbotserver.api.event;

/**
 * Registered event vocabulary for the boundary-D event stream.
 *
 * <p>Contract: see boundaries.md Boundary D protocol. Kinds are data,
 * not an enum — the type table grows by registration (disclosure-
 * patterns.md section 2), and unknown kinds degrade to a raw-text
 * fallback on the harness side, never to a crash.
 *
 * <p>Implementation note: Stage 0 ships only the kinds the gate tests
 * need; Stage 1 adds the task and world-event vocabulary.
 */
public final class EventKind {

    /** Synthetic event inserted when the queue had to drop entries. */
    public static final String EVENT_DROPPED = "EVENT_DROPPED";

    /**
     * Synthetic event inserted at the head of a polled page when the
     * caller's {@code sinceEventId} is older than the queue's oldest
     * retained entry — the harness is behind, the gap between the two
     * cursors was lost to queue rotation. Distinct from EVENT_DROPPED:
     * that one fires at push time when the queue overflows; this one
     * fires at poll time when the caller finally notices the loss.
     * Carries attrs {@code count} (the number of lost events),
     * {@code since} (the caller's stale cursor), and {@code oldest}
     * (the id of the first entry the page actually contains). The
     * event is urgent because the harness's mental model of "what has
     * happened to the bot" is now wrong and must be reconciled via
     * {@code getState()} before it consumes the page.
     */
    public static final String EVENT_GAP = "EVENT_GAP";

    /** State snapshot pushed on transition; carries model-relevant fields. */
    public static final String STATE_PUSH = "STATE_PUSH";

    /** A well-formed command was refused for execution reasons (async). */
    public static final String TASK_REJECTED = "TASK_REJECTED";

    /** An accepted task reached its goal successfully. */
    public static final String TASK_COMPLETED = "TASK_COMPLETED";

    /** An accepted task failed during execution (async execution error). */
    public static final String TASK_FAILED = "TASK_FAILED";

    /**
     * A running mission was parked by a reflex preemption; urgent —
     * the harness's mental model ("task is running") is now wrong.
     */
    public static final String TASK_PAUSED = "TASK_PAUSED";

    /** A parked mission revalidated cleanly and holds the body again. */
    public static final String TASK_RESUMED = "TASK_RESUMED";

    /**
     * A parked mission failed world revalidation and was dropped;
     * urgent — the task will never complete and the harness must
     * replan.
     */
    public static final String TASK_DROPPED = "TASK_DROPPED";

    /** The harness cancelled a still-running task via cancel(). */
    public static final String TASK_CANCELLED = "TASK_CANCELLED";

    /**
     * The tick pipeline caught a RuntimeException; carries the crash
     * summary per ADR-0005 D4 (primary channel).
     */
    public static final String BOT_CRASHED = "BOT_CRASHED";

    /**
     * Periodic liveness ping emitted by the tick loop. Carries a
     * snapshot of plan-progress state (pose, waypointIndex,
     * waypointsTotal, ticksSincePlanProgress, ticksSincePlan,
     * planAge, noPathWitnesses, goalCell) so a
     * harness replaying the event stream can distinguish "bot is
     * alive and making progress" from "bot is alive but the
     * planner is silent" (issue 0001 §4 / fix 2). Not urgent; the
     * harness is expected to ignore-unknown on S-F verifier
     * upgrade paths until the replay contract formally adopts it.
     */
    public static final String KEEPALIVE = "KEEPALIVE";

    /** A dig mission broke its target block (issue 0013, absorbing
     *  0009 F3): pos and blockId identify the cell, taskId
     *  correlates with the task stream. */
    public static final String BLOCK_BROKEN = "BLOCK_BROKEN";

    /**
     * The eat reflex decided to consume food and selected the target
     * hotbar slot (pre-selection disclosure, ledger 34 extension).
     * Fires at the reflex decision tick, before the adapter consumes
     * the item — the harness sees WHY the mission was preempted and
     * WHAT will be eaten. Carries attrs {@code foodLevel},
     * {@code trigger} (the rule's food threshold), {@code slot},
     * {@code itemId}, {@code nutrition}, {@code saturationModifier},
     * {@code source} ("reflex" or "harness"). Urgent because the
     * reflex claim parks the current mission on the same tick.
     */
    public static final String EAT_STARTED = "EAT_STARTED";

    /**
     * One food item was consumed through the vanilla finishUsingItem
     * chain (ledger 34). Carries attrs {@code itemId}, {@code slot},
     * {@code nutrition}, {@code saturationGained},
     * {@code foodLevelBefore}, {@code foodLevelAfter},
     * {@code saturationBefore}, {@code saturationAfter},
     * {@code source}. Not urgent — the harness learns the result at
     * its next event drain.
     */
    public static final String EAT_COMPLETED = "EAT_COMPLETED";

    /**
     * An eat attempt reached the adapter but consumed nothing. Carries
     * attrs {@code reason} (NOT_HUNGRY / NO_FOOD / NOT_EDIBLE /
     * SLOT_EMPTY), {@code slot}, {@code itemId}. Not urgent.
     */
    public static final String EAT_FAILED = "EAT_FAILED";

    /**
     * The drink path is about to consume a potion (pre-consumption
     * disclosure, consumable-face extension of EAT_STARTED). Fires
     * when the adapter detects a PotionItem in the selected slot and
     * is about to call finishUsingItem — the harness sees WHAT will
     * be drunk before the 32-tick use animation resolves. Carries
     * attrs {@code potionId} (the potion registry id, e.g.
     * "minecraft:strong_healing"), {@code slot}, {@code health}
     * (current health at drink start, for reflex correlation),
     * {@code source} ("reflex" or "harness"). Urgent because a
     * future drink reflex may park the current mission on the same
     * tick; P0 is harness-driven only.
     */
    public static final String DRINK_STARTED = "DRINK_STARTED";

    /**
     * One potion was consumed through the vanilla finishUsingItem
     * chain (consumable-face extension of EAT_COMPLETED). Carries
     * attrs {@code potionId}, {@code slot}, {@code effects}
     * (comma-separated "id:amplifier:duration"; instantaneous
     * effects have duration 0), {@code containerType}
     * ("glass_bottle" or "none"), {@code source}. Not urgent — the
     * harness learns the result at its next event drain.
     */
    public static final String DRINK_COMPLETED = "DRINK_COMPLETED";

    /**
     * A drink attempt reached the adapter but consumed nothing.
     * Carries attrs {@code reason} (NOT_DRINKABLE / SLOT_EMPTY /
     * NO_POTION), {@code slot}, {@code itemId}, {@code source}.
     * Not urgent.
     */
    public static final String DRINK_FAILED = "DRINK_FAILED";

    private EventKind() {}
}
