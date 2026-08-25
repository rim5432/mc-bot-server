package com.mcbot.mcbotserver.core.actor;

/**
 * Bare-hand dig pacing: the pure half of the dig executor, mirroring
 * vanilla's destroy-progress arithmetic so block-break timing matches
 * a player holding left-click with an empty hand.
 *
 * <p>Contract: see boundaries.md decision 25 (issue 0009). Vanilla
 * math, all constants from the decompiled 1.20.1 tree:
 * {@code Block.getDestroyProgress} computes
 * {@code digSpeed / destroySpeed / (correctTool ? 30 : 100)} per tick,
 * and {@code ServerPlayerGameMode#incrementDestroyProgress} scales it
 * by ticks held. The carrier has no Player and no tool, so digSpeed is
 * the bare-hand 1.0 modulo the situational modifiers that exist on
 * any entity (airborne penalty, {@code Player.getDigSpeed}); correct
 * tool reduces to "the block drops without one"
 * ({@code !requiresCorrectToolForDrops}), because a hand is never the
 * correct tool for anything that asks for one.
 *
 * <p>Why core/: the numbers are the contract (gravel costs 18 ticks,
 * stone 150, torch pops instantly) and layer-1 tests pin them without
 * an engine; the adapter only supplies the three block facts.
 */
public final class DigPacing {

    /**
     * Bare-hand dig speed. Empty main hand: no tool bonus, no
     * efficiency, no haste (effects are a Stage 3 facade question,
     * issue 0007 Phase 4).
     */
    public static final float BARE_HAND_DIG_SPEED = 1.0f;

    /**
     * Dig-speed multiplier while the body is airborne.
     * {@code Player.getDigSpeed} divides by 5 mid-air; a body with no
     * ground to brace on digs just as badly.
     */
    public static final float AIRBORNE_DIG_MULTIPLIER = 0.2f;

    /** Destroy-speed divisor when the block drops without a tool. */
    public static final int HARVESTABLE_DIVISOR = 30;

    /**
     * Destroy-speed divisor when the block demands a tool the bare
     * hand is not: five times slower, still finite - vanilla lets a
     * hand break stone, it just charges 150 ticks for it.
     */
    public static final int TOOL_REQUIRED_DIVISOR = 100;

    private DigPacing() {
    }

    /**
     * Destroy progress contributed by one held tick.
     *
     * @param destroySpeed         the block's destroy speed (vanilla
     *                             {@code getDestroySpeed} - hardness);
     *                             negative means unbreakable
     * @param requiresCorrectTool  true when the block only drops with
     *                             the right tool
     * @param onGround             whether the digging body stands on
     *                             ground
     * @return per-tick progress; 0 for unbreakable blocks, +Infinity
     *         for zero-hardness blocks (they pop on the first tick,
     *         exactly as vanilla's division produces)
     */
    public static float perTickProgress(float destroySpeed,
                                        boolean requiresCorrectTool,
                                        boolean onGround) {
        if (destroySpeed < 0f) {
            return 0f;
        }
        float digSpeed = onGround
            ? BARE_HAND_DIG_SPEED
            : BARE_HAND_DIG_SPEED * AIRBORNE_DIG_MULTIPLIER;
        return digSpeed / destroySpeed / (requiresCorrectTool
            ? TOOL_REQUIRED_DIVISOR
            : HARVESTABLE_DIVISOR);
    }

    /**
     * Cumulative progress after {@code heldTicks} ticks of holding.
     * Vanilla multiplies the per-tick figure by {@code heldTicks + 1}
     * because the start tick already contributes one full step.
     *
     * @param perTickProgress value from {@link #perTickProgress}
     * @param heldTicks       ticks held including the current one,
     *                        1-based
     * @return cumulative progress; {@code >= 1.0} means the break
     *         completes this tick
     */
    public static float cumulativeProgress(float perTickProgress,
                                           int heldTicks) {
        return perTickProgress * heldTicks;
    }

    /**
     * The crack-stage an observer sees (0..9), sent to clients as the
     * destroy-block progress. Vanilla broadcasts {@code (int)(f * 10)}
     * only when it changes from the last sent stage.
     *
     * @param cumulativeProgress value from
     *                           {@link #cumulativeProgress}
     * @return crack stage 0..9 (never 10 - a completed dig is a broken
     *         block, not a bigger crack)
     */
    public static int stageOf(float cumulativeProgress) {
        return Math.min((int) (cumulativeProgress * 10f), 9);
    }
}
