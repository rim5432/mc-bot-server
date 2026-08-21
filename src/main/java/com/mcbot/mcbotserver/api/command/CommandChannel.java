package com.mcbot.mcbotserver.api.command;

/**
 * Boundary-D command channel: the harness pulls by calling, the bot
 * answers synchronously with acceptance or structural rejection.
 *
 * <p>Contract: see boundaries.md Boundary D protocol invariants 5 and 6.
 * {@code submit} validates structure only; every execution feedback
 * (running, completed, failed, stuck, timed out) travels through the
 * event stream, never back through this channel. The bot knows nothing
 * about who calls — no LLM awareness, no consumer identity.
 */
public interface CommandChannel {

    /**
     * Validate a command structurally and accept it for asynchronous
     * execution.
     *
     * @param command the harness-issued command; must not be null
     * @return {@link SubmitResult.Ok} with a fresh task id when the
     *         command is well-formed; {@link SubmitResult.Rejected} with
     *         the structural cause otherwise — never null
     */
    SubmitResult submit(BotCommand command);

    /**
     * Request cancellation of a previously accepted task.
     *
     * @param taskId id returned by {@link #submit}; must not be null
     * @return true if the task was known and cancellation was issued;
     *         false if the id is unknown or the task was already terminal
     */
    boolean cancel(String taskId);
}
