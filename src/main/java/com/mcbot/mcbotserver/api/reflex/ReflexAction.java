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
    DIG,

    /**
     * Submit a reflex-owned rescue mission: the controller parks the
     * current mission for one tick (FREEZE hold) and submits a
     * rescue mission (a GotoProcess to a safe cell) to the arbiter;
     * from the next tick the rescue runs through the normal mission
     * stage - the same handoff shape as ENGAGE, but the mission is a
     * pathing goal rather than a fight.
     *
     * <p>Used by lava escape (nearest non-liquid shore cell) and fire
     * find-water (nearest water-adjacent cell). The rescue factory
     * reads the body state to decide which target to scan for; an
     * ESCAPE decision whose factory finds no reachable target degrades
     * to the freeze hold (park and escalate) rather than submitting a
     * doomed mission. Priority is carried by the rule, not the kind:
     * lava escape outranks everything (4 HP/tick does not negotiate),
     * fire find-water slots between FREEZE and ENGAGE.
     */
    ESCAPE,

    /**
     * Eat the best food the sensor found: the controller holds the
     * body still (MOVE), selects the sensed best-food hotbar slot
     * (SLOT) and holds the use claim (USE) every tick the rule keeps
     * firing; the adapter consumes exactly one item per rising press
     * edge through the vanilla finishUsingItem chain (sound,
     * probability effects, shrink, EAT game event) plus FoodData
     * nutrition. The rising-edge gate makes one firing episode eat
     * exactly one item - sense then sees the restored food level and
     * the rule releases. Hungry with no sensed food never reaches
     * this action: that is issue 0010's escalation contract, not a
     * body reflex.
     */
    EAT
}
