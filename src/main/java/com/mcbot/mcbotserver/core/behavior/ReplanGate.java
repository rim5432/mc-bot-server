package com.mcbot.mcbotserver.core.behavior;

/**
 * The replan trigger gate: cooldown rationing plus the vertical gate
 * (boundaries.md decision ledger 20; landed as issue 0001 fix 6).
 * Trigger evaluation runs only while the body is
 * in ground contact; the landing edge ({@code onGround} transitions
 * false -&gt; true) bypasses the cooldown so the post-landing position
 * is the basis for the next plan, not a stale pre-take-off plan.
 * Airborne ticks still steer toward the current waypoint, but they do
 * not request replans - those would be measured against a snapshot
 * the freshness check will discard on landing.
 *
 * <p>The cooldown counter counts UP from {@link PathingBehavior#REPLAN_COOLDOWN}
 * at request time, so a fresh request re-arms the full window.
 *
 * <p>Implementation note: single-threaded, tick-thread-local like all
 * follower state.
 */
final class ReplanGate {

    // Previous tick's onGround state, used to detect the landing
    // edge (false -> true). Initialised to false so the first
    // grounded tick is correctly classified as a "landing" - the
    // edge is harmless then because neverPlanned short-circuits
    // the replan gate anyway.
    private boolean wasOnGround;
    private boolean neverPlanned = true;
    private int ticksSincePlan = PathingBehavior.REPLAN_COOLDOWN;

    /**
     * What this tick's trigger evaluation is allowed to do.
     *
     * @param evaluateTriggers whether progress/offPath/exhausted
     *                         scoring may run at all (ground contact)
     * @param landingEdge      whether this tick IS the landing edge,
     *                         which bypasses the cooldown
     */
    record TickWindow(boolean evaluateTriggers, boolean landingEdge) {
    }

    /**
     * Open the tick's window: advance the cooldown counter and sample
     * ground contact for the landing edge.
     *
     * @param onGround whether the body is in ground contact this tick
     * @return the window governing this tick's trigger evaluation
     */
    TickWindow beginTick(boolean onGround) {
        ticksSincePlan++;
        boolean landing = onGround && !wasOnGround;
        wasOnGround = onGround;
        return new TickWindow(onGround || landing, landing);
    }

    /**
     * Whether a replan request may fire this window: first-ever plan,
     * landing edge, or the cooldown elapsed.
     *
     * @param window this tick's window; never null
     * @return true iff a request would be honoured
     */
    boolean mayRequest(TickWindow window) {
        return window.landingEdge()
            || neverPlanned
            || ticksSincePlan >= PathingBehavior.REPLAN_COOLDOWN;
    }

    /** Record that a request fired; re-arm the full cooldown. */
    void onRequest() {
        neverPlanned = false;
        ticksSincePlan = 0;
    }

    /**
     * Prime the ladder so THIS tick's trigger evaluation immediately
     * requests a plan - used when a finished search is discarded as
     * stale: the cooldown guards executing routes, not unanswered
     * goals.
     */
    void primeLadder() {
        ticksSincePlan = PathingBehavior.REPLAN_COOLDOWN;
    }

    /**
     * Full reset (mission success): forget the first-plan bypass and
     * re-arm the cooldown.
     */
    void reset() {
        neverPlanned = true;
        ticksSincePlan = PathingBehavior.REPLAN_COOLDOWN;
    }

    /**
     * Ticks since the last replan request, for the keepalive snapshot.
     *
     * @return the raw counter value
     */
    int ticksSinceRequest() {
        return ticksSincePlan;
    }
}
