package com.mcbot.mcbotserver.boundaryd;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.CombatOrder;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Combat vocabulary gate: the Overrides record carries its first real
 * component without disturbing the frozen Directive{Goal, Overrides}
 * shape, and the order algebra is sealed to Attack only.
 *
 * <p>Contract: see ADR-0002 section 1 and boundaries.md decision 11.
 */
class CombatVocabularyGateTest {

    @Test
    void emptyOverridesStayLocomotive() {
        assertNull(new Overrides().combat(),
            "no-combat must stay the default for existing tasks");
        assertNull(Directive.of(
            new GoalBlock(new CellPos(1, 64, 1))).overrides()
                .combat(),
            "Directive.of keeps locomotive semantics");
    }

    @Test
    void attackOrderTravelsInsideDirective() {
        CombatOrder order = new Attack("entity-uuid-1");
        Directive directive = new Directive(
            new GoalBlock(new CellPos(2, 64, 2)),
            new Overrides(order));

        assertEquals("entity-uuid-1",
            directive.overrides().combat().targetId());
    }

    @Test
    void blankTargetRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Attack(" "));
        assertThrows(NullPointerException.class,
            () -> new Attack(null));
    }
}
