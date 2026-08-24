package com.mcbot.mcbotserver.core.process;

import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.core.process.TaskArbiter;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.world.MockWorldView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-0 arbiter gate: banded registration, winner-take-all with
 * fresh-intent-first ties, reflex parking, and the resume-validation
 * drop path all hold on pure Java.
 *
 * <p>Contract: see ADR-0002 section 2 and ADR-0003 section 3.
 */
class ArbiterGateTest {

    private static final WorldView WORLD = new MockWorldView();
    private static final InterruptionContext CTX = new InterruptionContext(
        7L, new CellPos(0, 64, 0), "stub", "reflex-preempt:TEST", "");

    /** Scriptable process stub recording lifecycle calls. */
    private static final class StubProcess implements BotProcess {
        private final String name;
        private final int priority;
        private boolean active = true;
        private boolean resumeAnswer = true;
        private int lostCalls;
        private int invalidatedCalls;
        private int resumeCalls;
        private int tickCalls;

        private StubProcess(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Directive onTick(WorldView world) {
            tickCalls++;
            return Directive.of(new GoalBlock(new CellPos(0, 64, 0)));
        }

        @Override
        public void onLostControl(InterruptionContext context) {
            lostCalls++;
        }

        @Override
        public boolean resume(InterruptionContext context) {
            resumeCalls++;
            return resumeAnswer;
        }

        @Override
        public void onContextInvalidated() {
            invalidatedCalls++;
        }

        @Override
        public String displayName() {
            return name;
        }
    }

    /** Reserved and negative bands must never register. */
    @Test
    void bandValidationRejectsIllegalPriorities() {
        TaskArbiter arbiter = new TaskArbiter();
        assertThrows(IllegalArgumentException.class,
            () -> arbiter.register(new StubProcess("r200", 200)));
        assertThrows(IllegalArgumentException.class,
            () -> arbiter.register(new StubProcess("rneg", -1)));
        assertThrows(IllegalArgumentException.class,
            () -> arbiter.register(new StubProcess("r999", 999)));
        for (int legal : new int[] {0, 99, 100, 150, 199}) {
            arbiter.register(new StubProcess("ok" + legal, legal));
        }
    }

    /** Highest priority wins; equal priorities defer to the fresher. */
    @Test
    void winnerTakesAllAndFreshWinsTies() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess older = new StubProcess("older", 10);
        StubProcess fresher = new StubProcess("fresher", 10);
        StubProcess low = new StubProcess("low", 0);
        arbiter.register(older);
        arbiter.register(fresher);
        arbiter.register(low);

        arbiter.requestControl(older);
        arbiter.requestControl(fresher);
        arbiter.tick(WORLD);
        assertSame(fresher, arbiter.current(),
            "equal priorities defer to the fresher claim");
        assertEquals(1, fresher.tickCalls);
        assertEquals(0, older.tickCalls,
            "loser defers; winner-take-all means nobody else ticks");

        fresher.active = false;
        arbiter.tick(WORLD);
        assertSame(older, arbiter.current(),
            "deferred candidates take over by priority, not queue order");
    }

    /** Reflex preemption parks the mission; mission stage is skipped. */
    @Test
    void forcePauseParksWinnerAndSkipsMission() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess mission = new StubProcess("mission", 50);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        arbiter.tick(WORLD);
        assertEquals(1, mission.tickCalls);

        assertEquals(TaskArbiter.ParkResult.PARKED, arbiter.forcePauseAll(CTX));
        assertEquals(1, mission.lostCalls);
        assertNull(arbiter.lastDirective(),
            "a parked body produces no directive");

