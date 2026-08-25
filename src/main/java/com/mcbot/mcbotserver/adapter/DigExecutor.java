package com.mcbot.mcbotserver.adapter;

import com.mcbot.mcbotserver.adapter.entity.BotBodyEntity;
import com.mcbot.mcbotserver.api.types.CellPos;
import com.mcbot.mcbotserver.core.actor.DigPacing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The dig half of the entity binding: converts a held INTERACT claim
 * (Intent.Dig) into vanilla destroy progress and, on completion, the
 * block break. The engine applies every world mutation; this class
 * only paces it - boundary A holds because nothing here runs without a
 * winning claim from the actor's flush.
 *
 * <p>Contract: see boundaries.md decision 25 (issue 0009). The state
 * machine mirrors ServerPlayerGameMode's destroy machinery from the
 * decompiled 1.20.1 tree: per-tick progress accumulates on an
 * unchanged target ({@code perTick * heldTicks}), crack stages go to
 * observers via {@code destroyBlockProgress}, and the break itself is
 * {@code level.destroyBlock(pos, true)} (drops + the 2001 level
 * event, both handled inside that call). Two deliberate deviations,
 * both because a server-side bot has no client to compensate for:
 * vanilla's stop-destroy 0.7 completion shortcut (lag compensation for
 * a client that released the button a few packets early) is not
 * mirrored - the bot's claims are the ground truth, so the break lands
 * at exactly 1.0; and the Forge LeftClickBlock / onBlockBreakEvent
 * hooks are Player-typed and skipped - no carrier Player exists until
 * the 0007 facade ruling, so protection-mod interop is deferred with
 * it.
 *
 * <p>Reach gate: a claim outside bare-hand reach is ignored (progress
 * never starts), matching vanilla's mayInteract honesty without
 * needing its Player parameter.
 *
 * <p>Implementation note: runs on the server tick thread only, called
 * from BindingActor.flush.
 */
// contract: see boundaries.md decision 25 (dig is INTERACT-channel
//            vocabulary; all mutation behind the claim surface)
public final class DigExecutor {

    /**
     * Bare-hand block reach, eye to block center, in blocks. Vanilla
     * players reach 4.5 (block-reach attribute); the bot holds the
     * same number as a constant until inventory/tooling lands
     * (0007 Phase 1).
     */
    public static final double DIG_REACH_BLOCKS = 4.5;

    private final BotBodyEntity body;

    /** Current dig site; null while no dig is in progress. */
    private BlockPos target;

    /** Ticks the current target has been held, 1-based. */
    private int heldTicks;

    /** Last crack stage broadcast, -1 when none. */
    private int lastStage = -1;

    /**
     * Creates an executor bound to one body.
     *
     * @param body the physical carrier whose level is dug; never null
     */
    public DigExecutor(BotBodyEntity body) {
        this.body = body;
    }

    /**
     * One tick of a held dig claim.
     *
     * @param claimed the claimed cell; never null
     */
    public void dig(CellPos claimed) {
        BlockPos pos = new BlockPos(claimed.x(), claimed.y(),
            claimed.z());
        if (!pos.equals(target)) {
            // New site (or first tick): reset any previous progress.
            // Vanilla's ABORT path broadcasts the clear; so does this.
            resetBroadcast();
            target = pos;
            heldTicks = 0;
            lastStage = -1;
        }
        BlockState state = body.level().getBlockState(pos);
        if (state.isAir()) {
            // Somebody else finished it (a player, a piston): stop
            // silently, stay locked on the site in case more falls in.
            resetBroadcast();
            heldTicks = 0;
            lastStage = -1;
            return;
        }
        if (!withinReach(pos)) {
            // Out-of-reach claim: no progress, no broadcast - the
            // claim keeps arriving, the executor stays honest.
            return;
        }
        heldTicks++;
        float progress = DigPacing.cumulativeProgress(
            DigPacing.perTickProgress(
                state.getDestroySpeed(body.level(), pos),
                state.requiresCorrectToolForDrops(),
                body.onGround()),
            heldTicks);
        if (progress >= 1.0f) {
            // Clear the crack broadcast first: destroyBlock emits the
            // 2001 break effect itself, and a stale stage would linger
            // on the next dig of the same cell.
            body.level().destroyBlockProgress(body.getId(), pos, -1);
            body.level().destroyBlock(pos, true);
            target = null;
            heldTicks = 0;
            lastStage = -1;
            return;
        }
        int stage = DigPacing.stageOf(progress);
        if (stage != lastStage) {
            body.level().destroyBlockProgress(body.getId(), pos, stage);
            lastStage = stage;
        }
        // Client players swing every tick their held dig progresses;
        // the server-side body mirrors that visible cadence directly.
        body.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * The claim stopped arriving (channel unclaimed or a different
     * intent won): cancel the in-progress dig and clear the crack
     * broadcast, exactly like vanilla's stop/abort paths.
     */
    public void release() {
        resetBroadcast();
        target = null;
        heldTicks = 0;
        lastStage = -1;
    }

    /**
     * Whether the eye is within bare-hand reach of the block center.
     *
     * @param pos the claimed block; never null
     * @return true when the dig may progress
     */
    private boolean withinReach(BlockPos pos) {
        var eye = body.getEyePosition();
        var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        return eye.distanceToSqr(center)
            <= DIG_REACH_BLOCKS * DIG_REACH_BLOCKS;
    }

    /** Clear the crack broadcast for the current site, if any. */
    private void resetBroadcast() {
        if (target != null && lastStage >= 0) {
            body.level().destroyBlockProgress(body.getId(), target, -1);
        }
    }
}
