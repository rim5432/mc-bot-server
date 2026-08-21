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
    }

    /**
     * Reflex-layer entry point: park whoever currently holds the body,
     * keeping their plan intact for resume.
     *
     * @param context snapshot of the preemption moment; never null
     * @return true when a running mission was actually parked
     */
    public boolean forcePauseAll(InterruptionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (current == null) {
            return false;
        }
        current.onLostControl(context);
        pending.remove(current);
        paused = current;
        pauseContext = context;
        current = null;
        lastDirective = null;
        return true;
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
