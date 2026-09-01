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
    EAT,

    /**
     * Submit a reflex-owned forage mission (issue 0010, ledger 34):
     * the controller parks the current mission for one tick (FREEZE
     * hold) and submits a HungryProcess to the arbiter through the
     * forage seat - the same handoff shape as ENGAGE and ESCAPE. The
     * rule only fires hungry-without-food; the mission walks to the
     * sensed berry bush, digs it, and completes when pickup lands
     * food in inventory - from there the EAT reflex chews. A
     * factory-less or sensor-empty handoff degrades to the freeze
     * hold (park and escalate per 0010 section 5).
     */
    FORAGE,

    /**
     * One-shot water-bucket MLG (mine-school landing): the controller
     * aims at the sensed ground cell (ROT), selects the water-bucket
     * hotbar slot (SLOT) and pulses the use claim (USE) - vanilla
     * resolves the empty-through-POV-raycast placement, so the source
     * lands above whatever the descending body is about to hit and
     * the water cancels the fall damage. Self-limiting: after the
     * placement the bucket is empty, the sensor's water-bucket slot
     * reads -1 and the rule goes silent. Pulsed rather than held: a
     * failed first placement (pose lag, ray miss) must retry on the
     * next rising edge instead of hanging on a stuck press.
     */
    WATER_BUCKET,

    /**
     * Drink the best healing potion the sensor found: the controller
     * holds the body still (MOVE), selects the sensed potion hotbar
     * slot (SLOT) and holds the use claim (USE) every tick the rule
     * keeps firing; the adapter consumes exactly one potion per rising
     * press edge through the drinkHeldItem chain (finishUsingItem
     * applies effects, manual shrink, glass-bottle recovery, DRINK
     * game event). The rising-edge gate makes one firing episode drink
     * exactly one potion - sense then sees the restored health and the
     * rule releases. Low health with no sensed potion never reaches
     * this action: that is an acquisition/harness problem, not a body
     * reflex. Sits below ENGAGE (combat missions own their consumable
     * pacing) and above EAT (health is more urgent than hunger).
     */
    DRINK;

    /**
     * The owner prefix for all reflex-originated claims. The production
     * side ({@code ReflexClaimInjector}) constructs claim owners as
     * {@code REFLEX_OWNER_PREFIX + ruleName()}; the consumer side
     * ({@code BindingActor}) detects reflex intent via
     * {@code startsWith(REFLEX_OWNER_PREFIX)}. Shared constant so a
     * rename on either side breaks the other at compile time instead of
     * silently degrading intent detection.
     */
    public static final String REFLEX_OWNER_PREFIX = "reflex:";

    /**
     * Owner prefix for EAT-family reflex claims (rule names begin with
     * {@code EAT_}, e.g. {@code EAT_WHEN_HUNGRY}). Used by
     * {@code BindingActor} to distinguish a reflex eat intent from a
     * generic harness USE claim (which may be a weapon swing).
     */
    public static final String REFLEX_EAT_OWNER_PREFIX = "reflex:EAT_";

    /**
     * Owner prefix for DRINK-family reflex claims (rule names begin with
     * {@code DRINK_}, e.g. {@code DRINK_ON_LOW_HEALTH}). Used by
     * {@code BindingActor} to distinguish a reflex drink intent from a
     * generic harness USE claim.
     */
    public static final String REFLEX_DRINK_OWNER_PREFIX = "reflex:DRINK_";
}
