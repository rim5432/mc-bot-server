package com.mcbot.mcbotserver.core.pathing;

import com.mcbot.mcbotserver.api.pathing.Movement;
import com.mcbot.mcbotserver.api.types.CellPos;

import java.util.List;

/**
 * The search's edge supplier: "what movements exist from this cell".
 * Decouples the A* engine from concrete move vocabularies - swap
 * {@link BasicMoves} for a richer set without touching the finder.
 *
 * <p>Contract: see numen-notes.md section 15 (astar/ package knows the
 * algorithm; calc/ knows scheduling; moves/ knows vocabulary).
 */
@FunctionalInterface
public interface MoveGraph {

    /**
     * Candidate edges leaving one cell. Viability is checked by the
     * finder against perception, not here.
     *
     * @param cell the expansion cell; never null
     * @return candidate movements in any order; never null
     */
    List<Movement> movesFrom(CellPos cell);
}
