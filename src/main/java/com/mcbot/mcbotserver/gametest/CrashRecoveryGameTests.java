package com.mcbot.mcbotserver.gametest;

import static com.mcbot.mcbotserver.gametest.GametestRig.check;
import static com.mcbot.mcbotserver.gametest.GametestRig.checkEquals;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveOnly;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveTick;
import static com.mcbot.mcbotserver.gametest.GametestRig.driveUntil;
import static com.mcbot.mcbotserver.gametest.GametestRig.localToCell;
import static com.mcbot.mcbotserver.gametest.GametestRig.positionOf;
import static com.mcbot.mcbotserver.gametest.GametestRig.reached;
import static com.mcbot.mcbotserver.gametest.GametestRig.rig;
import static com.mcbot.mcbotserver.gametest.GametestRig.submitGoto;

import com.mcbot.mcbotserver.McBotServer;
import com.mcbot.mcbotserver.api.event.BotEvent;
import com.mcbot.mcbotserver.api.event.EventKind;
import com.mcbot.mcbotserver.api.goal.GoalBlock;
import com.mcbot.mcbotserver.api.interrupt.InterruptionContext;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.api.world.WorldView;
import com.mcbot.mcbotserver.core.process.GotoProcess;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ADR-0005 exception policy, in-engine: a RuntimeException raised
 * inside the four-stage pipeline latches the controller, the crashed
 * state runs MinimalReflex only (a submitted mission makes zero
 * progress), and a harness reset clears the latch so the pipeline
 * runs again.
 *
 * <p>The offline ExceptionPolicyGateTest pins the latch state machine
 * against mocks; this scenario pins the same contract through the
 * shared production assembly, including the parts only the engine
 * can show - the body standing still under MinimalReflex and the
 * recovery being observable as actual locomotion.
 *
 * <p>Contract: see ADR-0005 D1-D3 (single catch, latch -> snapshot ->
 * clear -> dual-channel report, MinimalReflex-only degraded mode) and
 * 5a (reset clears latch and counter).
 */
@GameTestHolder(McBotServer.MODID)
@PrefixGameTestTemplate(false)
public final class CrashRecoveryGameTests {

    private static final int TIMEOUT = 400;

    /** Latched ticks to observe before submitting the probe mission. */
    private static final int LATCHED_OBSERVE_TICKS = 30;

    /** Latched ticks the probe mission gets to prove zero progress. */
    private static final int LATCHED_PROBE_TICKS = 40;

    private CrashRecoveryGameTests() {}

    /**
     * Scenario: an in-pipeline exception latches the controller; while
     * latched a freshly submitted goto mission cannot move the body;
     * reset clears the latch and the same mission then completes.
     */
    @GameTest(template = "empty16x8x16", timeoutTicks = TIMEOUT)
    public static void latchesAndRecoversAfterCrash(GameTestHelper helper) {
        var rig = rig(helper, new BlockPos(3, GametestRig.WALK_Y, 8));
        CellPos goalCell = localToCell(helper, new BlockPos(12, GametestRig.WALK_Y, 8));
        var injector = new InjectorProcess(() -> positionOf(rig.body()));
        rig.arbiter().register(injector);
        rig.arbiter().requestControl(injector);
        CellPos[] posAfterCrash = {null};
        GotoProcess[] probe = {null};

        helper.startSequence()
                // One tick seats the injector; its onTick throws and the
                // controller's single catch frame latches (ADR-0005 D1).
                .thenExecute(() -> driveTick(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.controller().isCrashed(), "the injected exception must latch the controller");
                    checkEquals(1, rig.controller().crashCounter(), "exactly one crash is counted");
                    check(crashEvents(rig) == 1, "BOT_CRASHED must reach the stream exactly once");
                    check(rig.body().isAlive(), "a latched bot is degraded, not dead");
                    posAfterCrash[0] = positionOf(rig.body());
                })
                .thenExecuteFor(LATCHED_OBSERVE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(rig.controller().isCrashed(), "the latch is sticky without a reset");
                    checkEquals(
                            1,
                            rig.controller().crashCounter(),
                            "MinimalReflex must not rethrow (a second count "
                                    + "would mean the degraded path itself crashed)");
                    checkEquals(crashEvents(rig), 1, "no second BOT_CRASHED while degraded");
                    checkEquals(
                            posAfterCrash[0], positionOf(rig.body()), "MinimalReflex holds the body; it must not move");
                })
                // Probe mission submitted WHILE latched: the pipeline is
                // skipped, so the mission cannot start and the body cannot
                // move - the in-engine shape of "MinimalReflex only".
                .thenExecute(() -> probe[0] = submitGoto(rig, goalCell))
                .thenExecuteFor(LATCHED_PROBE_TICKS, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    checkEquals(
                            posAfterCrash[0],
                            positionOf(rig.body()),
                            "a latched controller must give a submitted " + "mission zero progress");
                    // Harness reset (ADR-0005 5a): latch and counter clear.
                    rig.controller().reset();
                    check(!rig.controller().isCrashed(), "reset must clear the latch");
                    checkEquals(0, rig.controller().crashCounter(), "reset must clear the crash counter");
                })
                .thenWaitUntil(driveUntil(
                        rig, () -> check(reached(rig.body(), goalCell), "waiting for post-reset arrival")))
                .thenExecuteFor(3, driveOnly(rig))
                .thenExecuteAfter(0, () -> {
                    check(!probe[0].isActive(), "the probe mission must retire after recovery");
                    check(probe[0].missionSucceeded(), "the recovered pipeline must complete the probe");
                    check(missionCompletedSeen(rig), "TASK_COMPLETED must reach the stream after reset");
                    rig.body().discard();
                })
                .thenSucceed();
    }

    private static int crashEvents(GametestRig.Rig rig) {
        return (int) rig.events().statusSnapshot(0).events().stream()
                .filter(e -> EventKind.BOT_CRASHED.equals(e.kind()))
                .count();
    }

    private static boolean missionCompletedSeen(GametestRig.Rig rig) {
        List<String> kinds = rig.events().statusSnapshot(0).events().stream()
                .map(BotEvent::kind)
                .toList();
        return kinds.contains(EventKind.TASK_COMPLETED);
    }

    /**
     * A mission whose first onTick raises a RuntimeException - the
     * injection point for ADR-0005 D1. After throwing once it retires
     * quietly and answers with a benign hold directive, so the
     * post-reset arbiter never re-seats a throwing process and the
     * recovery path stays the one under test.
     */
    private static final class InjectorProcess implements BotProcess {
        private final Supplier<CellPos> position;
        private boolean thrown;
        private boolean active = true;

        InjectorProcess(Supplier<CellPos> position) {
            this.position = position;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public int priority() {
            return 90;
        }

        @Override
        public Directive onTick(WorldView world) {
            if (!thrown) {
                thrown = true;
                throw new IllegalStateException("injected pipeline failure for ADR-0005 D1");
            }
            active = false;
            // Benign terminal directive: hold position (GoalBlock at
            // the body's own cell), never null - a null directive
            // from a process is an implementation bug per BotProcess.
            return new Directive(new GoalBlock(position.get()), new com.mcbot.mcbotserver.api.process.Overrides());
        }

        @Override
        public void onLostControl(InterruptionContext context) {}

        @Override
        public boolean resume(InterruptionContext context) {
            return true;
        }

        @Override
        public void onContextInvalidated() {}

        @Override
        public String displayName() {
            return "injector";
        }
    }
}
