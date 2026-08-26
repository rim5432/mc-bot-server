package com.mcbot.mcbotserver.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Single source of truth for the bot's interaction reach policy.
 *
 * <p>Vanilla survival players interact at 4.5 blocks (eye to block
 * center); the bot holds the same number. In Minecraft 1.20.1 this
 * value is hard-coded in the client game-mode layer
 * ({@code MultiPlayerGameMode} uses 4.5 for survival, 5.0 for
 * creative) — there is no {@code BLOCK_REACH} attribute to read
 * (that attribute arrived in 1.20.5). Centralizing it here prevents
 * the per-executor duplication that previously lived in
 * {@code DigExecutor}, {@code InteractBlockExecutor}, and
 * {@code MenuOpener}.
 *
 * <p>Contract: see boundaries.md section A (Actor is intents-only;
 * reach gating is an honesty check that rejects out-of-range claims,
 * not a physics computation). Every adapter that gates an interaction
 * on distance must use this class so a reach-policy change touches
 * one file.
 */
// contract: see boundaries.md section A (reach gating is intent
//            honesty, behind the Actor claim surface)
public final class ReachPolicy {

    /**
     * Block interaction reach, eye to block center, in blocks.
     * Matches vanilla survival player reach. Tool-independent — reach
     * is a player property, not a tool property.
     */
    public static final double BLOCK_REACH_BLOCKS = 4.5;

    /** Squared reach, precomputed for per-tick distance comparisons. */
    private static final double BLOCK_REACH_SQ =
        BLOCK_REACH_BLOCKS * BLOCK_REACH_BLOCKS;

    private ReachPolicy() {
        // utility class — no instances
    }

    /**
     * Whether an eye position is within interaction reach of a block
     * center. The comparison is squared to avoid a sqrt per tick.
     *
     * @param eye    the observer eye position; never null
     * @param center the block center position; never null
     * @return true when the distance is at most
     *         {@link #BLOCK_REACH_BLOCKS}
     */
    public static boolean withinReach(Vec3 eye, Vec3 center) {
        return eye.distanceToSqr(center) <= BLOCK_REACH_SQ;
    }

    /**
     * Convenience overload: reach check from an eye position to a
     * block position's center. Eliminates the repeated
     * {@code Vec3.atCenterOf(pos)} call at every gate site.
     *
     * @param eye the observer eye position; never null
     * @param pos the block position; never null
     * @return true when the block center is within reach
     */
    public static boolean withinReach(Vec3 eye, BlockPos pos) {
        return withinReach(eye, Vec3.atCenterOf(pos));
    }
}
