package com.mcbot.mcbotserver.boundaryd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.process.Attack;
import com.mcbot.mcbotserver.api.process.CombatOrder;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.Overrides;
import com.mcbot.mcbotserver.api.types.CellPos;
import org.junit.jupiter.api.Test;

/**
 * Combat vocabulary gate: the Overrides record carries its first real
 * component without disturbing the frozen Directive{Goal, Overrides}
 * shape; the order algebra grows by additive components (Attack,
 * Fish, Tame) that never change existing call sites.
 *
 * <p>Contract: see ADR-0002 section 1 and boundaries.md decision 11.
 */
class CombatVocabularyGateTest {

    @Test
    void emptyOverridesStayLocomotive() {
        assertNull(new Overrides().combat(), "no-combat must stay the default for existing tasks");
        assertNull(
                Directive.of(new GoalBlock(new CellPos(1, 64, 1))).overrides().combat(),
                "Directive.of keeps locomotive semantics");
    }

    @Test
    void attackOrderTravelsInsideDirective() {
        CombatOrder order = new Attack("entity-uuid-1");
        Directive directive = new Directive(new GoalBlock(new CellPos(2, 64, 2)), new Overrides(order));

        assertEquals("entity-uuid-1", directive.overrides().combat().targetId());
    }

    @Test
    void fishOrderTravelsInsideDirective() {
        com.mcbot.mcbotserver.api.process.Fish order =
                new com.mcbot.mcbotserver.api.process.Fish(new CellPos(3, 62, 3));
        Directive directive = new Directive(new GoalBlock(new CellPos(3, 64, 3)), new Overrides(null, order));

        assertEquals(
                new CellPos(3, 62, 3),
                directive.overrides().fish().waterCell(),
                "the fish order rides Overrides beside combat");
        assertNull(directive.overrides().combat(), "fishing is not fighting");
    }

    @Test
    void tameOrderTravelsInsideDirective() {
        com.mcbot.mcbotserver.api.process.Tame order =
                new com.mcbot.mcbotserver.api.process.Tame("wolf-uuid-9", "minecraft:wolf");
        Directive directive = new Directive(new GoalBlock(new CellPos(4, 64, 4)), new Overrides(null, null, order));

        assertEquals(
                "wolf-uuid-9",
                directive.overrides().tame().targetId(),
                "the tame order rides Overrides beside combat and fishing");
        assertEquals("minecraft:wolf", directive.overrides().tame().typeKey(), "the order carries the species");
        assertNull(directive.overrides().combat(), "taming is not fighting");
        assertNull(directive.overrides().fish(), "taming is not fishing");
    }

    @Test
    void blankTargetRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Attack(" "));
        assertThrows(NullPointerException.class, () -> new Attack(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new com.mcbot.mcbotserver.api.process.Tame(" ", "minecraft:wolf"));
        assertThrows(
                NullPointerException.class, () -> new com.mcbot.mcbotserver.api.process.Tame(null, "minecraft:wolf"));
        assertThrows(IllegalArgumentException.class, () -> new com.mcbot.mcbotserver.api.process.Tame("e-1", " "));
    }
}
