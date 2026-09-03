package com.mcbot.mcbotserver.adapter.entity;

import net.minecraft.util.Mth;

/**
 * The body's experience ledger - a vanilla
 * {@code Player.experience*} mirror (level, progress, total) owning
 * the vanilla level-up arithmetic, extracted from BotBodyEntity so
 * the carrier keeps only its engine-override surface - the same
 * state-vs-math split as {@link HungerTicker}.
 *
 * <p>Verbatim formulas from decompiled 1.20.1 Player.java:
 * {@code getXpNeededForNextLevel} (7+2L below 15, 37+5(L-15) to 30,
 * 112+9(L-30) above), {@code giveExperiencePoints} lines 1718-1743
 * (progress bar with signed overflow rolling across level
 * boundaries in both directions), {@code giveExperienceLevels}
 * (progress resets only on level LOSS - positive grants keep the
 * bar, so orb-earned progress precision survives level-ups).
 */
public final class ExperienceMirror {

    /**
     * Experience level (vanilla {@code Player.experienceLevel}
     * mirror). 0 at spawn; increases as XP orbs are absorbed.
     */
    private int experienceLevel;

    /**
     * Progress toward the next level, 0.0..1.0 (vanilla
     * {@code Player.experienceProgress} mirror). Added to by
     * {@link #addPoints(int)}; when it reaches 1.0 the level
     * increments and the remainder carries.
     */
    private float experienceProgress;

    /**
     * Total experience points ever absorbed (vanilla
     * {@code Player.totalExperience} mirror). Used for scoreboard
     * and anvil cost display; never decreases on level-up (only the
     * progress bar resets).
     */
    private int totalExperience;

    /**
     * Gets the current experience level.
     *
     * @return the current level; never negative
     */
    public int getLevel() {
        return experienceLevel;
    }

    /**
     * Gets progress toward the next level.
     *
     * @return the progress fraction, 0.0..1.0
     */
    public float getProgress() {
        return experienceProgress;
    }

    /**
     * Gets the total experience points ever absorbed.
     *
     * @return the total; never negative
     */
    public int getTotal() {
        return totalExperience;
    }

    /**
     * XP required to go from the current level to the next. Verbatim
     * vanilla formula (decompiled Player.java
     * {@code getXpNeededForNextLevel}): level {@code >= 30}:
     * {@code 112 + (level-30)*9}; level {@code >= 15}:
     * {@code 37 + (level-15)*5}; else {@code 7 + level*2}.
     *
     * @return XP points needed for the next level
     */
    public int getXpNeededForNextLevel() {
        if (experienceLevel >= 30) {
            return 112 + (experienceLevel - 30) * 9;
        }
        if (experienceLevel >= 15) {
            return 37 + (experienceLevel - 15) * 5;
        }
        return 7 + experienceLevel * 2;
    }

    /**
     * Add experience points, leveling up as needed. Mirrors
     * {@code Player.giveExperiencePoints} (decompiled lines
     * 1718-1743): adds to total, fills the progress bar, and rolls
     * overflow into level-ups. Negative values subtract (used by
     * anvil/enchant costs).
     *
     * @param points XP to add (or subtract if negative)
     */
    public void addPoints(int points) {
        if (points == 0) {
            return;
        }
        // Clamp to int range to match vanilla (net.minecraft.util.Mth.clamp
        // is not needed here because int arithmetic is already bounded).
        totalExperience = Math.max(0, totalExperience + points);
        float needed = getXpNeededForNextLevel();
        experienceProgress += (float) points / needed;
        while (experienceProgress < 0.0F) {
            float overflow = experienceProgress * needed;
            if (experienceLevel > 0) {
                experienceLevel--;
                needed = getXpNeededForNextLevel();
                experienceProgress = 1.0F + overflow / needed;
            } else {
                experienceProgress = 0.0F;
                break;
            }
        }
        while (experienceProgress >= 1.0F) {
            float overflow = (experienceProgress - 1.0F) * needed;
            experienceLevel++;
            needed = getXpNeededForNextLevel();
            experienceProgress = overflow / needed;
        }
        experienceProgress = Mth.clamp(experienceProgress, 0.0F, 1.0F);
    }

    /**
     * Add (or subtract) whole levels. Mirrors
     * {@code Player.giveExperienceLevels}.
     *
     * @param levels levels to add (or subtract if negative)
     */
    public void addLevels(int levels) {
        experienceLevel = Math.max(0, experienceLevel + levels);
        // Vanilla resets the progress bar only on level LOSS (a menu
        // cost); positive grants (orbs, kills) keep the bar - zeroing
        // it here would drop earned progress precision every level-up.
        if (levels < 0) {
            experienceProgress = 0.0F;
        }
    }
}
