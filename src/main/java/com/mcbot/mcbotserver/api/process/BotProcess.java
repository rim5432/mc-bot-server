package com.mcbot.mcbotserver.api.process;

import com.mcbot.mcbotserver.api.world.WorldView;

/**
 * The task-channel unit: a side-effect-free planner that turns world
 * perception into one {@link Directive} per tick. Processes never touch
 * the Actor, never mutate state outside themselves, and never compute
 * physics — adding a task must mean writing exactly one new Process and
 * zero behavior changes.
 *
 * <p>Contract: see ADR-0002 section 1 (fixed contract) and boundaries.md
 * section B. Lifecycle: register with the arbiter, request control,
 * receive ticks while the arbiter's winner; a reflex preemption delivers
 * {@link #onLostControl}, later recovery passes through
 * {@link #resume}, which must revalidate the recorded world assumptions
 * before trusting them (ADR-0003 section 3).
 *
 * <p>Implementation note: runs on the server tick thread only.
 */
public interface BotProcess {

    /**
     * Whether this process wants control right now. An inactive process
     * is skipped by selection and may be unregistered by the arbiter.
     *
     * @return true while the mission is live
     */
    boolean isActive();

    /**
     * Priority inside the band system; validated at registration.
     *
     * @return value legal per {@link PriorityBands#requireLegal}
     */
    int priority();

    /**
     * Produce this tick's directive.
     *
     * @param world read-only perception; never null
     * @return the directive for behaviors to execute; must not be null
     *         while active — returning null is an implementation bug
     */
    com.mcbot.mcbotserver.api.process.Directive onTick(WorldView world);

    /**
     * Reflex layer took control away. Keep every logical field intact —
     * the plan is what makes resume possible; release nothing here that
     * resume would need.
     *
     * @param context snapshot of the interruption moment; never null
     */
    void onLostControl(InterruptionContext context);

    /**
     * Reflex released control; revalidate the world assumptions from
     * the interruption snapshot before resuming.
     *
     * @param context the matching preemption snapshot; never null
     * @return true to take control back on the next tick; false when
     *         the world invalidated the plan — the arbiter then drops
     *         the mission instead of resuming it
     */
    boolean resume(InterruptionContext context);

    /**
     * Notification that this mission was dropped without resume —
     * either a failed revalidation or external invalidation. Last chance
     * to release resources; must not throw.
     */
    void onContextInvalidated();

    /**
     * Execution feedback from the behavior tier, closing boundary B's
     * request/response loop. Processes consume reports to drive their
     * own state machines; they still read zero world state.
     *
     * <p>Contract: see ADR-0004 D3 and boundaries.md section B. Default
     * no-op so existing processes stay source-compatible; Stage 1
     * review owns ratifying this vocabulary growth (workplan
     * follow-up).
     *
     * @param report the behavior's verdict for the latest tick; never
     *               null
     */
    default void onExecutionReport(ExecutionReport report) {}

    /**
     * Stable identity for diagnostics, snapshots and interruption
     * records.
     *
     * @return short display name; never null or blank
     */
    String displayName();
}
