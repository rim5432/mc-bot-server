package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.PriorityBands;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Winner-take-all task arbiter: at most one mission runs; fresh intents
 * enter the queue head and ties defer to the fresher claim.
 *
 * <p>Contract: see ADR-0002 section 2 and boundaries.md decision ledger
 * item 6. Reflex preemption bypasses this class numerically — the
 * pipeline calls {@link #forcePauseAll} directly, which parks the
 * current winner with its interruption snapshot; {@link #tryResume}
 * revalidates through the process's own {@code resume} answer and drops
 * invalidated missions instead of resuming them (ADR-0003 section 3).
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
// contract: see ADR-0002 section 2 (winner-take-all, DEFER, fresh-first)
public final class TaskArbiter {

    private final Deque<BotProcess> pending = new ArrayDeque<>();
    private BotProcess current;
    private BotProcess paused;
    private InterruptionContext pauseContext;
    /**
     * Directive from the most recent {@link #tick}; consumed by the
     * pipeline's behavior stage only. tryResume NEVER reads it - resume
     * re-runs the mission's own onTick, which produces a fresh
     * directive - so nulling it on park/retire loses nothing.
     */
    private Directive lastDirective;

    /**
     * Register a process for future selection. Registration validates
     * the priority band once; a violation is a programming error.
     *
     * @param process the candidate; must not be null
     * @throws IllegalArgumentException when the priority is outside the
     *         legal bands per {@link PriorityBands#requireLegal}
     */
    public void register(BotProcess process) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        PriorityBands.requireLegal(process.priority());
        pending.addLast(process);
    }

    /**
     * Enter an intent into selection. Semantics: move to the queue
     * head — re-requesting an already-pending process is not a
     * duplicate entry but a freshness bump, so equal-priority ties
     * always defer to the most recent claim.
     *
     * @param process the requesting process; must not be null
     */
    public void requestControl(BotProcess process) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        pending.remove(process);
        pending.addFirst(process);
    }

    /**
     * Run one arbiter stage: keep or replace the winner, then produce
     * its directive.
     *
     * @param world read-only perception passed through to the winner;
     *              never null
     */
    public void tick(WorldView world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (paused != null && !paused.isActive()) {
            // Defensive: a parked mission that died while parked must
            // not haunt tryResume forever.
            paused = null;
            pauseContext = null;
        }
        if (current != null && !current.isActive()) {
            retireCurrent();
        }
        if (current == null) {
            current = selectWinner();
        }
        lastDirective = current != null ? current.onTick(world) : null;
        // Deliberately NO tail sweep here: a mission that died during
        // its own onTick must survive until the NEXT tick's head
        // sweep, so the pipeline's transition emitter sees
        // previous != now and announces the verdict (one-tick latency,
        // per boundary B). Retiring inside tick() would orphan the
        // announcement - gametest scenario 5 proved exactly that.
    }

    /**
     * Outcome of {@link #forcePauseAll}: explicit so the caller never
     * infers state from side effects.
     */
    public enum ParkResult {

        /** A live mission was parked; TASK_PAUSED is owed. */
        PARKED,

        /**
         * The seated mission was ALREADY terminal (retirement-lap
         * corpse): retired, not parked - the transition emitter owes
         * its verdict this very tick.
         */
        RETIRED_TERMINAL,

        /** Nothing held the body. */
        NO_CURRENT,
    }

    /**
     * Reflex-layer entry point: park whoever currently holds the body,
     * keeping their plan intact for resume.
     *
     * <p>Preemption-vs-completion semantics: a terminal mission is
     * RETIRED, never parked - parking would swallow its verdict behind
     * a lying TASK_PAUSED and a silent cleanup. Only live missions
     * park. Window contract: after {@link #tick} returns, current is
     * live or null EXCEPT when death came from stage-3 reports in that
     * same tick; such a corpse survives until the next tick's head
     * sweep BY DESIGN, and every reader between ticks must treat a
     * non-active current as already-decided ({@link #forcePauseAll}
     * does exactly that).
     *
     * <p>Single-slot invariant: the paused slot holds ONE mission.
     * Parking a new mission while another is parked (the reflex-chain
     * shape: original parked by an engage submission, fight seated,
     * then a survival reflex parks the fight) would silently orphan
     * the old one - evicted from the slot, absent from pending, never
     * resumed, never dropped. Occupying the slot therefore first
     * requeues-or-drops the previous occupant through
     * {@link #requeuePausedOrDrop}. That internal call is the
     * invariant's safety net; callers that need the eviction outcome
     * as an event call the method themselves first (the arbiter never
     * emits).
     *
     * @param context snapshot of the preemption moment; never null
     * @return the outcome; never null
     */
    public ParkResult forcePauseAll(InterruptionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (current == null) {
            return ParkResult.NO_CURRENT;
        }
        if (!current.isActive()) {
            retireCurrent();
            lastDirective = null;
            return ParkResult.RETIRED_TERMINAL;
        }
        current.onLostControl(context);
        pending.remove(current);
        requeuePausedOrDrop();
        paused = current;
        pauseContext = context;
        current = null;
        lastDirective = null;
        return ParkResult.PARKED;
    }

    /** Outcome of {@link #requeuePausedOrDrop}. */
    public enum PausedEviction {

        /** Nothing was parked; no state changed. */
        NONE,

        /** The parked mission revalidated and re-entered pending. */
        REQUEUED,

        /** The parked mission was dropped via onContextInvalidated. */
        DROPPED
    }

    /**
     * Move the parked mission out of the single paused slot without
     * resuming it onto the body: it revalidates through its own
     * {@code resume(ctx)} exactly like {@link #tryResume} would - a
     * valid mission re-enters pending and competes again once the
     * body frees, an invalidated or dead one is dropped with
     * {@code onContextInvalidated}. Involuntary, but the same
     * revalidation the voluntary path runs; nothing is ever orphaned.
     *
     * @return what happened to the parked mission; never null
     */
    public PausedEviction requeuePausedOrDrop() {
        if (paused == null) {
            return PausedEviction.NONE;
        }
        BotProcess candidate = paused;
        InterruptionContext context = pauseContext;
        paused = null;
        pauseContext = null;
        if (candidate.isActive() && candidate.resume(context)) {
            pending.addLast(candidate);
            return PausedEviction.REQUEUED;
        }
        candidate.onContextInvalidated();
        return PausedEviction.DROPPED;
    }

    /**
     * Reflex layer released control; hand it back only if the parked
     * mission's world assumptions still hold — its own resume() decides.
     * An invalidated mission is dropped (onContextInvalidated) and the
     * queue proceeds normally next tick.
     *
     * @return true when the paused mission resumed cleanly
     */
    public boolean tryResume() {
        if (paused == null) {
            return false;
        }
        BotProcess candidate = paused;
        InterruptionContext context = pauseContext;
        paused = null;
        pauseContext = null;
        if (candidate.resume(context)) {
            current = candidate;
            return true;
        }
        candidate.onContextInvalidated();
        pending.remove(candidate);
        return false;
    }

    /**
     * The directive produced by the most recent {@link #tick}.
     *
     * @return latest directive; null when no mission ran this tick
     */
    public Directive lastDirective() {
        return lastDirective;
    }

    /**
     * Currently winning mission, for diagnostics and crash snapshots.
     *
     * @return active winner; null when the body is idle or paused
     */
    public BotProcess current() {
        return current;
    }

    /**
     * Mission parked by a reflex preemption, if any.
     *
     * @return the parked process; null when nothing is paused
     */
    public BotProcess paused() {
        return paused;
    }

    private BotProcess selectWinner() {
        purgeInactive();
        if (pending.isEmpty()) {
            return null;
        }
        BotProcess best = null;
        Iterator<BotProcess> it = pending.iterator();
        while (it.hasNext()) {
            BotProcess candidate = it.next();
            if (!candidate.isActive()) {
                it.remove();
                continue;
            }
            if (best == null || candidate.priority() > best.priority()) {
                best = candidate;
            }
        }
        if (best != null) {
            pending.remove(best);
        }
        return best;
    }

    private void purgeInactive() {
        pending.removeIf(p -> !p.isActive());
    }

    private void retireCurrent() {
        pending.remove(current);
        current = null;
    }
}
