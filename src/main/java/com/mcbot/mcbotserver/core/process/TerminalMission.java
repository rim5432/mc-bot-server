package com.mcbot.mcbotserver.core.process;

/**
 * Missions that know how they ended. Implemented by processes with
 * terminal states so the tick pipeline can emit boundary-D completion
 * events generically — the pipeline never learns what a "goto" is.
 *
 * <p>Contract: see boundaries.md section B (feedback closes through
 * ExecutionReport) and decision 18 (GotoCommand semantics). This is
 * Stage 1 vocabulary growth owned by the workplan follow-up entry,
 * alongside {@code BotProcess.onExecutionReport}.
 */
public interface TerminalMission {

    /**
     * Whether the mission reached its goal before any failure.
     *
     * @return true only for a clean success
     */
    boolean missionSucceeded();

    /**
     * Machine-readable failure cause.
     *
     * @return failure reason name, or null when succeeded or not yet
     *         terminal
     */
    String failureReasonOrNull();

    /**
     * Boundary-D task identity for structured event attrs - the same
     * id the harness received from submit, not the display name.
     *
     * @return task id; never null or blank
     */
    String missionTaskId();

    /**
     * Structured verdict attributes beyond task and reason - e.g. a
     * refused engagement's threat type. Merged into event attrs by
     * the pipeline; empty by default.
     *
     * @return extra attrs for the terminal event; never null
     */
    default java.util.Map<String, String> verdictAttrs() {
        return java.util.Map.of();
    }
}
