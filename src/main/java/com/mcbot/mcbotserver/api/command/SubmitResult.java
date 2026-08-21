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
     * @param taskId unique id for tracking and cancellation; never null
     */
    record Ok(String taskId) implements SubmitResult {
    }

    /**
     * The command was structurally invalid and was not accepted. The
     * reason is a short human-readable structural cause (unknown verb,
     * malformed argument, failed validation) — never an execution error.
     *
     * @param reason why the command was refused; never null or blank
     */
    record Rejected(String reason) implements SubmitResult {
    }
}
