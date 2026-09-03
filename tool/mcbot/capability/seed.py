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
        description="Consume sound, probability effects, stack shrink, EAT game event, then FoodData nutrition. Vanilla keeps nibble state private, so the adapter consumes exactly one item per firing episode through a rising-edge gate, not a flag poll. ConsumptionMechanics owns the physical eat + container recovery; ConsumableUse owns the USE-edge dispatch and eat lifecycle events.",
        implementation_status="shipped",
        vanilla_ref="Item.finishUsingItem; FoodData.eat (decompiled 1.20.1)",
    ),
    Capability(
        id="hunger.eat_events",
        name="Eat lifecycle events on boundary-D stream",
        category="hunger",
        description="EAT_STARTED (pre-selection, urgent: reflex decided to eat, mission about to be preempted), EAT_COMPLETED (result, non-urgent: item consumed with full before/after state), EAT_FAILED (reason: NOT_HUNGRY/NOT_EDIBLE/SLOT_EMPTY). EventFacts is the single construction point for all three kinds; ConsumableUse emits completed/failed from the BindingActor USE-edge dispatch, BotController.routeReflexDecision emits started.",
        implementation_status="shipped",
        vanilla_ref="N/A (bot-specific disclosure; vanilla player has no boundary-D event stream)",
        deviation="Bot-specific: vanilla eating is a silent 32-tick animation; the bot discloses the full lifecycle for harness observability so the harness learns WHY the mission was preempted and WHAT was eaten without polling status.",
    ),
    # --- consumable: potions, milk, splash/lingering ---
    Capability(
        id="consumable.potion",
        name="Potion drinking, milk, splash/lingering throw + DRINK lifecycle",
        category="consumable",
        description="Drink potions and milk via finishUsingItem (PotionItem/MilkBucketItem), throw splash/lingering via ThrowableItem path. DRINK_STARTED/DRINK_COMPLETED/DRINK_FAILED/POTION_THROWN events on boundary-D. consumeDrink shared helper handles shrink + container recovery (glass bottle / bucket) with containerDropped attr when inventory is full. DrinkOnLowHealthRule reflex auto-drinks healing potion below 12 HP. Failed reasons: SLOT_EMPTY / NO_POTION / NOT_EDIBLE with itemId. Instant health I heals 4 HP ((4 << amplifier) * healthFactor).",
        implementation_status="shipped",
        vanilla_ref="PotionItem.finishUsingItem; MilkBucketItem.finishUsingItem; ThrowablePotionItem; ItemUtils.createFilledResult (decompiled 1.20.1)",
        deviation="Bot consumes in one rising-edge pass (no 32-tick nibble animation); PotionItem.finishUsingItem lines 60-75 are Player-only (no shrink, no glass bottle return, no inventory add) so the adapter implements consumeDrink to mirror vanilla container recovery. Splash/lingering detected via ThrowablePotionItem instanceof check BEFORE PotionItem (ThrowablePotionItem extends PotionItem) to route to throw instead of drink.",
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
        axis="offense",
        description="Per-hit damage is the whole ranking, so an axe beats a same-tier sword. The vanilla doHurtTarget path carries enchant bonus, knockback, Fire Aspect, and shield-disable for free.",
        implementation_status="shipped",
        vanilla_ref="Mob.doHurtTarget; ItemStack.getAttributeModifiers (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.bow_slot",
        name="Bow: no attack-damage modifier, owns SLOT channel",
        category="combat",
        axis="offense",
        description="Bows carry no attack-damage modifier — any strength-comparing auto-weapon would switch off a drawn bow every tick, so the bow owns the SLOT channel while ranging.",
        implementation_status="shipped",
        vanilla_ref="BowItem; ItemStack.getAttributeModifiers (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.bow_draw",
        name="Bow draw/release: USE hold window, falling-edge fire",
        category="combat",
        axis="offense",
        description="Holding USE for the draw window (BOW_CHARGE_TICKS) then one USE release fires BowItem.releaseUsing with the accumulated charge; arrow-drop lift is 0.0028 * range^2. The facade is never player-ticked, so the adapter pumps tickUseLoop every held tick and releases on the falling edge or USE claim loss.",
        implementation_status="shipped",
        vanilla_ref="BowItem.releaseUsing; BowItem.use (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.shield",
        name="Shield blocking: evaluated on the hurt entity",
        category="combat",
        axis="defense",
        description="isDamageSourceBlocked reads the hurt entity's own blocking stack at hurt time, so the shield must be raised on the body taking the hit, not on a facade. Raise on the rising edge, release on the falling edge or USE claim loss.",
        implementation_status="shipped",
        vanilla_ref="LivingEntity.isDamageSourceBlocked; ShieldItem (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.line_of_sight",
        name="Melee line of sight: lava counts as opaque",
        category="combat",
        axis="perception",
        description="Eye-to-surface ray in which lava counts as opaque. The bot splits this into two independent checks in MeleeResolver: sightBlocked() runs a vanilla ClipContext with Block.COLLIDER / Fluid.NONE (solid terrain only — lava is transparent to this clip), and lavaBetween() is a separate 0.5-block point-sample along the segment that flags lava. Both must pass for a swing to connect.",
        implementation_status="shipped",
        vanilla_ref="MobEntity.hasLineOfSight; ClipContext (decompiled 1.20.1)",
    ),
    Capability(
        id="combat.hostile_acquisition",
        name="Hostile acquisition: their own AI",
        category="combat",
        axis="perception",
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
    # --- 9. Fishing behavior (rod micro-execution) ---
    Capability(
        id="fishing.bite_watch",
        name="Bite watch and reel: bobber dip detection + rod retrieve",
        category="fishing",
        axis="offense",
        description="FishBehavior equips the rod, casts (USE rising edge), settles the bobber (5 stable ticks after 30-tick cast grace), then watches consecutive BobberSnapshot Y values. A vertical drop >= 0.25 blocks in one tick is the bite — vanilla sets DATA_BITING=true which imparts -0.4*random(0.6,1.0) downward velocity. One reel (USE edge) lands the loot; hooked entity/item reels immediately. BITE_BUDGET_TICKS=600 no-bite timeout reels and stops. The HungryProcess per-tick food scan closes the mission.",
        implementation_status="shipped",
        vanilla_ref="FishingHook.catchingFish (timeUntilLured 100-600, timeUntilHooked 20-80, nibble 20-40); FishingHook.onSyncedDataUpdated (DATA_BITING -> deltaY -0.4); FishingHook.retrieve (BuiltInLootTables.FISHING, open water treasure, rod damage 1/3/5) (decompiled 1.20.1)",
        source_paths=[
            "core/behavior/FishBehavior.java",
            "core/process/HungryProcess.java",
            "api/process/Fish.java",
            "api/process/Overrides.java",
            "api/world/BobberSnapshot.java",
        ],
    ),
    # --- 10. Farming (till / plant / grow / harvest cycle) ---
    Capability(
        id="farming.till_soil",
        name="Hoe tilling: grass/dirt to farmland via right-click",
        category="farming",
        axis="interaction",
        description="HoeItem.useOn converts grass_block, dirt_path, dirt to farmland; coarse_dirt to dirt; rooted_dirt to dirt plus hanging_roots drop. Precondition onlyIfAirAbove: clicked face must not be DOWN and the block above must be air. Each till deals 1 tool damage and plays HOE_TILL. FarmBlock default state MOISTURE=0; water within 4 blocks or rain hydrates to 7.",
        implementation_status="gap",
        vanilla_ref="HoeItem.useOn (line 48-75); HoeItem.onlyIfAirAbove (line 92); FarmBlock (decompiled 1.20.1)",
    ),
    Capability(
        id="farming.plant_seeds",
        name="Seed planting: right-click farmland with seeds",
        category="farming",
        axis="interaction",
        description="ItemNameBlockItem.place on farmland places the crop at AGE=0. CropBlock.mayPlaceOn accepts only Blocks.FARMLAND. CropBlock.canSurvive requires rawBrightness >= 8 OR canSeeSky. Seed items: wheat_seeds, potato, carrot, beetroot_seeds, melon_seeds, pumpkin_seeds. Nether wart plants on soul_sand (separate face, not here).",
        implementation_status="gap",
        vanilla_ref="ItemNameBlockItem.place; CropBlock.mayPlaceOn (line 52); CropBlock.canSurvive (line 156) (decompiled 1.20.1)",
    ),
    Capability(
        id="farming.crop_growth",
        name="Crop growth: AGE 0-7 randomTick, growth speed, bonemeal",
        category="farming",
        axis="perception",
        description="CropBlock.randomTick advances AGE 0->7 when rawBrightness >= 9. Growth speed getGrowthSpeed: base 1.0 + fertile farmland center 3.0 / diagonal 0.75; same-crop in row divides total by 2. Grow chance = nextInt((int)(25/f)+1)==0. BoneMealItem.applyBonemeal calls growCrops adding Mth.nextInt(random,2,5) age, consumes 1. FarmBlock.MOISTURE 0-7: water within 4 blocks (isNearWater) or rain sets 7; dry + no crop above turns to dirt. Trampling (fallOn) reverts to dirt via ForgeEventFactory.onFarmlandTrample.",
        implementation_status="gap",
        vanilla_ref="CropBlock.randomTick (line 82-95); CropBlock.getGrowthSpeed (line 111-153); CropBlock.growCrops (line 97-105); BoneMealItem.applyBonemeal (line 66-89); FarmBlock.randomTick (line 78-89); FarmBlock.isNearWater (line 112-122) (decompiled 1.20.1)",
    ),
    Capability(
        id="farming.harvest_mature",
        name="Harvest: break mature crops (AGE>=7), collect drops + seeds",
        category="farming",
        axis="action",
        description="Break CropBlock at isMaxAge (AGE>=7) via the dig.pacing break sequence. Mature wheat drops 1 wheat + 0-3 seeds; potato/carrot drop 1-4 produce; beetroot drops 1 beetroot + 0-3 seeds. The face adds maturity detection (never break age<7) and seed-replant awareness (reserve seeds from drops for the next plant cycle). Reuses DigExecutor break progress; no new Intent kind.",
        implementation_status="gap",
        vanilla_ref="CropBlock.isMaxAge (line 72); CropBlock.getCloneItemStack (line 174-176); block loot tables (decompiled 1.20.1)",
    ),
    # --- 11. Water direct fishing (kill fish mobs in water) ---
    Capability(
        id="acquisition.water_fish",
        name="Water fish hunt: chase and kill AbstractFish mobs for raw fish",
        category="acquisition",
        axis="offense",
        description="Hunt fish mobs (cod, salmon, tropical_fish, pufferfish) directly in water. AbstractFish has MAX_HEALTH=3.0, WaterBoundPathNavigation, AvoidEntityGoal<Player> flee range 8.0, PanicGoal 1.25x. Cod drops raw_cod, salmon raw_salmon, tropical_fish clownfish (1 hunger), pufferfish pufferfish (poison effect). Underwater melee reuses combat.melee (no crits underwater). Fish flop on land (aiStep) — can be chased ashore. Alternative: Bucketable.bucketMobPickup with water bucket yields a fish bucket (transport, not direct food).",
        implementation_status="gap",
        vanilla_ref="AbstractFish (line 34, createAttributes MAX_HEALTH 3.0, registerGoals AvoidEntityGoal flee 8.0, WaterBoundPathNavigation); AbstractFish.aiStep flop (line 122-133); Bucketable.bucketMobPickup (line 137) (decompiled 1.20.1)",
    ),
    # --- 12. Taming (pet domestication loop) ---
    Capability(
        id="taming.chain",
        name="Tame chain: approach + tame-item use-on-entity presses until tamed",
        category="taming",
        axis="interaction",
        description="Right-click a named tameable animal with its species' tame item until the tamed flag flips. Wolf takes a bone (1/3 per press), cat raw cod/salmon (1/3), parrot any of six seed kinds (1/10); each press consumes the item and broadcasts hearts (success) or smoke (retry). The process chases with GoalNear(2), refuses on first sight with typed verdicts (NOT_TAMEABLE / ALREADY_TAMED / TARGET_ANGRY / NO_TAME_ITEM / TARGET_DEAD), and completes directly on the tamed sighting; the behavior equips via SLOT and presses INTERACT (Intent.InteractEntity) every 12 ticks (the cooldown doubles as the sit-toggle guard - the invariant is documented on PRESS_INTERVAL_TICKS); the executor resolves the id, gates reach at 3.0, and rides Player.interactOn. Angry wolves are refused (Wolf.mobInteract guards on !isAngry(), so bone presses would be silent no-ops). Horses excluded: ride-bucking temper taming, no item press (tracked in issue 0019).",
        implementation_status="gap",
        vanilla_ref="TamableAnimal.tame (bit 4 + owner UUID); Wolf.mobInteract nextInt(3); Cat TEMPT_INGREDIENT; Parrot TAME_FOOD nextInt(10); Player.interactOn (decompiled 1.20.1)",
    ),
    # --- 13. Hunting yield: land-game, herd, harvest (RE doc section 9, 2026-09-02) ---
    Capability(
        id="acquisition.hunt_game",
        name="Land-game hunt: kill prey, drop-table yield lands in inventory",
        category="acquisition",
        axis="offense",
        description="The excursion yield scenario: directed kill of land prey (the combat stack reused) followed by the prey's drop-table items reaching the carrier inventory - the assertion anchor is the YIELD, not the kill. Loot is data-driven per pool: set_count uniform + looting_enchant (grow(round(level x uniform)), uncapped) + furnace_smelt when the prey burns; death XP 1-3. All prey but rabbit/goat are temptable with breeding food (lure beats chase); plains herds are 4-strong (sheep w12, pig w10, chicken w10, cow w8).",
        implementation_status="gap",
        vanilla_ref="client-extra.jar data/minecraft/loot_tables/entities/cow.json (leather 0-2 + beef 1-3); LootingEnchantFunction.run:45-61; Animal.getExperienceReward:131 1+nextInt(3); plains.json spawners.creature (decompiled 1.20.1)",
    ),
    Capability(
        id="husbandry.shear_wool",
        name="Shear living sheep: 1-3 wool per cycle, regrows on graze",
        category="husbandry",
        axis="harvest",
        description="Zero-kill renewable harvest: use-on-entity with shears against a sheared-ready sheep (alive, not sheared, not baby) drops 1-3 wool of its current color; the sheep regrows once it eats a grass block, so one flock yields forever. Blocked on the entity-use interaction verb (mobInteract); harness path pending that verb.",
        implementation_status="gap",
        vanilla_ref="Sheep.shear:214-231 (1+nextInt(3)); Sheep.readyForShearing:236; EatBlockGoal regrow (Sheep.java:116,122) (decompiled 1.20.1)",
    ),
    Capability(
        id="husbandry.breed_loop",
        name="Breed loop: feed two adults, raise the calf, compound the herd",
        category="husbandry",
        axis="breed",
        description="Food-bank strategy: feed breeding food to two same-species adults (love 600t each) births one baby; parents cool down 5 minutes (setAge 6000); the baby grows 20 minutes naturally and each feeding accelerates 10% of the REMAINING time (~9 feedings = instant adult); 1-7 XP per newborn. Input is farmed food (the farming domain feeds this loop), output is the hunt/slaughter yield surface.",
        implementation_status="gap",
        vanilla_ref="Animal.mobInteract:141-158; Animal.spawnChildFromBreeding:218-236 (setAge 6000); AgeableMob.getSpeedUpSecondsWhenFeeding:159-161; breed XP Animal.java:248 nextInt(7)+1 (decompiled 1.20.1)",
    ),
    Capability(
        id="husbandry.passive_collect",
        name="Passive collect: milk on demand, eggs on a 5-10 minute clock",
        category="husbandry",
        axis="harvest",
        description="Kill-free periodic gains: bucket use-on-entity on a cow or goat yields MILK_BUCKET with NO cooldown (infinite-renewable); a hen drops an egg item every nextInt(6000)+6000 ticks (5-10 minutes), and a thrown egg hatches a chick at p=1/8 (1/32 for four). Blocked on the entity-use interaction verb (mobInteract); harness path pending that verb.",
        implementation_status="gap",
        vanilla_ref="Cow.mobInteract:81-89 (MILK_BUCKET, no cooldown); Goat.java:211; Chicken.java:48,95-99 eggTime; ThrownEgg.onHit:57-59 (decompiled 1.20.1)",
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
    # hunting yield: the directed-kill leg of the land-game excursion
    # rides the existing attack task; the drop pickup is vanilla
    "acquisition.hunt_game": ["/entities/<id>/attack"],
    # interaction: held item against the POV ray
    "interaction.right_click_order": ["write /player/held/use"],
    "interaction.blockitem_place": ["write /player/held/use"],
    "interaction.bucket_rod": ["write /player/held/use"],
    "interaction.use_item_deviations": ["write /player/held/use"],
    # hunger: eating is held-item use; fooddata/exhaustion are internal
    "hunger.eat_chain": ["write /player/held/use"],
    # consumable: potion drinking and throw are held-item use
    "consumable.potion": ["write /player/held/use"],
    # perception: fishing rod use exercises the projectile/bobber sense
    "perception.projectiles": ["write /player/held/use"],
    # inventory: bag/free reads + station menu crafting
    "inventory.shape": ["cat /player/bag", "cat /player/inventory/free"],
    "inventory.menu_clicks": ["/stations", "/recipes/<slug>"],
    # fishing: rod cast/reel USE edges
    "fishing.bite_watch": ["write /player/held/use"],
    # farming: hoe till and seed plant are held-item use; harvest reuses mine
    "farming.till_soil": ["write /player/held/use"],
    "farming.plant_seeds": ["write /player/held/use"],
    "farming.harvest_mature": ["/tasks/mine"],
    # water fish hunt reuses the attack task
    "acquisition.water_fish": ["/entities/<id>/attack"],
    # taming: the directed tame task
    "taming.chain": ["/entities/<id>/tame"],
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
        "core/behavior/CombatBehavior.java",
        "core/combat/RangedLoadouts.java",
        "adapter/BotPlayerFacade.java",
        "adapter/entity/BotBodyEntity.java",
        "core/tick/BotController.java",
    ],
    "combat.bow_slot": [
        "core/behavior/CombatBehavior.java",
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
        "core/behavior/CombatBehavior.java",
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
        "adapter/entity/ConsumptionMechanics.java",
        "adapter/ConsumableUse.java",
        "adapter/VanillaFoodCatalog.java",
    ],
    "hunger.eat_events": [
        "adapter/BindingActor.java",
        "adapter/ConsumableUse.java",
        "api/event/EventFacts.java",
        "core/tick/BotController.java",
        "api/event/EventKind.java",
        "core/tick/ReflexClaimInjector.java",
    ],
    # consumable
    "consumable.potion": [
        "adapter/entity/BotBodyEntity.java",
        "adapter/entity/ConsumptionMechanics.java",
        "adapter/BindingActor.java",
        "adapter/ConsumableUse.java",
        "api/event/EventFacts.java",
        "api/event/EventKind.java",
        "core/reflex/DrinkOnLowHealthRule.java",
        "core/tick/ReflexClaimInjector.java",
        "core/tick/BotController.java",
        "adapter/BotAssembly.java",
        "adapter/sensing/LevelThreatSensor.java",
        "api/reflex/ReflexAction.java",
        "api/reflex/ThreatBlackboard.java",
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
    # fishing
    "fishing.bite_watch": [
        "core/behavior/FishBehavior.java",
        "core/process/HungryProcess.java",
        "api/process/Fish.java",
        "api/process/Overrides.java",
        "api/world/BobberSnapshot.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    # farming (adapter-side implementation pending; paths declared for staleness tracking)
    "farming.till_soil": [
        "adapter/InteractBlockExecutor.java",
        "adapter/BotPlayerFacade.java",
    ],
    "farming.plant_seeds": [
        "adapter/InteractBlockExecutor.java",
        "adapter/BotPlayerFacade.java",
    ],
    "farming.crop_growth": [
        "core/world/SnapshotWorldView.java",
        "adapter/sensing/LevelThreatSensor.java",
    ],
    "farming.harvest_mature": [
        "adapter/DigExecutor.java",
        "core/actor/DigPacing.java",
        "core/process/DigProcess.java",
    ],
    # water fish hunt
    "acquisition.water_fish": [
        "core/behavior/CombatBehavior.java",
        "adapter/MeleeResolver.java",
        "core/process/HungryProcess.java",
    ],
    # taming: item press chain to the vanilla tame flip
    "taming.chain": [
        "core/tame/TameFoodCatalog.java",
        "core/behavior/TameBehavior.java",
        "core/process/TameProcess.java",
        "core/command/TameCommandHandler.java",
        "api/process/Tame.java",
        "api/actor/Intent.java",
        "adapter/InteractEntityExecutor.java",
    ],
    # land-game hunt: the directed-kill stack plus the world scan the
    # target id resolves against (yield pickup is vanilla, no file)
    "acquisition.hunt_game": [
        "core/process/AttackProcess.java",
        "core/behavior/CombatBehavior.java",
        "adapter/MeleeResolver.java",
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
            if existing.axis != cap.axis:
                existing.axis = cap.axis
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
