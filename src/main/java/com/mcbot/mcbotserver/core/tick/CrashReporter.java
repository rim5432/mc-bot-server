package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;

/**
 * Crash reporting surface for ADR-0005 D4's dual-channel rule.
 *
 * <p>Contract: see ADR-0005 D4 — both channels fire on every crash;
 * either succeeding counts as reported; neither may throw upward. The
 * production implementation pairs the event stream (primary) with
 * System.err plus a disk file (fallback); tests capture calls instead.
 */
@FunctionalInterface
public interface CrashReporter {

    /**
     * Report one crash snapshot. Implementations must be best-effort:
     * swallow their own failures, never rethrow.
     *
     * @param context the crash snapshot; never null
     */
    void report(InterruptionContext context);

    /**
     * Production fallback half of ADR-0005 D4: stderr plus a run-dir
     * file once the adapter knows where that is.
     *
     * @return a best-effort reporter writing to standard error
     */
    static CrashReporter consoleFallback() {
        return context -> {
            System.err.println("[mcbot][CRASH] tick="
                + context.tick() + " cause=" + context.causeSummary());
            System.err.println(context.stackTrace());
            // TODO adapter: append crash lines to the run-dir file as
            //  well (ADR-0005 D4 fallback half); needs the runtime dir,
            //  which only the adapter knows (Ref: workplan Stage 1).
        };
    }
}
