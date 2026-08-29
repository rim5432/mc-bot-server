package com.mcbot.mcbotserver.core.actor;

/**
 * Dig pacing: the pure half of the dig executor, mirroring vanilla's
 * destroy-progress arithmetic so block-break timing matches a player
 * holding left-click with the selected hotbar item.
 *
 * <p>Contract: see boundaries.md decision 25 (issue 0009). Vanilla
 * math, all constants from the decompiled 1.20.1 tree:
 * {@code Block.getDestroyProgress} computes
 * {@code digSpeed / destroySpeed / (correctTool ? 30 : 100)} per tick,
 * and {@code ServerPlayerGameMode#incrementDestroyProgress} scales it
 * by ticks held. The adapter supplies three block facts plus the
 * selected item's effective dig speed and correct-tool-for-drops
 * verdict; this class does the arithmetic.
 *
 * <p>Tool speed (issue 0007 Phase 1 tool supplier): {@code toolSpeed}
 * is the selected item's {@code ItemStack.getDestroySpeed(BlockState)}
 * — a pickaxe returns its tier speed (wood 2.0, stone 4.0, iron 6.0,
 * diamond 8.0, netherite 9.0) against blocks it can dig, and 1.0
 * otherwise; an empty hand or non-tool item also returns 1.0. The
 * {@code hasCorrectTool} flag is
 * {@code !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)}
 * — it controls both the divisor (30 vs 100) and whether the broken
 * block spawns drops (the adapter passes it to
 * {@code level.destroyBlock(pos, hasCorrectTool)}).
 *
 * <p>Why core/: the numbers are the contract (gravel costs 18 ticks
 * bare-hand, stone 150 bare-hand, stone with iron pickaxe 8 ticks)
 * and layer-1 tests pin them without an engine; the adapter only
 * supplies the block and item facts.
 */
public final class DigPacing {

    /**
     * Bare-hand dig speed. Empty main hand or a non-tool item: no tool
     * bonus, no efficiency, no haste (effects are a Stage 3 facade
     * question, issue 0007 Phase 4). The adapter passes this value when
     * the selected slot is empty or holds a non-digger item —
     * {@code ItemStack.getDestroySpeed} already returns 1.0 in those
     * cases, so this constant is the documented fallback.
     */
    public static final float BARE_HAND_DIG_SPEED = 1.0f;

    /**
     * Dig-speed multiplier while the body is airborne.
     * {@code Player.getDigSpeed} divides by 5 mid-air; a body with no
     * ground to brace on digs just as badly.
     */
    public static final float AIRBORNE_DIG_MULTIPLIER = 0.2f;

    /** Destroy-speed divisor when the block drops with the current tool. */
    public static final int HARVESTABLE_DIVISOR = 30;

    /**
     * Destroy-speed divisor when the block demands a tool the current
     * item is not: five times slower, still finite - vanilla lets a
     * hand break stone, it just charges 150 ticks for it.
     */
    public static final int TOOL_REQUIRED_DIVISOR = 100;

    private DigPacing() {}

    /**
     * Destroy progress contributed by one held tick.
     *
     * @param destroySpeed    the block's destroy speed (vanilla
     *                        {@code getDestroySpeed} - hardness);
     *                        negative means unbreakable
     * @param toolSpeed       the selected item's effective dig speed
     *                        against this block ({@code ItemStack.getDestroySpeed});
     *                        1.0 for bare hand or non-tool items
     * @param hasCorrectTool  true when the current tool satisfies the
     *                        block's tool requirement (or the block does
     *                        not require one); controls the divisor and
     *                        whether drops spawn
     * @param onGround        whether the digging body stands on ground
     * @return per-tick progress; 0 for unbreakable blocks, +Infinity
     *         for zero-hardness blocks (they pop on the first tick,
     *         exactly as vanilla's division produces)
     */
    public static float perTickProgress(float destroySpeed, float toolSpeed, boolean hasCorrectTool, boolean onGround) {
        if (destroySpeed < 0f) {
            return 0f;
        }
        float digSpeed = onGround ? toolSpeed : toolSpeed * AIRBORNE_DIG_MULTIPLIER;
        return digSpeed / destroySpeed / (hasCorrectTool ? HARVESTABLE_DIVISOR : TOOL_REQUIRED_DIVISOR);
    }