        arbiter.tick(WORLD);
        assertEquals(1, mission.tickCalls,
            "paused missions are not ticked by the arbiter");
        assertSame(mission, arbiter.paused());
    }

    /**
     * ADR-0003 section 3 gate: a failed revalidation drops the mission
     * instead of resuming it.
     */
    @Test
    void invalidatedContextDropsTaskInsteadOfResuming() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess dropped = new StubProcess("dropped", 60);
        StubProcess fallback = new StubProcess("fallback", 10);
        arbiter.register(dropped);
        arbiter.register(fallback);
        arbiter.requestControl(dropped);
        arbiter.tick(WORLD);
        arbiter.forcePauseAll(CTX);

        dropped.resumeAnswer = false;
        assertFalse(arbiter.tryResume());
        assertEquals(1, dropped.resumeCalls);
        assertEquals(1, dropped.invalidatedCalls,
            "invalidated missions get their drop notification");

        arbiter.tryResume();
        assertNull(arbiter.current(),
            "an invalidated mission must not sneak back in");

        arbiter.requestControl(fallback);
        arbiter.tick(WORLD);
        assertSame(fallback, arbiter.current(),
            "the queue proceeds normally after a drop");
    }

    /** Clean revalidation hands control straight back. */
    @Test
    void validContextResumesMission() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess mission = new StubProcess("mission", 40);
        arbiter.register(mission);
        arbiter.requestControl(mission);
        arbiter.tick(WORLD);
        arbiter.forcePauseAll(CTX);

        assertTrue(arbiter.tryResume());
        assertSame(mission, arbiter.current());
        arbiter.tick(WORLD);
        assertEquals(2, mission.tickCalls);
        assertEquals(1, mission.resumeCalls);
    }

    /**
     * Single-slot invariant (reflex chain): parking a second mission
     * while one is parked evicts the old occupant through
     * revalidate-and-requeue - it re-enters pending, revalidates via
     * resume(), and is never orphaned.
     */
    @Test
    void parkingOverAParkedMissionRequeuesTheOldOccupant() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess original = new StubProcess("original", 50);
        StubProcess fight = new StubProcess("fight", 150);
        arbiter.register(original);
        arbiter.tick(WORLD);
        arbiter.forcePauseAll(CTX);            // original parked
        arbiter.register(fight);
        arbiter.tick(WORLD);                   // fight seated
        assertSame(fight, arbiter.current());

        assertEquals(TaskArbiter.ParkResult.PARKED,
            arbiter.forcePauseAll(CTX));       // parks fight, evicts original
        assertSame(fight, arbiter.paused());
        assertEquals(1, original.resumeCalls,
            "the evicted occupant revalidates through resume()");
        assertEquals(0, original.invalidatedCalls);

        arbiter.tick(WORLD);                   // fight paused; original wins pending
        assertSame(original, arbiter.current(),
            "the requeued original must not be orphaned");
        assertEquals(2, original.tickCalls);
    }

    /** Eviction of a refusing or dead occupant drops it honestly. */
    @Test
    void parkingOverAParkedMissionDropsARefusingOccupant() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess original = new StubProcess("original", 50);
        StubProcess fight = new StubProcess("fight", 150);
        arbiter.register(original);
        arbiter.tick(WORLD);
        arbiter.forcePauseAll(CTX);
        arbiter.register(fight);
        arbiter.tick(WORLD);

        original.resumeAnswer = false;
        arbiter.forcePauseAll(CTX);
        assertEquals(1, original.invalidatedCalls,
            "a refusing occupant is dropped via onContextInvalidated");
        assertSame(fight, arbiter.paused());

        arbiter.tick(WORLD);
        assertNull(arbiter.current(),
            "the dropped occupant must not re-enter selection");
    }

    /** The requeue call is a no-op without a parked mission. */
    @Test
    void requeueWithoutPausedIsNone() {
        TaskArbiter arbiter = new TaskArbiter();
        assertEquals(TaskArbiter.PausedEviction.NONE,
            arbiter.requeuePausedOrDrop());
    }

    /** The explicit requeue path returns its outcome for callers. */
    @Test
    void explicitRequeueReturnsRequeuedForValidMission() {
        TaskArbiter arbiter = new TaskArbiter();
        StubProcess original = new StubProcess("original", 50);
        arbiter.register(original);
        arbiter.tick(WORLD);
        arbiter.forcePauseAll(CTX);
        assertEquals(TaskArbiter.PausedEviction.REQUEUED,
            arbiter.requeuePausedOrDrop());
        assertNull(arbiter.paused());
        arbiter.tick(WORLD);
        assertSame(original, arbiter.current());
    }
}
