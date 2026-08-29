package com.mcbot.mcbotserver.core.behavior;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Sneak-flag gate for one steering leg, called directly: this test
 * lives in the same package as the package-private helper it pins
 * (code-health.md H-R1 - collaborator-level tests are same-package,
 * never reflective).
 *
 * <p>Why the medium check is load-bearing: a flag keyed only on
 * "waypoint below floor" held shift on every land descent, and the
 * body's sneak edge guard ({@code maybeBackOffFromEdge}) then pinned
 * the body at the ledge - the corridor-STUCK engine batch regression.
 * Only a SwimDown edge earns the flag; {@code BasicMoves.SwimDown}
 * viability demands a liquid source, so the gate mirrors the edge
 * exactly.
 */
class SteerSneakGateTest {

    private static final CellPos FLOOR = new CellPos(5, 64, 5);

    @Test
    void waterDescentLegEarnsSneak() {
        CellPos below = new CellPos(5, 63, 5);
        assertTrue(PathingBehavior.sneakForLeg(below, FLOOR, true), "SwimDown leg dives");
    }

    @Test
    void landDescentLegNeverSneaks() {
        CellPos below = new CellPos(6, 63, 5);
        assertFalse(
                PathingBehavior.sneakForLeg(below, FLOOR, false),
                "land drop plus sneak = edge guard pins the body at the ledge");
    }

    @Test
    void levelAndRisingLegsNeverSneak() {
        CellPos level = new CellPos(6, 64, 5);
        CellPos above = new CellPos(5, 65, 5);
        assertFalse(PathingBehavior.sneakForLeg(level, FLOOR, true), "surface crossing must not dive");
        assertFalse(PathingBehavior.sneakForLeg(above, FLOOR, true), "ascent is the jump branch, not sneak");
        assertFalse(PathingBehavior.sneakForLeg(level, FLOOR, false));
        assertFalse(PathingBehavior.sneakForLeg(above, FLOOR, false));
    }
}
