package com.mcbot.mcbotserver.api.reflex;

/**
 * What the controller does with the body on the tick a reflex rule
 * wins: the rule decides IF and HOW URGENT (ADR-0003 section 2),
 * the action kind decides WHAT the frozen body does.
 *
 * <p>Contract: see ADR-0003 section 2 - the action vocabulary lives
 * at the decision layer, not inside rules: a rule names a kind, the
 * controller maps the kind to {@link com.mcbot.mcbotserver.api.actor.Intent}s.
 * This keeps rules pure blackboard functions and the intent shapes
 * (which are boundary-A surface) owned by exactly one place.
 */
public enum ReflexAction {

    /**
     * Halt locomotion: MOVE(0, 0) under the reflex claim. The
     * default for rules that only need the body to stop being a
     * participant (low health, crash adjacency).
     */
    FREEZE,

    /**
     * Ascend: hold jump so vanilla fluid physics swims the body up
     * (LivingEntity#aiStep routes a held jump in water depth to
     * jumpInFluid, +0.04/tick upward impulse). For the drowning
     * reflex - no pathing, no self-computed physics; the engine is
     * the only thing that moves the body (boundary A).
     */
    ASCEND
}
