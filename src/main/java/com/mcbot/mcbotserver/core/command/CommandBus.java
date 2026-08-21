package com.mcbot.mcbotserver.core.command;

import com.mcbot.mcbotserver.api.command.BotCommand;
import com.mcbot.mcbotserver.api.command.CommandChannel;
import com.mcbot.mcbotserver.api.command.SubmitResult;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reference command router: structural validation is synchronous, all
 * execution feedback travels through the event stream.
 *
 * <p>Contract: see boundaries.md Boundary D protocol invariant 5 — the
 * sync/async split. {@code submit} answers Ok/Rejected before returning
 * (caller's fault = sync); a handler that cannot execute an accepted
 * task emits TASK_REJECTED / TASK_FAILED onto the queue (world's state =
 * async). The bus never reports execution results through its return
 * value.
 *
 * <p>Implementation note: single-threaded by policy; every method runs
 * on the server tick thread.
 */
// contract: see boundaries.md Boundary D protocol (invariant 5)
public final class CommandBus implements CommandChannel {

    /** Handler SPI: one registration per verb, validated at submit. */
    public interface Handler {

        /**
         * Validate structure beyond verb dispatch.
         *
         * @param command the submitted command; never null
         * @return rejection reason, or null when structurally valid
         */
        String validate(BotCommand command);

        /**
         * Execute the accepted task. Must report its outcome by pushing
         * TASK_* events onto the injected queue; must not throw for
         * execution problems (report those as TASK_FAILED instead).
         *
         * @param command the accepted command; never null
         * @param taskId  id this bus assigned; never null
         */
        void execute(BotCommand command, String taskId);
    }

    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, String> taskVerbs = new HashMap<>();
    private final Set<String> activeTasks = new HashSet<>();
    private final EventQueue events;
    private long taskSequence;
    private CancelListener cancelListener;

    /** Hook for verb owners to react to harness cancellation. */
    @FunctionalInterface
    public interface CancelListener {

        /**
         * Called after a successful cancel, before the method returns.
         *
         * @param taskId the cancelled id; never null
         * @param verb   the verb it was submitted under; never null
         */
        void onCancelled(String taskId, String verb);
    }

    /**
     * Creates a bus reporting lifecycle over the given stream.
     *
     * @param events the boundary-D event stream; never null
     */
    public CommandBus(EventQueue events) {
        if (events == null) {
            throw new IllegalArgumentException("events must not be null");
        }
        this.events = events;
    }

    /**
     * Register the handler for a verb. Re-registration replaces.
     *
     * @param verb    dispatch key; must not be blank
     * @param handler the handler; must not be null
     */
    public void register(String verb, Handler handler) {
        if (verb == null || verb.isBlank()) {
            throw new IllegalArgumentException("verb must not be blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        handlers.put(verb, handler);
    }

    /**
     * Install the single cancellation hook. Verb owners route by the
     * verb argument; replacing an existing listener is a wiring bug.
     *
     * @param listener the hook; must not be null
     */
    public void setCancelListener(CancelListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException(
                "listener must not be null");
        }
        this.cancelListener = listener;
    }

    @Override
    public SubmitResult submit(BotCommand command) {
        if (command == null) {
            return new SubmitResult.Rejected("null command");
        }
        Handler handler = handlers.get(command.verb());
        if (handler == null) {
            return new SubmitResult.Rejected(
                "unknown verb: " + command.verb());
        }
        String reason = handler.validate(command);
        if (reason != null) {
            return new SubmitResult.Rejected(reason);
        }
        String taskId = nextTaskId();
        activeTasks.add(taskId);
        taskVerbs.put(taskId, command.verb());
        handler.execute(command, taskId);
        return new SubmitResult.Ok(taskId);
    }

    @Override
    public boolean cancel(String taskId) {
        String verb = taskVerbs.get(taskId);
        boolean removed = activeTasks.remove(taskId);
        if (removed && cancelListener != null && verb != null) {
            cancelListener.onCancelled(taskId, verb);
        }
        return removed;
    }

    /**
     * Convenience for handlers: emit a terminal task event with the id
     * attached, and retire the task so cancel() answers false after it.
     *
     * @param taskId owning task id; never null
     * @param kind   one of EventKind.TASK_COMPLETED / TASK_FAILED /
     *               TASK_REJECTED; never null
     * @param day    game day stamp; non-negative
     * @param t      time-of-day stamp; non-negative
     * @param text   short human-readable outcome; never null
     */
    public void finishTask(String taskId, String kind, long day, long t,
                           String text) {
        activeTasks.remove(taskId);
        events.push(new BotEvent(kind, day, t, true,
            Map.of("taskId", taskId), text));
    }

    private String nextTaskId() {
        taskSequence++;
        return "task-" + taskSequence;
    }
}
