package com.mcbot.mcbotserver.api.behavior;

import javax.annotation.Nullable;

/**
 * One behavior's verdict for this tick — the feedback half of boundary
 * B that keeps processes side-effect-free.
 *
 * <p>Contract: see ADR-0004 D3. SUCCESS is declared only after the
 * directive's {@code Goal} predicate passed; STUCK is declared by the
 * behavior that owns motion history; processes consume reports and
 * never read world state themselves.
 */
public record ExecutionReport(Status status, @Nullable String reason) {

    /** Terminal and ongoing states, closed per ADR-0004 D3. */
    public enum Status {

        /** Mission in progress; nothing special to report. */
        RUNNING,

        /** Goal predicate satisfied; mission may retire. */
        SUCCESS,

        /** Mission cannot proceed (NO_PATH, TIMEOUT, INVALIDATED...). */
        FAILED,

        /** Motion intended but no displacement over the fuse window. */
        STUCK
    }

    /**
     * Creates a validated report.
     *
     * @param status must not be null
     * @param reason required for FAILED and STUCK, must be null
     *               otherwise
     */
    public ExecutionReport {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        boolean needsReason = status == Status.FAILED || status == Status.STUCK;
        if (needsReason && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(status + " requires a reason");
        }
        if (!needsReason && reason != null) {
            throw new IllegalArgumentException(status + " must not carry a reason");
        }
    }

    /**
     * Ongoing report.
     *
     * @return a RUNNING report
     */
    public static ExecutionReport running() {
        return new ExecutionReport(Status.RUNNING, null);
    }

    /**
     * Arrival report.
     *
     * @return a SUCCESS report
     */
    public static ExecutionReport success() {
        return new ExecutionReport(Status.SUCCESS, null);
    }

    /**
     * Terminal failure.
     *
     * @param reason structural or world cause, e.g. NO_PATH; never null
     * @return a FAILED report
     */
    public static ExecutionReport failed(String reason) {
        return new ExecutionReport(Status.FAILED, reason);
    }

    /**
     * Motion-intended-but-not-moving verdict.
     *
     * @param reason diagnostic hint, e.g. "no displacement in window";
     *               never null
     * @return a STUCK report
     */
    public static ExecutionReport stuck(String reason) {
        return new ExecutionReport(Status.STUCK, reason);
    }
}
