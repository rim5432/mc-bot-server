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
     * execution, with an explicit idempotency key.
     *
     * <p>Contract: see boundaries.md Boundary D protocol - idempotency
     * key section. {@code idempotencyKey} is the harness-side dedupe
     * signal: when non-null, two submits with the same key collapse to
     * the first one and the second returns
     * {@link SubmitResult.Ok#idempotencyReplay()} = {@code true}. When
     * null, the implementation MAY apply a fallback dedupe by
     * {@code verb + canonical(args)}; whether it does is the
     * implementation's choice, but the contract is that the seam
     * accepts a null key as "no explicit preference".
     *
     * @param command        the harness-issued command; must not be null
     * @param idempotencyKey optional dedupe key; may be null
     * @return {@link SubmitResult.Ok} with a task id when the command
     *         is well-formed (the same id on a deduped replay);
     *         {@link SubmitResult.Rejected} with the structural cause
     *         otherwise — never null
     */
    SubmitResult submit(BotCommand command, String idempotencyKey);

    /**
     * Convenience for callers that do not carry an explicit
     * idempotency key. Equivalent to {@code submit(command, null)};
     * the implementation decides whether to apply a fallback dedupe.
     *
     * <p>Default method: implementations override the two-argument
     * form. The default does not double-dispatch into the override
     * (the override is the source of truth) - this default is the
     * safety net for harness-side code that does not care about
     * idempotency.
     *
     * @param command the harness-issued command; must not be null
     * @return never null
     */
    default SubmitResult submit(BotCommand command) {
        return submit(command, null);
    }

    /**
     * Request cancellation of a previously accepted task.
     *
     * @param taskId id returned by {@link #submit}; must not be null
     * @return true if the task was known and cancellation was issued;
     *         false if the id is unknown or the task was already terminal
     */
    boolean cancel(String taskId);
}