    /**
     * Cumulative progress after {@code heldTicks} ticks of holding.
     * Each tick adds one full {@code perTickProgress} step, so the
     * break completes when cumulative reaches {@code >= 1.0}. Vanilla
     * uses the same model: {@code perTick * (i + 1)} where {@code i}
     * is zero-based; our {@code heldTicks} is one-based so the
     * expression is equivalently {@code perTick * heldTicks}.
     *
     * @param perTickProgress value from {@link #perTickProgress}
     * @param heldTicks       ticks held including the current one,
     *                        1-based
     * @return cumulative progress; {@code >= 1.0} means the break
     *         completes this tick
     */
    public static float cumulativeProgress(float perTickProgress, int heldTicks) {
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

    /**
     * Apply the full vanilla dig-speed modifier stack to a base tool
     * speed. Mirrors {@code Player.getDigSpeed} (decompiled 1.20.1,
     * lines 752-781) minus the airborne penalty — that lives in
     * {@link #perTickProgress} so the pure half can test it without an
     * engine. Multiplication is associative across these factors, so
     * splitting airborne to the per-tick call is numerically exact.
     *
     * <p>Modifier order (matches vanilla):
     * <ol>
     *   <li>Efficiency enchant: {@code speed += level^2 + 1}, only when
     *       base speed {@code > 1.0} (the tool is effective against this
     *       block; bare-hand and wrong-tool get no efficiency bonus).</li>
     *   <li>Haste (DIG_SPEED): {@code speed *= 1.0 + (amplifier+1) * 0.2}.</li>
     *   <li>Mining Fatigue (DIG_SLOWDOWN): amplifier 0 -> 0.3, 1 -> 0.09,
     *       2 -> 0.0027, 3+ -> 0.00081.</li>
     *   <li>Underwater without Aqua Affinity: {@code speed /= 5.0}. Aqua
     *       Affinity on the helmet cancels this; Water Breathing does NOT
     *       (common misconception — vanilla checks the enchant, not the
     *       potion).</li>
     * </ol>
     *
     * <p>Amplifier parameters use {@code -1} for "effect not present" so
     * the pure method needs no MC classes; the adapter maps
     * {@code hasEffect} to the sentinel.
     *
     * @param baseSpeed              the held item's {@code getDestroySpeed}
     *                               against the target block; 1.0 for bare
     *                               hand or wrong tool
     * @param efficiencyLevel        Efficiency enchant level on the held
     *                               item; 0 when absent
     * @param hasteAmplifier         Haste (DIG_SPEED) amplifier, or -1
     *                               when not active
     * @param fatigueAmplifier       Mining Fatigue (DIG_SLOWDOWN)
     *                               amplifier, or -1 when not active
     * @param underwaterWithoutAqua  true when the eye is in water AND the
     *                               helmet has no Aqua Affinity
     * @return the effective dig speed after all modifiers; the airborne
     *         penalty (divide by 5) is NOT applied here —
     *         {@link #perTickProgress} applies it from the
     *         {@code onGround} flag
     */
    public static float applyDigSpeedModifiers(
            float baseSpeed,
            int efficiencyLevel,
            int hasteAmplifier,
            int fatigueAmplifier,
            boolean underwaterWithoutAqua) {
        float speed = baseSpeed;
        if (speed > 1.0f && efficiencyLevel > 0) {
            speed += efficiencyLevel * efficiencyLevel + 1;
        }
        if (hasteAmplifier >= 0) {
            speed *= 1.0f + (hasteAmplifier + 1) * 0.2f;
        }
        if (fatigueAmplifier >= 0) {
            speed *= switch (fatigueAmplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 0.00081f;
            };
        }
        if (underwaterWithoutAqua) {
            speed /= 5.0f;
        }
        return speed;
    }
}
