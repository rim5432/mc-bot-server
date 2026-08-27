package com.mcbot.mcbotserver.api.command;

/**
 * Synchronous answer from the boundary-D command channel.
 *
 * <p>Contract: see boundaries.md Boundary D protocol invariant 5 — the
 * sync error path is for structural failures only. A well-formed command
 * yields {@link Ok} and its execution result arrives later as
 * {@code TASK_*} events on the event stream; a malformed one yields
 * {@link Rejected} here, now, because that is the caller's fault.
 */
public sealed interface SubmitResult {

    /**
     * The command was structurally valid and has been accepted for
     * asynchronous execution under the given task id.
     *
     * <p>Contract: see boundaries.md Boundary D protocol - the
     * idempotency-key section. {@code idempotencyReplay} is {@code true}
     * when this {@code Ok} answers a deduped re-submission rather than a
     * fresh execution; the {@code taskId} is the original one, the
     * caller may treat the answer as a pointer and not a new event to
     * schedule. A {@code false} value is the historical default
     * (single-submission per {@code taskId}) and is what every
     * first-time submit returns.
     *
     * @param taskId           unique id for tracking and cancellation;
     *                         never null
     * @param idempotencyReplay true when this Ok is a deduped replay of
     *                         a prior accepted submission
     */
    record Ok(String taskId, boolean idempotencyReplay) implements SubmitResult {

        /**
         * Canonical form for a fresh acceptance. Equivalent to
         * {@code new Ok(taskId, false)}.
         *
         * @param taskId unique id for tracking; never null
         * @return a non-replay Ok for the first-time submission
         */
        public static Ok fresh(String taskId) {
            return new Ok(taskId, false);
        }

        /**
         * Canonical form for a deduped replay. Equivalent to
         * {@code new Ok(taskId, true)}.
         *
         * @param taskId the original task id that this replay points to;
         *               never null
         * @return a replay Ok whose taskId is the prior acceptance's id
         */
        public static Ok replay(String taskId) {
            return new Ok(taskId, true);
        }
    }

    /**
     * The command was structurally invalid and was not accepted. The
     * reason is a short human-readable structural cause (unknown verb,
     * malformed argument, failed validation) — never an execution error.
     *
     * @param reason why the command was refused; never null or blank
     */
    record Rejected(String reason) implements SubmitResult {}
}
