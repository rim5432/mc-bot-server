"""Player-behavior reference baseline for coverage analysis.

Each category carries an exhaustive list of what a vanilla player can
do in that domain, organized by behavioral axis. The ``capability
domain`` command diffs this baseline against the matrix faces to
answer "does the bot's design cover what a player can do?"

A reference entry is NOT a capability face — it is the thing a face
might cover. ``mapped_face`` links it to a matrix face when one
exists; entries with no mapping are coverage gaps by definition.

Adding a new category here is the first step before seeding faces for
it: the reference defines the universe, the faces declare what is
implemented.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class ReferenceBehavior:
    """One thing a vanilla player can do in a domain.

    Attributes:
        id: stable identifier, ``<category>.<axis>.<slug>``
        name: short human-readable label
        axis: sub-domain axis (offense, defense, movement, ...)
        description: what the player action is and when it matters
        vanilla_ref: decompiled class / method that implements it
        mapped_face: capability face id that covers this, or None when
            no matrix face exists (a coverage gap)
        coverage_note: optional note on partial coverage or why the
            mapping is weak (e.g. "adapter supports it but no behavior
            invokes it during combat")
    """
    id: str
    name: str
    axis: str
    description: str
    vanilla_ref: str = ""
    mapped_face: Optional[str] = None
    coverage_note: str = ""


# ---------------------------------------------------------------------------
# Combat domain — the full vanilla player combat action set.
#
# Axes:
#   offense      — things that deal damage to a target
#   defense      — things that prevent or mitigate incoming damage
#   movement     — positional tactics during combat
#   perception   — information the player uses to make combat decisions
#   consumables  — items consumed during combat for effect
#   equipment    — gear choices and swaps driven by combat context
#   environment  — using blocks, fluids, and terrain as combat tools
# ---------------------------------------------------------------------------
COMBAT_REFERENCE: list[ReferenceBehavior] = [
    # --- offense ---
    ReferenceBehavior(
        id="combat.offense.melee_swing",
        name="Melee swing with cooldown pacing",
        axis="offense",
        description="Left-click attack at full charge; weapon attack-speed "
                    "governs the cooldown. Partial-charge hits deal reduced "
                    "damage; the player times clicks to the charge meter.",
        vanilla_ref="Player.attack; Player.attackStrengthTicker (decompiled 1.20.1)",
        mapped_face="combat.melee",
        coverage_note="Bot paces swings at a fixed 10-tick window and only "
                      "deals damage at full charge; no partial-charge damage. "
                      "Face is shipped but UNTESTED (no spec, no impl anchor).",
    ),
    ReferenceBehavior(
        id="combat.offense.critical_hit",
        name="Critical hit (falling, not sprinting)",
        axis="offense",
        description="A fully-charged hit while falling (fallDistance > 0, "
                    "onGround false, not in water, not blind, not climbing, "
                    "not riding, not sprinting) deals 1.5x damage and spawns "
                    "crit particles. Players jump to force crits.",
        vanilla_ref="Player.attack crit branch (decompiled 1.20.1)",
        mapped_face="combat.melee",
        coverage_note="MeleeResolver detects crit conditions passively but no "
                      "behavior claims jump to manufacture crits. The face "
                      "covers the damage formula, not the tactical use.",
    ),
    ReferenceBehavior(
        id="combat.offense.sweep_attack",
        name="Sword sweep attack (aoe on non-crit fully-charged hit)",
        axis="offense",
        description="A non-crit, non-sprinting, on-ground fully-charged hit "
                    "with a sword also hits bystanders in the sweep box for "
                    "1 + SweepingEdge ratio damage and 0.4 knockback.",
        vanilla_ref="Player.attack sweep branch (decompiled 1.20.1)",
        mapped_face="combat.melee",
        coverage_note="MeleeResolver.performSweepAttack replicates the full "
                      "chain. Covered as a damage mechanic, not as a tactic "
                      "(bot does not choose swords for sweep matchups).",
    ),
    ReferenceBehavior(
        id="combat.offense.bow_shot",
        name="Bow: draw, aim with arrow drop, release",
        axis="offense",
        description="Hold right-click to draw, aim above the target to "
                    "compensate for gravity, release at full charge (20 ticks) "
                    "for max damage. Arrows travel in a parabola.",
        vanilla_ref="BowItem.use; BowItem.releaseUsing (decompiled 1.20.1)",
        mapped_face="combat.bow_draw",
        coverage_note="Bot draws for 20 ticks and applies a constant-velocity "
                      "drop approximation (0.0028 * range^2). Currently RED "
                      "in the newest engine run (bowChargedShotDamagesDistantTarget).",
    ),
    ReferenceBehavior(
        id="combat.offense.crossbow",
        name="Crossbow: load, fire, multishot / piercing",
        axis="offense",
        description="Right-click to load (takes time), then right-click to "
                    "fire. Multishot fires 3 arrows; Piercing passes through "
                    "entities. Distinct charge model from the bow.",
        vanilla_ref="CrossbowItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. RangedLoadouts only recognizes bows. "
                      "Crossbow load-fire cycle is not implemented.",
    ),
    ReferenceBehavior(
        id="combat.offense.trident",
        name="Trident: melee throw, riptide, loyalty, channeling",
        axis="offense",
        description="Melee weapon that can also be thrown (right-click). "
                    "Riptide propels the player through water/rain; Loyalty "
                    "returns the thrown trident; Channeling strikes lightning "
                    "on a hit target during thunder.",
        vanilla_ref="TridentItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Not in RangedLoadouts, no throw "
                      "behavior, no riptide movement.",
    ),
    ReferenceBehavior(
        id="combat.offense.knockback",
        name="Knockback control (sprint-attack vs normal)",
        axis="offense",
        description="A sprinting attack deals extra knockback (1.5x) but "
                    "suppresses crits and sweep. Players choose sprint-hit for "
                    "knockback (e.g. pushing a creeper away) or normal hit "
                    "for crit/sweep damage.",
        vanilla_ref="Player.attack knockback branch (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Sprint is an adapter auto-policy, not "
                      "a combat decision. Bot cannot choose between crit and "
                      "knockback.",
    ),
    ReferenceBehavior(
        id="combat.offense.weapon_switch_tactical",
        name="Tactical weapon switching by target type",
        axis="offense",
        description="Switch to Bane of Arthropods vs spiders, Smite vs "
                    "undead, Sharpness otherwise; use an axe to disable "
                    "shields; use a sword for sweep vs groups.",
        vanilla_ref="EnchantmentHelper.getDamageBonus; AxeItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="holdBestWeapon uses a static per-hit damage ranking "
                      "that ignores target type. No shield-disable logic. "
                      "combat.melee covers the damage formula only.",
    ),
    # --- defense ---
    ReferenceBehavior(
        id="combat.defense.shield_block",
        name="Raise shield to block incoming damage",
        axis="defense",
        description="Hold right-click with a shield in the off-hand (or main "
                    "hand) to block frontal attacks. Blocked melee damage is "
                    "negated; blocked projectile damage is negated but the "
                    "shield takes durability. Axe attacks can disable shields.",
        vanilla_ref="LivingEntity.isDamageSourceBlocked; ShieldItem (decompiled 1.20.1)",
        mapped_face="combat.shield",
        coverage_note="Adapter supports shield raise on USE claim, but NO "
                      "combat behavior claims USE to raise a shield during "
                      "combat. The face is shipped+GREEN but only proves the "
                      "adapter can raise a shield when externally driven.",
    ),
    ReferenceBehavior(
        id="combat.defense.armor",
        name="Wear armor for damage reduction",
        axis="defense",
        description="Armor points + toughness reduce incoming damage per the "
                    "vanilla formula. Players choose armor by protection type "
                    "(Protection, Blast Protection, Fire Protection, Projectile "
                    "Protection) and swap for specific threats.",
        vanilla_ref="LivingEntity.damageSource armor calc (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No combat-domain face. inventory.equipment_mirror and "
                      "inventory.armor_classification exist but are not "
                      "combat-driven — no 'switch to fire prot when burning' "
                      "logic.",
    ),
    ReferenceBehavior(
        id="combat.defense.dodge_projectile",
        name="Dodge or block incoming projectiles",
        axis="defense",
        description="Move laterally to evade arrows, fireballs, and "
                    "shulker bullets; or raise a shield to block. Players "
                    "track projectile trajectories and react.",
        vanilla_ref="AbstractArrow; LargeFireball (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. EntitySnapshot does not carry "
                      "projectiles; no projectile perception or dodge behavior.",
    ),
    ReferenceBehavior(
        id="combat.defense.retreat",
        name="Retreat / disengage when outmatched",
        axis="defense",
        description="Move away from a threat when health is low, weapon is "
                    "inferior, or the target is too dangerous. Players "
                    "evaluate risk and choose flight over fight.",
        vanilla_ref="Player movement input (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. FreezeOnLowHealthRule stops the body "
                      "but does not retreat. DefendProcess has no "
                      "outmatched-assessment; only ENGAGEMENT_REFUSED for "
                      "unarmed ranged types.",
    ),
    # --- movement ---
    ReferenceBehavior(
        id="combat.movement.chase",
        name="Chase a moving target",
        axis="movement",
        description="Close distance to a target that is moving away, "
                    "navigating around obstacles. Players path toward the "
                    "target's current position.",
        vanilla_ref="Player movement input (player-driven)",
        mapped_face="combat.melee",
        coverage_note="DefendProcess/AttackProcess emit GoalNear and A* "
                      "pathing handles chase. Covered as locomotion, but the "
                      "face is labeled combat.melee (damage), not chase.",
    ),
    ReferenceBehavior(
        id="combat.movement.strafe_circle",
        name="Strafe / circle around target to avoid hits",
        axis="movement",
        description="Move laterally around the target while keeping it in "
                    "front, making it harder for the target to land hits "
                    "(especially melee mobs and skeletons). Players hold "
                    "sideways movement while facing the target.",
        vanilla_ref="Player movement input (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. CombatBehavior explicitly owns NO "
                      "locomotion; PathingBehavior is point-to-point A* with "
                      "no circling. Intent.Move.strafe exists but no combat "
                      "behavior uses it.",
    ),
    ReferenceBehavior(
        id="combat.movement.kite",
        name="Kite: attack while maintaining distance",
        axis="movement",
        description="Ranged players back away while shooting, keeping the "
                      "target at bow range. Melee players hit-and-run against "
                      "stronger targets.",
        vanilla_ref="Player movement input (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. Only RANGED_STANDOFF=10 for ranged "
                      "target types; no hit-and-run for melee, no "
                      "distance-maintenance while shooting.",
    ),
    ReferenceBehavior(
        id="combat.movement.jump_crit",
        name="Jump to force critical hits",
        axis="movement",
        description="Time jumps so the falling phase coincides with the "
                    "swing, guaranteeing crits. Advanced players "
                    "jump-crit every hit.",
        vanilla_ref="Player.jump; Player.attack crit condition (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Intent.Move.jump exists but no "
                      "combat behavior claims it. MeleeResolver passively "
                      "detects crits but does not manufacture them.",
    ),
    ReferenceBehavior(
        id="combat.movement.sprint_attack",
        name="Sprint attack for knockback",
        axis="movement",
        description="Sprint into a hit for 1.5x knockback, useful for "
                    "pushing enemies off ledges or away from you. Suppresses "
                    "crits and sweep.",
        vanilla_ref="Player.attack sprint-knockback branch (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Sprint is adapter auto-policy, not a "
                      "combat choice.",
    ),
    ReferenceBehavior(
        id="combat.movement.sneak_approach",
        name="Sneak to approach without triggering detection",
        axis="movement",
        description="Sneaking reduces the detection range of most mobs and "
                    "prevents walking off edges. Players sneak to get the "
                    "first hit or to reposition safely.",
        vanilla_ref="Mob.checkDespawn; Player.setShiftKeyDown (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Intent.Move.sneak exists but combat "
                      "does not use it. motion.sneak face covers the input "
                      "state, not combat use.",
    ),
    # --- perception ---
    ReferenceBehavior(
        id="combat.perception.target_identify",
        name="Identify target type, health, position",
        axis="perception",
        description="Players see the mob's type (zombie, skeleton, creeper), "
                    "health bar, and exact position. This drives weapon "
                    "choice, engagement decision, and targeting priority.",
        vanilla_ref="ClientLevel.entities; GUI health rendering",
        mapped_face="combat.melee",
        coverage_note="EntitySnapshot carries id, type, CellPos, health, "
                      "maxHealth. Position is block-cell granularity (not "
                      "sub-block), which the MeleeResolver works around with "
                      "a cone test.",
    ),
    ReferenceBehavior(
        id="combat.perception.threat_priority",
        name="Threat prioritization (ranged first, then melee)",
        axis="perception",
        description="Players assess which enemy is most dangerous and engage "
                    "it first — typically skeletons / witches / creepers "
                    "before zombies, because ranged threats deal damage from "
                    "afar and creepers can explode.",
        vanilla_ref="Player decision-making (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. DefendProcess picks the NEAREST "
                      "hostile only, with no threat-weighted ranking. "
                      "EngageOnHostileProximityRule fires on proximity, not "
                      "threat type.",
    ),
    ReferenceBehavior(
        id="combat.perception.entity_state",
        name="Read entity state: creeper fuse, skeleton draw, burning, shield",
        axis="perception",
        description="Players see when a creeper is hissing/expanding (about "
                    "to explode), when a skeleton is drawing a bow, when an "
                    "enemy is burning, or when an enemy is blocking with a "
                    "shield. These states drive immediate reactions (back "
                    "off, dodge, switch to axe).",
        vanilla_ref="LivingEntity data watchers; Creeper swell; Skeleton bow "
                    "goal (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. EntitySnapshot has only 5 fields — no "
                      "entity state. Bot cannot react to creeper fuses, "
                      "skeleton draws, burning targets, or shield users.",
    ),
    ReferenceBehavior(
        id="combat.perception.projectile_track",
        name="Track incoming projectiles (arrows, fireballs, potions)",
        axis="perception",
        description="Players see arrows in flight, fireballs, thrown potions, "
                    "and shulker bullets, and can dodge or block them.",
        vanilla_ref="AbstractArrow; ThrowableProjectile (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. perception.projectiles face is about "
                      "the fishing bobber seam, not combat projectiles. No "
                      "projectile scanning in LevelThreatSensor.",
    ),
    ReferenceBehavior(
        id="combat.perception.multi_target",
        name="Track multiple targets simultaneously",
        axis="perception",
        description="Players are aware of all nearby enemies, not just one. "
                    "They can switch targets, watch for flanks, and use "
                    "sweep attacks to hit groups.",
        vanilla_ref="Player awareness (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. DefendProcess locks one target and "
                      "does not switch. Sweep is a damage side-effect, not a "
                      "multi-target strategy.",
    ),
    # --- consumables ---
    ReferenceBehavior(
        id="combat.consumables.heal_combat",
        name="Eat food / golden apple during combat to heal",
        axis="consumables",
        description="Players eat golden apples, steak, or other food mid-fight "
                    "to restore health. Golden apples also grant Absorption "
                    "and Regeneration; enchanted golden apples grant stronger "
                    "effects.",
        vanilla_ref="FoodProperties; GoldenAppleItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. EatWhenHungryRule fires on low hunger, "
                      "not low health in combat. No combat-time healing "
                      "decision. hunger.eat_chain covers the eat mechanic, "
                      "not combat use.",
    ),
    ReferenceBehavior(
        id="combat.consumables.drink_potion",
        name="Drink potions (strength, speed, fire resistance, healing)",
        axis="consumables",
        description="Players drink potions to buff themselves before or during "
                    "combat: Strength for damage, Speed for kiting, Fire "
                    "Resistance for lava/blaze fights, Instant Healing for "
                    "emergency heal.",
        vanilla_ref="PotionUtils; PotionItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. No potion detection, no drink behavior, "
                      "no buff tracking. RangedLoadouts does not recognize "
                      "potions.",
    ),
    ReferenceBehavior(
        id="combat.consumables.throw_potion",
        name="Throw splash / lingering potions",
        axis="consumables",
        description="Players throw splash potions of harming / weakness / "
                    "slowness at enemies, or splash healing to heal "
                    "themselves in a pinch. Lingering potions create area "
                    "effect clouds.",
        vanilla_ref="SplashPotion; LingeringPotion; AreaEffectCloud "
                    "(decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. No throw behavior, no potion item "
                      "recognition.",
    ),
    ReferenceBehavior(
        id="combat.consumables.totem",
        name="Totem of Undying (off-hand, saves from death)",
        axis="consumables",
        description="A totem in the off-hand (or main hand) prevents death "
                    "when health would drop to 0, granting Regeneration II, "
                    "Absorption, and Fire Resistance, and consuming the "
                    "totem.",
        vanilla_ref="LivingEntity.checkTotemDeathProtection; TotemItem "
                    "(decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. No off-hand slot selection. "
                      "Intent.SelectSlot only covers hotbar 0-8. Totem "
                      "cannot be equipped.",
    ),
    ReferenceBehavior(
        id="combat.consumables.ender_pearl",
        name="Ender pearl teleport (engage or escape)",
        axis="consumables",
        description="Players throw ender pearls to teleport to the hit "
                    "location — useful for closing distance to a fleeing "
                    "target or escaping a losing fight. Deals 5 fall damage "
                    "on arrival.",
        vanilla_ref="EnderPearl; Player.teleport (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. No throw behavior, no pearl "
                      "recognition.",
    ),
    # --- equipment ---
    ReferenceBehavior(
        id="combat.equipment.offhand",
        name="Use off-hand (shield, totem, map, food)",
        axis="equipment",
        description="Players hold a shield or totem in the off-hand, leaving "
                    "the main hand for weapons. The off-hand slot is "
                    "independent of the hotbar.",
        vanilla_ref="Player.inventory.offhand; LivingEntity.getItemBySlot "
                    "(decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. inventory.shape mentions 41 slots "
                      "(including offhand) but Intent.SelectSlot only covers "
                      "hotbar 0-8. No off-hand equip behavior.",
    ),
    ReferenceBehavior(
        id="combat.equipment.armor_swap",
        name="Swap armor for specific threats",
        axis="equipment",
        description="Players switch to Blast Protection vs creepers, Fire "
                    "Protection vs blazes, Projectile Protection vs "
                    "skeletons. Armor swaps happen via the inventory menu "
                    "or hotbar shortcuts.",
        vanilla_ref="ArmorItem; Player.inventory (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. WearPlanner exists but is not "
                      "combat-driven. No threat-aware armor switching.",
    ),
    ReferenceBehavior(
        id="combat.equipment.weapon_by_type",
        name="Select weapon by target type and matchup",
        axis="equipment",
        description="Players choose swords for general melee, axes for "
                    "shield-users, tridents for ranged/melee hybrid, bows for "
                    "kiting. Enchantments (Bane/Smite/Sharpness) further "
                    "drive the choice.",
        vanilla_ref="ItemStack.getAttributeModifiers; EnchantmentHelper "
                    "(decompiled 1.20.1)",
        mapped_face="combat.melee",
        coverage_note="holdBestWeapon picks the highest per-hit damage in "
                      "the hotbar, ignoring target type and matchup. "
                      "combat.bow_slot covers bow SLOT ownership while "
                      "ranging, but no cross-weapon tactical selection.",
    ),
    # --- environment ---
    ReferenceBehavior(
        id="combat.environment.block_defense",
        name="Place blocks for defense (walls, pillars, bridging)",
        axis="environment",
        description="Players place blocks to create cover from projectiles, "
                    "pillar up to gain height advantage, bridge across gaps "
                    "to chase, or wall off a creeper before it explodes.",
        vanilla_ref="BlockItem.place; ServerPlayerGameMode.useItemOn "
                    "(decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. Intent.InteractBlock exists but "
                      "combat never uses it. No block placement during "
                      "combat. interaction.blockitem_place covers the "
                      "mechanic, not combat use.",
    ),
    ReferenceBehavior(
        id="combat.environment.terrain_use",
        name="Use terrain (high ground, chokepoints, water, lava)",
        axis="environment",
        description="Players fight from high ground (melee mobs can't hit "
                    "up), funnel enemies through chokepoints, use water to "
                    "negate fall damage or slow pursuing mobs, and use lava "
                    "as a damage barrier.",
        vanilla_ref="Player movement + environment interaction (player-driven)",
        mapped_face=None,
        coverage_note="No matrix face. Pathing is pure A* pathfinding with "
                      "no combat terrain evaluation. No high-ground seeking, "
                      "no chokepoint use, no water/lava tactical use.",
    ),
    ReferenceBehavior(
        id="combat.environment.fluid_bucket",
        name="Use water / lava bucket as combat tool",
        axis="environment",
        description="Players place water to extinguish themselves or create "
                    "slows, place lava to damage enemies or block paths, and "
                    "use water buckets for MLG (negate fall damage).",
        vanilla_ref="BucketItem.use; DispenserBlock (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. WaterBucketOnFallRule is a survival "
                      "reflex (fall damage), not a combat tool. No lava "
                      "bucket combat use. interaction.bucket_rod covers the "
                      "mechanic, not combat use.",
    ),
    ReferenceBehavior(
        id="combat.environment.flint_steel",
        name="Use flint and steel / fire charge to ignite enemies",
        axis="environment",
        description="Players ignite blocks or entities directly with flint "
                    "and steel or fire charges, dealing fire damage over "
                    "time. Useful against mobs vulnerable to fire.",
        vanilla_ref="FlintAndSteelItem; FireChargeItem (decompiled 1.20.1)",
        mapped_face=None,
        coverage_note="No matrix face. No fire-starting behavior, no flint "
                      "recognition.",
    ),
]


# ---------------------------------------------------------------------------
# Registry: category -> reference list. New categories append here.
# ---------------------------------------------------------------------------
REFERENCE_BY_CATEGORY: dict[str, list[ReferenceBehavior]] = {
    "combat": COMBAT_REFERENCE,
}


# Canonical axis order for rendering (combat). Other categories can
# define their own; the renderer falls back to alphabetical.
AXIS_ORDER: dict[str, list[str]] = {
    "combat": [
        "offense",
        "defense",
        "movement",
        "perception",
        "consumables",
        "equipment",
        "environment",
    ],
}


def reference_for(category: str) -> list[ReferenceBehavior]:
    """Return the player-behavior reference list for a category.

    Returns an empty list when no reference is defined — the domain
    report renders that as "no reference baseline defined for this
    category" rather than crashing.
    """
    return REFERENCE_BY_CATEGORY.get(category, [])


def axis_order_for(category: str) -> list[str]:
    """Return the canonical axis display order for a category.

    Falls back to the sorted set of axes found in the reference when
    no explicit order is defined.
    """
    if category in AXIS_ORDER:
        return AXIS_ORDER[category]
    refs = reference_for(category)
    return sorted({r.axis for r in refs})
