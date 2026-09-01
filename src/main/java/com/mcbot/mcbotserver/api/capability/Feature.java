package com.mcbot.mcbotserver.api.capability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a single behavioral feature as an atomic unit of a capability
 * face. The Python capability scanner reads these annotations from source
 * and builds the feature matrix — the annotation is the single source of
 * truth for what a feature is, where it lives, and what vanilla behavior
 * it mirrors or deviates from.
 *
 * <p>Feature ids follow the convention {@code <face>.<subfeature>}
 * (e.g. {@code combat.melee.crit_hit}). The parent face must exist in
 * the capability seed; the scanner rejects annotations whose face is
 * unknown so a typo fails the build gate rather than silently creating
 * an orphan.
 *
 * <p>Retention is SOURCE: the annotation exists for the offline scanner
 * only and carries no runtime cost. It is not a Forge event subscriber
 * or a mixin marker — putting it on a method does not change how the
 * JVM or MC loads that method.
 *
 * <p>Contract: see ADR-0002 (capability model) and boundaries.md
 * section B (behavior monopolizes Actor via per-channel claims).
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD})
public @interface Feature {

    /**
     * Globally unique feature id: {@code <face>.<subfeature>}.
     *
     * @return the feature id, never null or empty
     */
    String id();

    /**
     * Parent capability face id (e.g. {@code combat.melee}). Must match
     * a seeded capability id.
     *
     * @return the parent face id, never null or empty
     */
    String face();

    /**
     * One-sentence description of what this feature does. The scanner
     * surfaces this verbatim in the feature matrix; it should state the
     * behavioral contract, not the implementation mechanism.
     *
     * @return the feature description, never null
     */
    String description();

    /**
     * Vanilla reference anchor: decompiled class/method or a
     * player-behavior-RE.md section. Empty string means no direct
     * vanilla counterpart (bot-specific behavior).
     *
     * @return the vanilla reference anchor, or empty string
     */
    String vanillaRef() default "";

    /**
     * How this implementation deviates from vanilla. Empty string means
     * behavior matches vanilla exactly. When non-empty, state the
     * difference and the reason in one sentence.
     *
     * @return the deviation note, or empty string
     */
    String deviation() default "";
}
