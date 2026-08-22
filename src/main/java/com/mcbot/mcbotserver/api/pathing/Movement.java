package com.mcbot.mcbotserver.api.pathing;

import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;

/**
 * One edge of the move graph: the Movement five-tuple of boundaries.md
 * decision 8 minus its final slot - (source, destination, precondition,
 * cost) live here; the execution state machine is the follower's
 * concern and arrives with the waypoint runner.
 *
 * <p>Contract: see boundaries.md decision 7/8 and numen-notes.md
 * section 15 (pathing 3-way split: algorithm decoupled from scheduler
 * and executor). Implementations are pure data plus read-only viability
 * math over {@link WorldView} - never mutating, never acting.
 */
public interface Movement {

    /**
     * The cell this movement starts from.
     *
     * @return source cell; never null
     */
    CellPos source();

    /**
     * The cell this movement ends in.
     *
     * @return destination cell; never null
     */
    CellPos destination();

    /**
     * Precondition check against current perception. Called during
     * search on candidate edges only.
     *
     * @param world read-only perception; never null
     * @return true when the move is physically available right now
     */
    boolean isViable(WorldView world);

    /**
     * Search cost of taking this edge. Meaningful only when
     * {@link #isViable(WorldView)} returned true for the same world.
     *
     * @param world read-only perception; never null
     * @return non-negative tick-equivalent cost
     */
    double cost(WorldView world);

    /**
     * Human-readable identity for diagnostics and debug rendering.
     *
     * @return short description including src/dst; never null
     */
    String describe();
}
