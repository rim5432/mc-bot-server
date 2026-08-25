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
    ASCEND,

    /**
     * Engage the nearest hostile: the controller parks the current
     * mission for one tick and submits a reflex-owned defend mission
     * to the arbiter; from the next tick the fight runs THROUGH the
     * mission stage (not as a held-body reflex). Needed because a
     * fight is multi-tick state (target tracking, leash, grace) and
     * reflexes are one-tick decisions - the rule only notices the
     * threat; DefendProcess owns the fight.
     */
    ENGAGE,

    /**
     * Dig the action target the decision carries: the controller
     * holds the body still (MOVE), aims at the target cell (ROT) and
     * holds the dig claim (INTERACT) every tick the rule keeps
     * firing; destroy progress accumulates adapter-side, so the
     * reflex itself stays a one-tick pure decision - the same shape
     * as ASCEND (the engine, not the reflex, carries the multi-tick
     * state). A DIG decision without a target degrades to the freeze
     * hold: missing position data must not mint a dig-at-null.
     */
    DIG
}
