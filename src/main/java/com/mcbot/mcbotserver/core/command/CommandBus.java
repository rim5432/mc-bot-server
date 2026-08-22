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
    // Idempotency cache: maps the effective dedupe key (explicit
    // idempotencyKey from the harness, or the verb+args fallback hash
    // when the harness passed null) to the taskId the first submit
    // produced. Lives until the task reaches a terminal state; the
    // dedupe window is therefore "while the task is live". Two submits
    // with the same key inside the window collapse to the first. When
    // the task ends (cancel or finishTask), the entry is evicted and
    // the next submit with the same key is a fresh acceptance - the
    // LLM-friendly behaviour, see boundaries.md idempotency key
    // section.
    private final Map<String, String> idempotencyCache = new HashMap<>();
    // Reverse index: taskId -> effective idempotency key. Lets
    // cancel() and finishTask() drop the cache entry in O(1) when the
    // task leaves the live set, without scanning the whole cache.
    private final Map<String, String> taskIdToIdempotencyKey = new HashMap<>();
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
    public SubmitResult submit(BotCommand command, String idempotencyKey) {
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
        // C-scheme dedupe: explicit key wins; null falls back to a
        // verb+args hash so the harness can stay dumb and still get
        // retry protection. The cache lives in this bus only;
        // a bot restart wipes it alongside the event queue (the
        // resetAt contract covers both).
        String effectiveKey = idempotencyKey != null
            ? idempotencyKey
            : "auto:" + command.verb() + ":" + canonicalArgs(command.args());
        String cachedTaskId = idempotencyCache.get(effectiveKey);
        if (cachedTaskId != null) {
            return new SubmitResult.Ok(cachedTaskId, true);
        }
        String taskId = nextTaskId();
        activeTasks.add(taskId);
        taskVerbs.put(taskId, command.verb());
        idempotencyCache.put(effectiveKey, taskId);
        taskIdToIdempotencyKey.put(taskId, effectiveKey);
        handler.execute(command, taskId);
        return new SubmitResult.Ok(taskId, false);
    }

    /**
     * Canonical form of the args map for fallback dedupe. The order
     * matters because two equal-by-content maps with different
     * iteration order would otherwise hash to different keys and
     * the same logical command would slip past the dedupe.
     *
     * <p>Why the sort is required: the args map arrives via
     * {@link BotCommand}, whose canonical constructor runs
     * {@code args = Map.copyOf(args)}. {@code Map.copyOf} returns
     * an immutable map that preserves the caller's insertion
     * order (it is not a HashMap and not a sorted view). Two
     * callers passing the same logical args in different
     * literal order - {@code Map.of("x", "1", "y", "2")} vs
     * {@code Map.of("y", "2", "x", "1")} - get equal maps with
     * different iteration orders, and the dedupe would treat
     * them as different commands. Sorting by key with a
     * {@code TreeMap} is the cheapest way to make the
     * canonicalisation indifferent to the caller's key order.
     *
     * <p>Why a sort and not a hash: the cache key needs to be
     * stable across the dedupe window (which can outlive a
     * single process if the bot migrates state in some future
     * build). A 32-bit hash would be sufficient within a single
     * JVM but would couple dedupe to the hash function; a
     * collision would silently merge two distinct commands.
     * Keeping the canonical form as readable text means a
     * debug log of the cache can be grepped for the args that
     * produced a given key. The verb-level prefix in
     * {@link #submit} keeps this string from colliding with a
     * future verb whose id happens to start with the same
     * characters.
     *
     * @param args command args; never null
     * @return stable string for hashing; empty args yield
     *         {@code "{}"} (the distinct empty-args case)
     */
    private static String canonicalArgs(
            java.util.Map<String, String> args) {
        if (args.isEmpty()) {
            return "{}";
        }
        java.util.TreeMap<String, String> sorted =
            new java.util.TreeMap<>(args);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    @Override
    public boolean cancel(String taskId) {
        String verb = taskVerbs.get(taskId);
        boolean removed = activeTasks.remove(taskId);
        if (removed) {
            // Idempotency window ends with the task - any retry
            // that arrives after cancel will be a fresh submit,
            // not a deduped replay of the cancelled one.
            String idemKey = taskIdToIdempotencyKey.remove(taskId);
            if (idemKey != null) {
                idempotencyCache.remove(idemKey, taskId);
            }
            if (cancelListener != null && verb != null) {
                cancelListener.onCancelled(taskId, verb);
            }
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
        // Terminal state closes the dedupe window the same way cancel
        // does. A retry that arrives after the task finished must see
        // a fresh taskId, not a replay of one that no longer exists.
        String idemKey = taskIdToIdempotencyKey.remove(taskId);
        if (idemKey != null) {
            idempotencyCache.remove(idemKey, taskId);
        }
        events.push(new BotEvent(kind, day, t, true,
            Map.of("taskId", taskId), text));
    }

    private String nextTaskId() {
        taskSequence++;
        return "task-" + taskSequence;
    }
}
