package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.event.EventQueue;
import com.mcbot.mcbotserver.api.process.BotProcess;

import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.core.process.TerminalMission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mission-lifecycle reporting for the tick pipeline: stage-4 hand-off
 * detection plus the TASK_* events the preemption paths raise. Owns
 * the previous-current edge state so {@link BotController} stays
 * orchestration-only.
 *
 * <p>Structured fields land in attrs (task, reason); text stays
 * human-readable summary only - harnesses must never parse it. Every
 * push is guarded: reporting must never take the pipeline down with
 * it.
 *
 * <p>Implementation note: server tick thread only, like every
 * pipeline collaborator.
 */
// contract: see boundaries.md section D (structured attrs; text is
// summary only, never parsed by harnesses)
final class MissionReporter {

    private final TaskArbiter arbiter;
    private final EventQueue events;
    /** The process seated at the last {@link #announceTransition}
     *  call; null when none was. */
    private BotProcess previousCurrent;

    /**
     * @param arbiter seat authority whose {@code current()} drives
     *                hand-off detection; never null
     * @param events  boundary-D stream missions are reported to;
     *                never null
     */
    MissionReporter(TaskArbiter arbiter, EventQueue events) {
        this.arbiter = arbiter;
        this.events = events;
    }

    /**
     * Detect a mission hand-off and emit its completion event. The
     * pipeline stays generic: any process implementing TerminalMission
     * gets TASK_COMPLETED / TASK_FAILED; anything else ends silently.
     *
     * @param day game day for event stamping
     * @param tod time-of-day ticks for event stamping
     */
    void announceTransition(long day, long tod) {
        BotProcess previous = previousCurrent;
        BotProcess now = arbiter.current();
        previousCurrent = now;
        if (previous == null || previous == now) {
            return;
        }
        if (!(previous instanceof TerminalMission mission)) {
            return;
        }
        if (mission.missionSucceeded()) {
            emit(EventKind.TASK_COMPLETED, day, tod,
                mission.missionTaskId(), "goal reached",
                mission.verdictAttrs());
        } else if (previous.isActive()) {
            // Swapped out while still active (preemption path already
            // reported); nothing terminal to announce.
            return;
        } else {
            String reason = mission.failureReasonOrNull();
            String shown = reason != null ? reason : "unknown";
            var extra = new LinkedHashMap<String, String>(
                mission.verdictAttrs());
            extra.put("reason", shown);
            emit(EventKind.TASK_FAILED, day, tod,
                mission.missionTaskId(), "failed: " + shown, extra);
        }
    }

    /**
     * Park aftermath: no current is seated while paused, so the
     * hand-off detector must not fire on that stale state when the
     * seat refills.
     */
    void forgetCurrent() {
        previousCurrent = null;
    }

    /**
     * A reflex parked a mission mid-flight.
     *
     * @param taskName display name of the parked mission; never null
     * @param day      game day for event stamping
     * @param tod      time-of-day ticks for event stamping
     * @param detail   human-readable pause reason; never null
     */
    void paused(String taskName, long day, long tod, String detail) {
        emit(EventKind.TASK_PAUSED, day, tod, taskName, detail,
            Map.of());
    }

    /**
     * A parked mission was evicted or invalidated, not completed.
     *
     * @param taskName display name of the dropped mission; never null
     * @param day      game day for event stamping
     * @param tod      time-of-day ticks for event stamping
     * @param detail   human-readable drop reason; never null
     */
    void dropped(String taskName, long day, long tod, String detail) {
        emit(EventKind.TASK_DROPPED, day, tod, taskName, detail,
            Map.of());
    }

    /**
     * Revalidation verdict once a paused mission's seat frees up.
     *
     * @param resumed  true when world assumptions held and the mission
     *                 re-seated; false when they invalidated and the
     *                 mission was dropped
     * @param taskName display name of the revalidated mission;
     *                 never null
     * @param day      game day for event stamping
     * @param tod      time-of-day ticks for event stamping
     */
    void resumeVerdict(boolean resumed, String taskName,
                       long day, long tod) {
        emit(resumed ? EventKind.TASK_RESUMED : EventKind.TASK_DROPPED,
            day, tod, taskName,
            resumed ? "world assumptions held"
                : "world assumptions invalidated; task dropped",
            Map.of());
    }

    /**
     * Push one mission lifecycle event. PAUSED and DROPPED count as
     * urgent; everything else is informational. Never throws - a
     * poisoned outbox must not take the tick pipeline with it.
     */
    private void emit(String kind, long day, long tod, String taskName,
                      String detail, Map<String, String> extraAttrs) {
        boolean urgent = EventKind.TASK_PAUSED.equals(kind)
            || EventKind.TASK_DROPPED.equals(kind);
        try {
            var attrs = new LinkedHashMap<String, String>();
            attrs.put("task", taskName);
            attrs.putAll(extraAttrs);
            events.push(new BotEvent(kind, day, tod, urgent,
                Map.copyOf(attrs), taskName + ": " + detail));
        } catch (RuntimeException ignored) {
            // Reporting must never take the pipeline down with it.
        }
    }
}
