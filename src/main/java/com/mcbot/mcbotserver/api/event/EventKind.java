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

    private EventKind() {
    }
}
