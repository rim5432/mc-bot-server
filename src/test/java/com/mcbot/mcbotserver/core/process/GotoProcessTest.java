package com.mcbot.mcbotserver.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.process.InterruptionContext;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.world.MockWorldView;
import org.junit.jupiter.api.Test;

/**
 * Stage-1 GotoProcess state machine: SUCCESS / STUCK / TIMEOUT
 * terminals, silent abort for cancellation, resume mirroring liveness.
 *
 * <p>Contract: see boundaries.md decision 18 and ADR-0004 D3/D4.
 */
class GotoProcessTest {

    private static final MockWorldView WORLD = new MockWorldView();
    private static final InterruptionContext CTX =
            new InterruptionContext(1L, new CellPos(0, 64, 0), "goto:t1", "reflex-preempt:X", "");

    private GotoProcess mission(long timeout) {
        return new GotoProcess("t1", new GoalBlock(new CellPos(10, 64, 10)), 50, timeout);
    }

    /** SUCCESS report retires the mission as a clean success. */
    @Test
    void successReportRetiresCleanly() {
        GotoProcess m = mission(100);
        assertTrue(m.isActive());
        m.onExecutionReport(ExecutionReport.success());
        assertFalse(m.isActive());
        assertTrue(m.missionSucceeded());
        assertNull(m.failureReasonOrNull());
        assertFalse(m.resume(CTX), "a finished mission must not resume");
    }

    /** STUCK report fails the mission with reason STUCK. */
    @Test
    void stuckReportFailsMission() {
        GotoProcess m = mission(100);
        m.onExecutionReport(ExecutionReport.stuck("no displacement in window"));
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertEquals("STUCK", m.failureReasonOrNull());
    }

    /** The tick budget exhausts into a TIMEOUT failure. */
    @Test
    void timeoutExhaustsIntoFailure() {
        GotoProcess m = mission(5);
        for (int i = 0; i < 4; i++) {
            m.onTick(WORLD);
            assertTrue(m.isActive(), "tick " + i + " must still run");
        }
        m.onTick(WORLD);
        assertFalse(m.isActive());
        assertEquals("TIMEOUT", m.failureReasonOrNull());
    }

    /** First failure wins; later reports cannot flip terminal state. */
    @Test
    void terminalStateIsSticky() {
        GotoProcess m = mission(100);
        m.onExecutionReport(ExecutionReport.stuck("x"));
        m.onExecutionReport(ExecutionReport.success());
        assertFalse(m.missionSucceeded(), "a failed mission must not become successful");
        assertEquals("STUCK", m.failureReasonOrNull());
    }

    /** abort() deactivates without recording any failure. */
    @Test
    void abortIsSilentTermination() {
        GotoProcess m = mission(100);
        m.abort();
        assertFalse(m.isActive());
        assertFalse(m.missionSucceeded());
        assertNull(m.failureReasonOrNull());
    }

    /** Resume mirrors liveness: dropped missions refuse to come back. */
    @Test
    void resumeMirrorsLiveness() {
        GotoProcess m = mission(100);
        assertTrue(m.resume(CTX));
        m.onExecutionReport(ExecutionReport.stuck("x"));
        assertFalse(m.resume(CTX), "a failed mission must not resume");
    }
}
