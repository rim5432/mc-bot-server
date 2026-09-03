package com.mcbot.mcbotserver.core.tick;

import com.mcbot.mcbotserver.api.actor.Actor;
import com.mcbot.mcbotserver.api.behavior.Behavior;
import com.mcbot.mcbotserver.api.process.BotProcess;
import com.mcbot.mcbotserver.api.process.DigMission;
import com.mcbot.mcbotserver.api.process.Directive;
import com.mcbot.mcbotserver.api.process.ExecutionReport;
import com.mcbot.mcbotserver.api.world.WorldView;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Per-tick dig-claim driver for seated dig-family missions: while the
 * arbiter's current mission implements {@link DigMission} and reports
 * {@code isDigging()}, re-issue the aim+dig claims (with the matching
 * tool selection) every tick. Extracted from BotController's pipeline
 * so the orchestrator stays mission-type-agnostic - the claims ride
 * the stage-3 behavior loop and resolve in the stage-4 flush exactly
 * as before (ChannelArbiter contests are priority+incumbent based, so
 * the within-tick submission position is immaterial).
 *
 * <p>Liveness contract (the protocol this class owns - previously
 * scattered across BotController comments and DigExecutor semantics):
 * DigExecutor resets break progress when the DIG claim goes silent
 * for a tick (vanilla parity - releasing the mouse resets destroy
 * progress), so a seated dig-family mission MUST be fed every tick.
 * Reflex preemption still wins because the reflex layer runs first
 * and parks missions - the parked mission leaves arbiter.current(),
 * which drops the injection with it. Any future early-return path
 * above stage 3 silently resets dig progress; do not add one.
 *
 * <p>Contract: see boundaries.md section B (process tier is
 * side-effect-free) + issue 0013 R1 / issue 0014 (DigMission
 * extraction).
 */
public final class DigClaimBehavior implements Behavior {

    private final Supplier<BotProcess> current;
    private final ToolSelector toolSelector;
    private final ReflexClaimInjector claimInjector;

    /**
     * Creates the claim driver over the arbiter's live mission seat.
     *
     * @param current       live view of the arbiter's seated mission;
     *                      never null
     * @param toolSelector  dig-speed tool auto-selection; never null
     * @param claimInjector claim construction shared with the reflex
     *                      paths; never null
     */
    public DigClaimBehavior(
            Supplier<BotProcess> current, ToolSelector toolSelector, ReflexClaimInjector claimInjector) {
        this.current = current;
        this.toolSelector = toolSelector;
        this.claimInjector = claimInjector;
    }

    @Override
    public ExecutionReport tick(WorldView world, @Nullable Directive directive, Actor actor) {
        if (current.get() instanceof DigMission dm && dm.isDigging()) {
            String holder = "mission:dig:" + dm.missionTaskId();
            toolSelector.select(world, actor, dm.priority(), holder, dm.digTarget());
            claimInjector.aimAndDig(dm.priority(), holder, dm.digTarget());
        }
        return ExecutionReport.running();
    }
}
