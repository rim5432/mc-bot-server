package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.world.Difficulty;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameRules;

/**
 * One tick of the hunger lifecycle - a verbatim clone of vanilla
 * {@code FoodData.tick(Player)} (decompiled 1.20.1) with the body in
 * the player's seat: the Player parameter's only four uses (level
 * difficulty and natural-regen rule, {@code isHurt}, {@code heal},
 * starve {@code hurt}) are all LivingEntity-available, so the carrier
 * ticks the same math vanilla players get. Extracted from
 * BotBodyEntity so the body class keeps its WMC budget (the clone's
 * branch count is one method's worth, not the body's).
 */
final class HungerTicker {

    /** FoodData's private tickTimer, carried here (no accessor exists). */
    private int tickTimer;

    /**
     * Advances the food state one tick: exhaustion drain, saturated
     * fast regen, foodLevel>=18 slow regen, starvation.
     *
     * @param body the hungry body; never null
     * @param food its food state; never null
     */
    void tick(BotBodyEntity body, FoodData food) {
        drainExhaustion(body, food);
        boolean naturalRegen = body.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        boolean hurt = body.getHealth() > 0.0F && body.getHealth() < body.getMaxHealth();
        if (!trySaturatedRegen(body, food, naturalRegen, hurt)
                && !trySlowRegen(body, food, naturalRegen, hurt)
                && !tryStarve(body, food)) {
            tickTimer = 0;
        }
    }

    /** The every-4.0-exhaustion drain: saturation first, then food. */
    private void drainExhaustion(BotBodyEntity body, FoodData food) {
        if (food.getExhaustionLevel() > 4.0F) {
            food.addExhaustion(-4.0F);
            if (food.getSaturationLevel() > 0.0F) {
                food.setSaturation(Math.max(food.getSaturationLevel() - 1.0F, 0.0F));
            } else if (body.level().getDifficulty() != Difficulty.PEACEFUL) {
                food.setFoodLevel(Math.max(food.getFoodLevel() - 1, 0));
            }
        }
    }

    /**
     * Saturated fast regen: heal saturation/6 every 10 ticks at
     * foodLevel 20, paying back what it healed.
     *
     * @return true when this branch ran (owns the timer this tick)
     */
    private boolean trySaturatedRegen(BotBodyEntity body, FoodData food, boolean naturalRegen, boolean hurt) {
        if (naturalRegen && food.getSaturationLevel() > 0.0F && hurt && food.getFoodLevel() >= 20) {
            if (++tickTimer >= 10) {
                float healed = Math.min(food.getSaturationLevel(), 6.0F);
                body.heal(healed / 6.0F);
                food.addExhaustion(healed);
                tickTimer = 0;
            }
            return true;
        }
        return false;
    }

    /**
     * Slow regen: 1 HP per 80 ticks at foodLevel>=18, costing 6.0
     * exhaustion.
     *
     * @return true when this branch ran (owns the timer this tick)
     */
    private boolean trySlowRegen(BotBodyEntity body, FoodData food, boolean naturalRegen, boolean hurt) {
        if (naturalRegen && food.getFoodLevel() >= 18 && hurt) {
            if (++tickTimer >= 80) {
                body.heal(1.0F);
                food.addExhaustion(6.0F);
                tickTimer = 0;
            }
            return true;
        }
        return false;
    }

    /**
     * Starvation: 1.0 starve damage per 80 ticks at foodLevel 0,
     * with vanilla's difficulty/health gating.
     *
     * @return true when this branch ran (owns the timer this tick)
     */
    private boolean tryStarve(BotBodyEntity body, FoodData food) {
        if (food.getFoodLevel() > 0) {
            return false;
        }
        if (++tickTimer >= 80) {
            Difficulty difficulty = body.level().getDifficulty();
            if (body.getHealth() > 10.0F
                    || difficulty == Difficulty.HARD
                    || (body.getHealth() > 1.0F && difficulty == Difficulty.NORMAL)) {
                body.hurt(body.damageSources().starve(), 1.0F);
            }
            tickTimer = 0;
        }
        return true;
    }
}
