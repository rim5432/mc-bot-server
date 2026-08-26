package com.mcbot.mcbotserver.api.process;

import com.mcbot.mcbotserver.api.types.CellPos;

/**
 * Mixin interface for processes that drive the INTERACT/DIG channel
 * through the controller's mission-dig claim path (issue 0014).
 *
 * <p>BotController injects aim+dig claims every tick for any seated
 * process implementing this interface and reporting {@link #isDigging()}
 * true. Previously this was hardcoded to {@code DigProcess}; the
 * interface lets {@code MineProcess} (and future ChopProcess) reuse the
 * same per-tick claim injection without touching the controller.
 *
 * <p>Contract: see boundaries.md section B (process tier is
 * side-effect-free) + issue 0014 §3.2 (DigMission extraction).
 */
public interface DigMission {

    /**
     * The block cell currently being mined. The controller uses this as
     * the aim target for the dig claim. Must not be null while
     * {@link #isDigging()} is true.
     *
     * @return the absolute block cell of the current dig target; never
     *         null when digging
     */
    CellPos digTarget();

    /**
     * Whether the process is in its digging phase this tick. When true,
     * the controller injects aim+dig claims; when false, the process is
     * moving, searching, or collecting and no dig claims are issued.
     *
     * @return true if the controller should inject dig claims this tick
     */
    boolean isDigging();

    /**
     * Arbiter seat priority for the dig claim. Same scale as
     * {@code GotoProcess} and {@code DigProcess}; higher priority wins
     * claim contests.
     *
     * @return the priority band value
     */
    int priority();

    /**
     * The boundary-D task id for this mission. Used as the claim owner
     * tag and the BLOCK_BROKEN event attr, so the harness can correlate
     * per-block progress to the submitting task.
     *
     * @return the task id; never null
     */
    String missionTaskId();
}
