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
     * ticksSinceProgress, ticksSincePlan, planAge, goalCell) so a
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

    private EventKind() {
    }
}
