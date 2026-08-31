"""Seed data extracted from player-behavior-RE.md.

These are the initial capability faces, one per vanilla mechanic
intersection. The ``implementation_status`` values are read from the
document's shipped/gap/deferred annotations as of 2026-08-29.

Seeding is idempotent: ``seed_database()`` skips any id that already
exists, so re-running after manual edits won't clobber them. New
capabilities added to the list will be inserted on the next run.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

from mcbot.capability.db import init_db
from mcbot.capability.models import Capability
from mcbot.capability.repository import CapabilityRepository

VERIFIED_AT = "2026-08-29"

SEED_CAPABILITIES: list[Capability] = [
    # --- 1. Hunger, eating, regeneration ---
    Capability(
        id="hunger.fooddata",
        name="FoodData verbatim vanilla ticking",
        category="hunger",
        description="Exhaustion / saturation / foodLevel ticked every server tick with vanilla math: saturated fast regen heals saturation/6 per 10 ticks, foodLevel >= 18 slow regen (1 HP per 80 ticks), starvation damage below foodLevel 0. natural-regeneration gamerule and difficulty gate branches exactly as vanilla.",
        implementation_status="shipped",
        vanilla_ref="Player.checkMovementStatistics; FoodData.tick (decompiled 1.20.1)",
    ),
    Capability(
        id="hunger.movement_exhaustion",
        name="Movement exhaustion from distance traveled",
        category="hunger",
        description="Vanilla rates: sprint 0.1 exhaustion per block, walk 0 per block (walking on ground is free), swim / underwater wade 0.01 per block. Sprint is 10x the swim rate, not 10x walk.",
        implementation_status="partial",
        vanilla_ref="Player.checkMovementStatistics (decompiled 1.20.1)",
        deviation="BotBodyEntity.customServerAiStep accumulates no exhaustion naturally, so it is fed manually from horizontal distance. Sprint matches vanilla at 0.1/block; walk charges 0.01/block (the vanilla swim rate, not vanilla's zero walk rate) — a non-zero baseline so a purely-walking carrier still drains hunger over time.",
    ),
    Capability(
        id="hunger.eat_chain",
        name="Eat chain: one finishUsingItem pass",
        category="hunger",
        description="Consume sound, probability effects, stack shrink, EAT game event, then FoodData nutrition. Vanilla keeps nibble state private, so the adapter consumes exactly one item per firing episode through a rising-edge gate, not a flag poll.",
        implementation_status="shipped",
        vanilla_ref="Item.finishUsingItem; FoodData.eat (decompiled 1.20.1)",
    ),
    # --- 2. Vitals ---
    Capability(
        id="vitals.lava",
        name="Lava damage: 4 HP per tick, no damage interval",
        category="vitals",
        description="The one vital that outranks everything. Lava deals 4 HP per tick with no damage interval — the highest-priority reflex trigger.",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.baseTick; lava damage branch (decompiled 1.20.1)",
    ),
    Capability(
        id="vitals.air_supply",
        name="Air supply: tops at 300, surfaces at 80",
        category="vitals",
        description="Air supply tops at 300 ticks. The bot surfaces at 80 (decision 22), leaving margin before drowning damage starts.",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.airSupply; Player.tick (decompiled 1.20.1)",
    ),
    Capability(
        id="vitals.swimming",
        name="Swimming: vanilla fluid physics",
        category="vitals",
        description="ASCEND holds jump and the physics do the ascent. Deliberate dive/ascend on the reserved yya axis is issue 0004 D3; v1 ships ascent only.",
        implementation_status="partial",
        vanilla_ref="Player.aiStep; fluid movement physics (decompiled 1.20.1)",
        deviation="v1 ships ascent only; deliberate dive is deferred (issue 0004 D3).",
    ),
    Capability(
        id="vitals.powder_snow",
        name="Powder snow: climbable escape, freeze threshold 140",
        category="vitals",
        description="Powder snow is climbable (escape is pure held jump). Vanilla full-freeze at freezeTicks = 140, where the freezing phase deals 1.0 damage per 40 ticks. Bot's climb reflex fires at TRIGGER_FREEZE = 100, leaving 40 ticks of margin.",
        implementation_status="shipped",
        vanilla_ref="Entity.getTicksRequiredToFreeze; LivingEntity.baseTick (decompiled 1.20.1)",
    ),
    Capability(
        id="vitals.fire",
        name="Fire: lethal-band only",
        category="vitals",
        description="fireTicks/20 >= health is the kill band; below it no rule fires — vanilla resolves it (issue 0008 D5).",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.baseTick; fire damage branch (decompiled 1.20.1)",
    ),
    Capability(
        id="vitals.suffocation",
        name="Suffocation: dig the eye block",
        category="vitals",
        description="Digs the eye block with authentic vanilla hand-dig math (~15 of 20 health points for the dirt class). An unbreakable eye block still kills (decision 25).",
        implementation_status="shipped",
        vanilla_ref="Player.checkMovementStatistics; suffocation damage (decompiled 1.20.1)",
    ),
    Capability(
        id="vitals.mlg_water",
        name="MLG: water block cancels fall damage",
        category="vitals",
        description="The MLG mirror aims at the ground cell, selects the bucket, and pulses use on the descent. Engine pair waterBucketBreaksAFall is staged.",
        implementation_status="deferred",
        vanilla_ref="Entity.causeFallDamage; water damage cancellation (decompiled 1.20.1)",
        deviation="Capability staged; engine test pair not yet landed.",
    ),
    # --- 3. Dig pacing and tools ---
    Capability(
        id="dig.pacing",
        name="Dig pacing: per-tick tool speed, correct-tool-for-drops",
        category="digging",
        description="Per-tick tool speed and correct-tool-for-drops drive both the 30/100 break-progress divisor and whether drops spawn.",
        implementation_status="shipped",
        vanilla_ref="ServerPlayerGameMode.destroyBlock; block break progress (decompiled 1.20.1)",
    ),
    Capability(
        id="dig.tool_speed",
        name="DiggerItem speed: only inside mineable tag",
        category="digging",
        description="DiggerItem answers its speed only inside its mineable tag; wrong tool and empty hand both return 1.0, so equal speed buys nothing, the held slot wins ties, and selection churn never flickers.",
        implementation_status="shipped",
        vanilla_ref="DiggerItem.getDestroySpeed; TieredItem (decompiled 1.20.1)",
    ),
    Capability(
        id="dig.enchantment_loot",
        name="Enchantment-aware dig speed + match_tool loot fidelity",
        category="digging",
        description="Enchantment-aware dig speed and match_tool loot fidelity are recorded gaps in the workplan gap inventory.",
        implementation_status="gap",
        vanilla_ref="EnchantmentHelper.getBlockEfficiency; LootContext (decompiled 1.20.1)",
    ),
    # --- 4. Combat ---
    Capability(
        id="combat.melee",
        name="Mob melee: no attack-speed scaling or crits",
        category="combat",
        description="Per-hit damage is the whole ranking, so an axe beats a same-tier sword. The vanilla doHurtTarget path carries enchant bonus, knockback, Fire Aspect, and shield-disable for free.",
        implementation_status="shipped",
        vanilla_ref="Mob.doHurtTarget; ItemStack.getAttributeModifiers (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.bow_slot",
        name="Bow: no attack-damage modifier, owns SLOT channel",
        category="combat",
        description="Bows carry no attack-damage modifier — any strength-comparing auto-weapon would switch off a drawn bow every tick, so the bow owns the SLOT channel while ranging.",
        implementation_status="shipped",
        vanilla_ref="BowItem; ItemStack.getAttributeModifiers (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.bow_draw",
        name="Bow draw/release: USE hold window, falling-edge fire",
        category="combat",
        description="Holding USE for the draw window (BOW_CHARGE_TICKS) then one USE release fires BowItem.releaseUsing with the accumulated charge; arrow-drop lift is 0.0028 * range^2. The facade is never player-ticked, so the adapter pumps tickUseLoop every held tick and releases on the falling edge or USE claim loss.",
        implementation_status="shipped",
        vanilla_ref="BowItem.releaseUsing; BowItem.use (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.shield",
        name="Shield blocking: evaluated on the hurt entity",
        category="combat",
        description="isDamageSourceBlocked reads the hurt entity's own blocking stack at hurt time, so the shield must be raised on the body taking the hit, not on a facade. Raise on the rising edge, release on the falling edge or USE claim loss.",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.isDamageSourceBlocked; ShieldItem (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.line_of_sight",
        name="Melee line of sight: lava counts as opaque",
        category="combat",
        description="Eye-to-surface ray in which lava counts as opaque. The bot splits this into two independent checks in MeleeResolver: sightBlocked() runs a vanilla ClipContext with Block.COLLIDER / Fluid.NONE (solid terrain only — lava is transparent to this clip), and lavaBetween() is a separate 0.5-block point-sample along the segment that flags lava. Both must pass for a swing to connect.",
        implementation_status="shipped",
        vanilla_ref="MobEntity.hasLineOfSight; ClipContext (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.hostile_acquisition",
        name="Hostile acquisition: their own AI",
        category="combat",
        description="Follow range, line of sight, and free target slots. A carrier-side presence pass is enough for hostiles to acquire the body on sight (pinned by hostilesAggroOnSight).",
        implementation_status="shipped",
        vanilla_ref="MobEntity.checkDespawn; Monster (decompiled 1.20.1)",
    ),
    # --- 5. Perception seams ---
    Capability(
        id="perception.projectiles",
        name="Projectiles invisible to LivingEntity-shaped scans",
        category="perception",
        description="The fishing bobber carries its own snapshot seam (WorldView.getBobbers). Vanilla keeps the nibble private, so the bite is derived from consecutive snapshots' vertical dip.",
        implementation_status="shipped",
        vanilla_ref="FishingHook; ClientLevel.entities (decompiled 1.20.1)",
    ),
    Capability(
        id="perception.death_flag",
        name="Scans carry no death flag",
        category="perception",
        description="A vanished target cannot be distinguished from a dead one; verdicts stay conservative (issue 0006).",
        implementation_status="shipped",
        vanilla_ref="LivingEntity; Entity.isAlive (decompiled 1.20.1)",
    ),
    Capability(
        id="perception.sleepers",
        name="All-sleepers table cannot see a PathfinderMob",
        category="perception",
        description="Sleep is verify-and-skip (real BedBlock + in reach + nighttime) with a device-side clock advance; the engine never registers the carrier as a sleeper.",
        implementation_status="partial",
        vanilla_ref="Player.isSleeping; BedBlock (decompiled 1.20.1)",
    ),
    # --- 6. Inventory, equipment, menu ---
    Capability(
        id="inventory.shape",
        name="Carrier inventory shape: 41 slots",
        category="inventory",
        description="36 main + 4 armor + 1 offhand, snapshotted immutable every perception tick, keyed by registry id.",
        implementation_status="shipped",
        vanilla_ref="Inventory; Player.getInventory (decompiled 1.20.1)",
    ),
    Capability(
        id="inventory.equipment_mirror",
        name="Equipment slots: write-through mirror over inventory container",
        category="inventory",
        description="Mainhand maps to the selected hotbar slot, offhand to slot 40, armor to flat 5..8 head..feet. LivingEntity.detectEquipmentUpdates diffs the mirror every tick and applies each item's attribute modifiers — held sword raises ATTACK_DAMAGE, worn armor raises ARMOR — with zero extra wiring, and the mob model renders held and worn items for free.",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.detectEquipmentUpdates; EquipmentSlot (decompiled 1.20.1)",
    ),
    Capability(
        id="inventory.menu_clicks",
        name="Menu clicks: vanilla click machinery",
        category="inventory",
        description="Whole-stack lifts, one-item-per-cell deposits, remainder returns, quick-move takes. Crafting resolves against the live RecipeManager; shaped recipes translate tag-expanded and pattern-relative; shapeless recipes are an ingredient list with no fixed grid cells.",
        implementation_status="shipped",
        vanilla_ref="AbstractContainerMenu.clicked; RecipeManager (decompiled 1.20.1)",
    ),
    Capability(
        id="inventory.armor_classification",
        name="Armor classification: vanilla ArmorItem flat slots 5..8",
        category="inventory",
        description="Reads vanilla ArmorItem flat slots 5..8 (VanillaArmorCatalog) and picks the highest-protection candidate per slot; an unclassifiable worn piece is never stripped.",
        implementation_status="shipped",
        vanilla_ref="ArmorItem; ArmorMaterial (decompiled 1.20.1)",
    ),
    # --- 7. Place / use / interact ---
    Capability(
        id="interaction.right_click_order",
        name="Right-click order: BlockState.use first, then ItemStack.useOn",
        category="interaction",
        description="BlockState.use first; only on PASS does ItemStack.useOn run (BlockItem placement included) — a right-click on a door opens it even while holding a block, exactly as vanilla.",
        implementation_status="shipped",
        vanilla_ref="ServerPlayerGameMode.useItemOn; BlockState.use (decompiled 1.20.1)",
    ),
    Capability(
        id="interaction.blockitem_place",
        name="BlockItem.place: direction-aware state",
        category="interaction",
        description="Stairs facing, snow layers, waterlog, replaceable target cells. Retired the Phase 1 default-state debt.",
        implementation_status="shipped",
        vanilla_ref="BlockItem.place; BlockBehaviour.BlockStateBase (decompiled 1.20.1)",
    ),
    Capability(
        id="interaction.use_item_deviations",
        name="useItemOn: two recorded deviations",
        category="interaction",
        description="The Forge RightClickBlock hook is not fired, and the carrier is a PathfinderMob acting through BotPlayerFacade rather than a ServerPlayer.",
        implementation_status="shipped",
        vanilla_ref="ServerPlayerGameMode.useItemOn (decompiled 1.20.1)",
        deviation="Forge RightClickBlock hook not fired; carrier is PathfinderMob via BotPlayerFacade, not ServerPlayer.",
    ),
    Capability(
        id="interaction.bucket_rod",
        name="Bucket fill/empty and rod cast/reel: vanilla Item.use",
        category="interaction",
        description="Ride vanilla Item.use against a POV raycast from the synced facade pose; ROT aim is the caller's precondition.",
        implementation_status="shipped",
        vanilla_ref="BucketItem.use; FishingRodItem.use (decompiled 1.20.1)",
    ),
    Capability(
        id="interaction.drop",
        name="Drop semantics: mirror Q / Ctrl-Q",
        category="interaction",
        description="Placement honors BlockItem.canPlace parity guards and air-cells-only default state.",
        implementation_status="shipped",
        vanilla_ref="Player.drop; ServerPlayerGameMode (decompiled 1.20.1)",
    ),
    Capability(
        id="interaction.menu_provider_skip",
        name="Container UI blocks (MenuProvider): deliberately skipped",
        category="interaction",
        description="Deliberately skipped by the use-block chain — the menu verbs own that surface.",
        implementation_status="deferred",
        vanilla_ref="MenuProvider; AbstractContainerMenu (decompiled 1.20.1)",
        deviation="Deliberately skipped by design; menu verbs own the container surface.",
    ),
    # --- 8. Motion vocabulary ---
    Capability(
        id="motion.sneak",
        name="Sneak: input state, not an action",
        category="motion",
        description="Vanilla has no discrete sneak action to fire — it is a pose plus movement modifier held for as long as the input bit is set. The bot mirrors this with a latched modifier (/bot sneak on|off) and the per-tick Intent.Move.sneak channel; there is no SNEAK channel to claim.",
        implementation_status="partial",
        vanilla_ref="Player.setShiftKeyDown; Pose (decompiled 1.20.1)",
        deviation="The pathing layer does not yet plan sneaked edge walks (workplan gap inventory).",
    ),
    Capability(
        id="motion.sprint",
        name="Sprint: clear-road gate",
        category="motion",
        description="Vanilla sprint needs collision-free room ahead; the carrier-local rule mirrors it (strong forward claim, dry ground, 5-block eye clip; ~1.3x speed observed).",
        implementation_status="shipped",
        vanilla_ref="Player.setSprinting; ServerPlayer.checkMovementStatistics (decompiled 1.20.1)",
    ),
    Capability(
        id="motion.ladders_vines",
        name="Ladders and vines: climbable traits, no deliberate move",
        category="motion",
        description="Climbable traits with no deliberate move yet (workplan gap inventory).",
        implementation_status="gap",
        vanilla_ref="ClimberHolder; LadderBlock; VineBlock (decompiled 1.20.1)",
    ),
]


# ---------------------------------------------------------------------------
# Harness path axis (boundary-D surface that exercises each face).
#
# The path namespace's contract is harness-interaction.md ("new
# capabilities are new paths, never new verbs"). This table is
# catalog data: curated, deliberately UNDER-mapped - only paths the
# canonical doc confirms are declared. Faces absent here render
# PATHLESS in `capability paths`; internal reflex/sense faces are
# pathless by design, the rest are review candidates.
# ---------------------------------------------------------------------------
HARNESS_PATHS: dict[str, list[str]] = {
    # motion: goto exercises walking/sprint/climb; sneak is a player write
    "motion.sprint": ["/tasks/goto"],
    "motion.ladders_vines": ["/tasks/goto"],
    "motion.sneak": ["write /player/sneak", "/tasks/goto"],
    # digging: the mine job family
    "dig.pacing": ["/tasks/mine"],
    "dig.tool_speed": ["/tasks/mine"],
    "dig.enchantment_loot": ["/tasks/mine"],
    # combat: directed engagement (target selection + line of sight +
    # melee/ranged by held item all ride the attack task)
    "combat.bow_draw": ["/entities/<id>/attack"],
    "combat.bow_slot": ["/entities/<id>/attack"],
    "combat.shield": ["/entities/<id>/attack"],
    "combat.melee": ["/entities/<id>/attack"],
    "combat.line_of_sight": ["/entities/<id>/attack"],
    "combat.hostile_acquisition": ["/entities/<id>/attack"],
    # interaction: held item against the POV ray
    "interaction.right_click_order": ["write /player/held/use"],
    "interaction.blockitem_place": ["write /player/held/use"],
    "interaction.bucket_rod": ["write /player/held/use"],
    "interaction.use_item_deviations": ["write /player/held/use"],
    # inventory: bag/free reads + station menu crafting
    "inventory.shape": ["cat /player/bag", "cat /player/inventory/free"],
    "inventory.menu_clicks": ["/stations", "/recipes/<slug>"],
}


# Source path axis (implementation files that realize each face).
#
# Catalog data, curated alongside HARNESS_PATHS. Paths are relative to
# src/main/java/com/mcbot/mcbotserver/. A face with no paths renders
# SOURCELESS in `capability paths`; the per-face staleness metric needs
# this axis populated (0/35 before this seed landed).
# ---------------------------------------------------------------------------
SOURCE_PATHS: dict[str, list[str]] = {
    # combat
    "combat.bow_draw": [
        "core/combat/RangedLoadouts.java",
        "adapter/BotPlayerFacade.java",
        "adapter/entity/BotBodyEntity.java",
        "core/tick/BotController.java",
    ],
    "combat.bow_slot": [
        "core/combat/RangedLoadouts.java",
        "core/tick/ToolSelector.java",
        "adapter/inventory/BindingInventory.java",
    ],
    "combat.shield": [
        "adapter/entity/BotBodyEntity.java",
        "adapter/BotPlayerFacade.java",
        "core/tick/BotController.java",
    ],
    "combat.melee": [
        "adapter/MeleeResolver.java",
        "core/process/AttackProcess.java",
        "core/command/AttackCommandHandler.java",
        "adapter/VanillaWeaponCatalog.java",
    ],
    "combat.line_of_sight": [
        "adapter/sensing/LevelThreatSensor.java",
        "core/process/TargetTracker.java",
        "core/world/SnapshotWorldView.java",
    ],
    "combat.hostile_acquisition": [
        "adapter/sensing/LevelThreatSensor.java",
        "core/reflex/EngageOnHostileProximityRule.java",
        "core/process/TargetTracker.java",
    ],
    # digging
    "dig.pacing": [
        "core/actor/DigPacing.java",
        "adapter/DigExecutor.java",
        "core/process/DigProcess.java",
        "core/command/DigCommandHandler.java",
    ],
    "dig.tool_speed": [
        "core/tick/ToolSelector.java",
        "adapter/VanillaToolCatalog.java",
        "adapter/DigExecutor.java",
    ],
    "dig.enchantment_loot": [
        "adapter/DigExecutor.java",
        "core/world/MapBlockTraitsRegistry.java",
        "adapter/VanillaToolCatalog.java",
    ],
    # hunger
    "hunger.fooddata": [
        "adapter/VanillaFoodCatalog.java",
        "adapter/entity/HungerTicker.java",
        "core/process/HungryProcess.java",
    ],
    "hunger.movement_exhaustion": [
        "adapter/entity/HungerTicker.java",
        "core/behavior/PathingBehavior.java",
        "adapter/entity/BotBodyEntity.java",
    ],
    "hunger.eat_chain": [
        "core/reflex/EatWhenHungryRule.java",
        "core/reflex/AcquireFoodWhenHungryRule.java",
        "core/process/HungryProcess.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/VanillaFoodCatalog.java",
    ],
    # interaction
    "interaction.right_click_order": [
        "adapter/InteractBlockExecutor.java",
        "core/command/VerbTaskHandler.java",
        "adapter/BotPlayerFacade.java",
        "adapter/ReachPolicy.java",
    ],
    "interaction.blockitem_place": [
        "adapter/InteractBlockExecutor.java",
        "core/command/VerbTaskHandler.java",
        "core/world/SnapshotWorldView.java",
    ],
    "interaction.bucket_rod": [
        "core/behavior/FishBehavior.java",
        "adapter/InteractBlockExecutor.java",
        "adapter/BotPlayerFacade.java",
    ],
    "interaction.drop": [
        "adapter/MenuVerbs.java",
        "adapter/inventory/BindingInventory.java",
        "core/menu/TransferPlanner.java",
    ],
    "interaction.menu_provider_skip": [
        "adapter/MenuOpener.java",
        "adapter/ActorMenuTransactions.java",
        "core/menu/MenuPlanner.java",
    ],
    "interaction.use_item_deviations": [
        "adapter/BotPlayerFacade.java",
        "adapter/entity/BotBodyEntity.java",
        "core/tick/BotController.java",
    ],
    # inventory
    "inventory.shape": [
        "adapter/inventory/BindingInventory.java",
        "adapter/MenuSlotLayouts.java",
        "core/menu/PlayerRegion.java",
    ],
    "inventory.equipment_mirror": [
        "adapter/entity/BotBodyEntity.java",
        "adapter/VanillaArmorCatalog.java",
        "adapter/WearVerbs.java",
        "core/menu/WearPlanner.java",
    ],
    "inventory.menu_clicks": [
        "adapter/MenuVerbs.java",
        "adapter/BotCraftingMenu.java",
        "adapter/BotInventoryMenu.java",
        "core/menu/CraftingPlanner.java",
        "core/menu/TransferPlanner.java",
        "adapter/RecipeCatalog.java",
    ],
    "inventory.armor_classification": [
        "adapter/VanillaArmorCatalog.java",
        "adapter/WearVerbs.java",
        "core/menu/WearPlanner.java",
    ],
    # motion
    "motion.sneak": [
        "adapter/entity/SneakEdgeGuard.java",
        "adapter/entity/BotBodyEntity.java",
        "core/pathing/BasicMoves.java",
    ],
    "motion.sprint": [
        "adapter/entity/BotBodyEntity.java",
        "core/pathing/BasicMoves.java",
        "core/behavior/PathingBehavior.java",
    ],
    "motion.ladders_vines": [
        "core/pathing/BasicMoves.java",
        "core/pathing/AStarPathFinder.java",
        "core/behavior/PathingBehavior.java",
        "core/behavior/WaypointCursor.java",
    ],
    # perception
    "perception.projectiles": [
        "core/behavior/FishBehavior.java",
        "adapter/sensing/LevelThreatSensor.java",
        "core/world/SnapshotWorldView.java",
    ],
    "perception.death_flag": [
        "adapter/entity/BotBodyEntity.java",
        "core/tick/BotController.java",
        "adapter/BotStateSnapshots.java",
    ],
    "perception.sleepers": [
        "adapter/entity/BotBodyEntity.java",
        "core/tick/BotController.java",
        "adapter/BotStateSnapshots.java",
    ],
    # vitals
    "vitals.lava": [
        "core/reflex/EscapeLavaRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "vitals.air_supply": [
        "core/reflex/SurfaceOnLowAirRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "vitals.swimming": [
        "adapter/entity/BotBodyEntity.java",
        "core/pathing/BasicMoves.java",
        "core/behavior/PathingBehavior.java",
    ],
    "vitals.powder_snow": [
        "core/reflex/ClimbOutOfPowderSnowRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "vitals.fire": [
        "core/reflex/ExtinguishFireRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "vitals.suffocation": [
        "core/reflex/DigOnSuffocationRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "vitals.mlg_water": [
        "core/reflex/WaterBucketOnFallRule.java",
        "adapter/entity/BotBodyEntity.java",
        "adapter/InteractBlockExecutor.java",
    ],
}


def seed_database(db_path: Optional[Path] = None) -> dict:
    """Initialize the DB and insert any seed capabilities not yet present.

    Idempotent on statuses - existing rows keep their status /
    verified_at (those live in the state overlay) - but CATALOG
    fields refresh: harness_paths and source_paths from their seed
    tables always re-apply, so a new mapping lands on the next init.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)
    inserted = 0
    skipped = 0
    for cap in SEED_CAPABILITIES:
        existing = repo.get(cap.id)
        if existing:
            skipped += 1
            hpaths = HARNESS_PATHS.get(cap.id, [])
            spaths = SOURCE_PATHS.get(cap.id, [])
            changed = False
            if existing.harness_paths != hpaths:
                existing.harness_paths = hpaths
                changed = True
            if existing.source_paths != spaths:
                existing.source_paths = spaths
                changed = True
            if changed:
                repo.upsert(existing)
            continue
        cap.verified_at = cap.verified_at or VERIFIED_AT
        cap.harness_paths = HARNESS_PATHS.get(cap.id, [])
        cap.source_paths = SOURCE_PATHS.get(cap.id, [])
        repo.upsert(cap)
        inserted += 1
    total = len(repo.list())
    return {"inserted": inserted, "skipped": skipped, "total": total}
