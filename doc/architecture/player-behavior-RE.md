---
title: Player Behavior RE - reverse-engineered vanilla mechanics truths
last_verified: 2026-09-02
covers: []
related:
  - doc/architecture/boundaries.md
  - doc/architecture/harness-interaction.md
  - doc/guide/workplan.md
---

# Player Behavior RE (reverse-engineering record)

The dedicated collection of reverse-engineered truths about vanilla
player behavior - the parity target the device layer mirrors. Wherever
a bot mechanic intersects a vanilla player verb, the mirror aims at
exact engine math where it matters (dig pacing, fluid physics, menu
click machinery) and documented deviation everywhere else; the parity
axes are ruled per issue (0004 movement vocabulary, 0005 motion feel,
0007 interaction).

Provenance: this file converged from the retired function-map
capability envelope (2026-08-29). The SHIPPED/GAP/DEFERRED capability
status moved to [workplan](../guide/workplan.md) and the owning
issues, the harness-facing verdict semantics moved to
[harness-interaction.md](harness-interaction.md), and this file keeps
only what the name now says: what vanilla actually does, read from
the decompiled tree (`D:/mc-decompiled/forge-1.20.1-47.4.10/`) or
observed live, with the bot-side mirror and every deliberate
deviation cited.

## Record discipline

- A record states: the vanilla mechanic (exact math, constants,
  semantics), its source, its verification (offline gate, in-engine
  gametest, or live run), and the mirroring code plus deviation
  citation.
- A shipped mechanic records its vanilla truth here in the same
  commit that lands the mirror (review-enforced; doc check surfaces
  drift).
- English-only; registry keys and game-content strings verbatim.

## 1. Hunger, eating, regeneration

- **FoodData is verbatim vanilla** (issue 0010 Phase 4 dossier,
  2026-08-27): exhaustion / saturation / foodLevel ticked every server
  tick with vanilla math - saturated fast regen heals saturation/6 per
  10 ticks, foodLevel >= 18 slow regen (1 HP per 80 ticks), starvation
  damage below foodLevel 0. The natural-regeneration gamerule and
  difficulty gate the regen/starvation branches exactly as vanilla.
- **Movement exhaustion** feeds from actual distance traveled. Vanilla
  rates (Player.checkMovementStatistics, decompiled 1.20.1): sprint
  0.1 exhaustion per block, walk 0 per block (walking on ground is
  free), swim / underwater wade 0.01 per block. Sprint is therefore
  10x the swim rate, not 10x walk — walk has no rate to multiply.
  Deviation (BotBodyEntity.customServerAiStep): the mob carrier
  accumulates no exhaustion naturally, so it is fed manually from
  horizontal distance traveled. Sprint matches vanilla at 0.1/block;
  walk charges 0.01/block (the vanilla swim rate, not vanilla's zero
  walk rate) — a non-zero baseline so a purely-walking carrier still
  drains hunger over time. This is not vanilla parity; it is recorded
  here because the original comment called it "vanilla-shaped."
- **The eat chain is one finishUsingItem pass**: consume sound,
  probability effects, stack shrink, the EAT game event, then
  FoodData nutrition. Vanilla keeps nibble state private, so the
  adapter consumes exactly one item per firing episode through a
  rising-edge gate, not a flag poll (issue 0010 consumption half).

## 2. Vitals: fluids, air, lava, fire, freeze, falling

- **Lava deals 4 HP per tick with no damage interval** - the one vital
  that outranks everything (decisions 24 / 26).
- **Air supply tops at 300**; the bot surfaces at 80 (decision 22).
- **Swimming is vanilla fluid physics**: ASCEND holds jump and the
  physics do the ascent (issue 0004). Deliberate dive/ascend on the
  reserved yya axis is issue 0004 D3; v1 ships ascent only.
- **Powder snow is climbable** (escape is pure held jump). Vanilla
  full-freeze is at freezeTicks = 140 (Entity.getTicksRequiredToFreeze),
  where the "freezing" phase deals 1.0 damage per 40 ticks
  (LivingEntity.baseTick). The bot's climb reflex fires at
  TRIGGER_FREEZE = 100 (ClimbOutOfPowderSnowRule), leaving 40 ticks
  of margin before damage starts — 100 is the bot's trigger threshold,
  not the vanilla kill threshold (decision 27).
- **Fire is lethal-band only**: fireTicks/20 >= health is the kill
  band; below it no rule fires - vanilla resolves it (issue 0008 D5).
- **Suffocation digs the eye block** with authentic vanilla hand-dig
  math (~15 of 20 health points for the dirt class); an unbreakable
  eye block still kills (decision 25).
- **A water block cancels fall damage** - the MLG mirror aims at the
  ground cell, selects the bucket, and pulses use on the descent
  (capability wave 2026-08-28; engine pair waterBucketBreaksAFall
  staged).

## 3. Dig pacing and tools

- **Per-tick tool speed and correct-tool-for-drops drive both the
  30/100 break-progress divisor and whether drops spawn** (issue 0009
  + 0007 tool supplier).
- **DiggerItem answers its speed only inside its mineable tag**; wrong
  tool and empty hand both return 1.0, so equal speed buys nothing,
  the held slot wins ties, and selection churn never flickers (P4
  tool select).
- Enchantment-aware dig speed and match_tool loot fidelity are
  recorded gaps (workplan gap inventory).

## 4. Combat

- **Mob melee has no attack-speed scaling or crits** - per-hit damage
  is the whole ranking, so an axe beats a same-tier sword (Stage 3
  loadout dossier, 2026-08-27). The vanilla doHurtTarget path carries
  enchant bonus, knockback, Fire Aspect, and shield-disable for free.
- **Bows carry no attack-damage modifier** - any strength-comparing
  auto-weapon would switch off a drawn bow every tick, so the bow
  owns the SLOT channel while ranging (ledger 37, amending
  decision 14).
- **Bow draw/release**: holding USE for the draw window
  (BOW_CHARGE_TICKS) then one USE release fires BowItem.releaseUsing
  with the accumulated charge; arrow-drop lift is `0.0028 * range^2`.
  The facade is never player-ticked, so the adapter pumps tickUseLoop
  every held tick and releases on the falling edge or USE claim loss
  (orphaned-charge guard; ledger 37).
- **Shield blocking is evaluated on the hurt entity**:
  isDamageSourceBlocked reads the hurt entity's own blocking stack at
  hurt time, so the shield must be raised on the body taking the hit,
  not on a facade (issue 0003, live-verified). Raise on the rising
  edge, release on the falling edge or USE claim loss (orphaned-guard
  parity with the bow).
- **Melee line of sight** is an eye-to-surface ray in which lava
  counts as opaque. The bot splits this into two independent checks
  in MeleeResolver: `sightBlocked()` runs a vanilla ClipContext with
  Block.COLLIDER / Fluid.NONE (solid terrain only — lava is
  transparent to this clip), and `lavaBetween()` is a separate
  0.5-block point-sample along the segment that flags lava. Both
  must pass (neither returns true) for a swing to connect; calling
  the lava opacity "mirrored by sightBlocked" alone is imprecise.
- **Hostile acquisition is their own AI**: follow range, line of
  sight, and free target slots. A carrier-side presence pass is
  enough for hostiles to acquire the body on sight (ruling
  2026-08-25, pinned by hostilesAggroOnSight).

## 5. Perception seams (engine blind spots)

- **Projectiles are invisible to LivingEntity-shaped scans** - the
  fishing bobber carries its own snapshot seam
  (WorldView.getBobbers). Vanilla keeps the nibble private, so the
  bite is derived from consecutive snapshots' vertical dip (issue
  0010 section 7, P3a).
- **Scans carry no death flag** - a vanished target cannot be
  distinguished from a dead one; verdicts stay conservative
  (issue 0006; the harness-facing verdict semantics live in
  harness-interaction.md).
- **The all-sleepers table cannot see a PathfinderMob** - sleep is
  verify-and-skip (real BedBlock + in reach + nighttime) with a
  device-side clock advance; the engine never registers the carrier
  as a sleeper.

## 6. Inventory, equipment, menu machinery

- **The carrier inventory shape is 41 slots**: 36 main + 4 armor + 1
  offhand, snapshotted immutable every perception tick, keyed by
  registry id (issue 0007 Phase 1).
- **Equipment slots are a write-through mirror over the inventory
  container**: mainhand maps to the selected hotbar slot, offhand to
  slot 40, armor to flat 5..8 head..feet. LivingEntity.
  detectEquipmentUpdates diffs the mirror every tick and applies each
  item's attribute modifiers - a held sword raises ATTACK_DAMAGE,
  worn armor raises ARMOR - with zero extra wiring, and the mob model
  renders held and worn items for free (issue 0007 Phase 4).
- **Menu clicks ride the vanilla click machinery** through the Player
  facade: whole-stack lifts, one-item-per-cell deposits, remainder
  returns, quick-move takes. Crafting resolves against the live
  RecipeManager; shaped recipes translate tag-expanded and
  pattern-relative; shapeless recipes are an ingredient list with no
  fixed grid cells (emitGridFill accepts any gridPos) (issue 0007
  Phase 2 + RecipeCatalog).
- **Armor classification** reads vanilla ArmorItem flat slots 5..8
  (VanillaArmorCatalog) and picks the highest-protection candidate
  per slot; an unclassifiable worn piece is never stripped.

## 7. Place / use / interact chains

- **Right-click order**: BlockState.use first; only on PASS does
  ItemStack.useOn run (BlockItem placement included) - a right-click
  on a door opens it even while holding a block, exactly as vanilla
  (issue 0013 P1).
- **BlockItem.place owns direction-aware state**: stairs facing, snow
  layers, waterlog, replaceable target cells (retired the Phase 1
  default-state debt).
- **Two recorded deviations from ServerPlayerGameMode.useItemOn**: the
  Forge RightClickBlock hook is not fired, and the carrier is a
  PathfinderMob acting through BotPlayerFacade rather than a
  ServerPlayer.
- **Bucket fill/empty and rod cast/reel ride vanilla Item.use**
  against a POV raycast from the synced facade pose; ROT aim is the
  caller's precondition.
- **Drop semantics mirror Q / Ctrl-Q**; placement honors
  BlockItem.canPlace parity guards and air-cells-only default state.
- **Container UI blocks (MenuProvider) are deliberately skipped** by
  the use-block chain - the menu verbs own that surface.

## 8. Motion vocabulary

- **Sneak is input state, not an action**: vanilla has no discrete
  sneak action to fire - it is a pose plus movement modifier held for
  as long as the input bit is set. The bot mirrors this with a
  latched modifier (/bot sneak on|off) and the per-tick
  Intent.Move.sneak channel; there is no SNEAK channel to claim
  (issue 0003). The pathing layer does not yet plan sneaked edge
  walks (workplan gap inventory).
- **Sprint is a clear-road gate**: vanilla sprint needs collision-free
  room ahead; the carrier-local rule mirrors it (strong forward
  claim, dry ground, 5-block eye clip; ~1.3x speed observed).
- **Ladders and vines are climbable traits** with no deliberate move
  yet (workplan gap inventory).

## 9. Taming and companion animals (survey + mirror, 2026-09-02)

Pet-domestication truths read from the decompiled tree. The tame
press chain is mirrored live by the `taming.chain` face; the
companion behaviors and mounts behind it are survey-stage records
feeding future face proposals.

### 10.1 The tame press (mirrored)

- TamableAnimal: tame is synched byte bit 4; the owner is a synched
  `Optional<UUID>` (`DATA_OWNERUUID_ID`); sitting is a server-side
  `orderedToSit` flag (NBT `Sitting`) plus pose bit 1; the tame
  result is broadcast as entity event byte 7 (hearts) / 6 (smoke).
- Per-species press: Wolf takes a bone only while not angry,
  consumes it, tames on `nextInt(3) == 0` (Wolf.mobInteract:382)
  and auto-sits on success; Cat takes raw cod or salmon at the same
  1/3 (Cat.mobInteract:405); Parrot takes any of six seed kinds at
  `nextInt(10) == 0` (Parrot.mobInteract:273) and a cookie is
  instant death (POISON 900 ticks + MAX-damage hurt).
- Mirror (landed): TameProcess hands the id and owns the typed
  verdicts; TameFoodCatalog owns the species-to-items map; the
  behavior equips via SLOT and presses via INTERACT
  (`Intent.InteractEntity`) every 12 ticks; InteractEntityExecutor
  resolves the UUID against the server level and rides
  `Player.interactOn`. Deliberate deviation: the device presses by
  id within reach 3.0, not by crosshair.

### 10.2 Companion behaviors (survey, no mirror)

- Sit/stand toggle: an owner right-click flips `setOrderedToSit`
  (Wolf:367, Cat:384, Parrot:293) - an empty-hand press on an owned
  pet toggles sit, which is why the tame press cadence retires the
  directive between presses. A damaged wolf stands up.
- Follow + teleport: FollowOwnerGoal `TELEPORT_WHEN_DISTANCE_IS =
  12` (distanceToSqr >= 144); 10 attempts at the owner's block plus
  offsets x/z in [-3,3], y in [-1,1]; sitting, leashed, or
  passenger pets do not follow.
- Lead: `Mob.canBeLeashed` refuses Enemies (Wolf also refuses angry
  wolves); beyond 10 blocks the lead drops (tickLeash), 6..10
  applies elastic tug; `LeadItem.useOn` transfers leads held by the
  player within a +/-7 block box to a fence knot.
- Horse armor: `HorseArmorItem` equips into the horse inventory
  (slot 1) via AbstractHorse.mobInteract:702; donkey, mule, and
  undead horses report `canWearArmor() == false`; llamas accept
  wool carpets only and are never saddleable.

### 10.3 Mounts (survey, no mirror)

- Horse taming is the ride-bucking temper loop, not an item press:
  while ridden untamed, RunAroundLikeCrazyGoal rolls
  temper/maxTemper roughly every 50 ticks; failure ejects the
  rider, adds +5 temper, and rears. maxTemper 100 (llama 30);
  feeding wheat/sugar/apple +3, golden carrot +5, golden apple +10
  temper (AbstractHorse.handleEating:469).
- Control gates on the saddle: `AbstractHorse.getControllingPassenger`
  hands steering to the rider only `isSaddled()`; pig needs saddle
  plus carrot-on-a-stick, strider saddle plus warped fungus on a
  stick. Rider input arrives as `ServerboundPlayerInputPacket(xxa,
  zza, jumping, shift)` and the VEHICLE runs `travel` - riding a
  bot would mean driving another entity, not walking one. Jump is
  the charge model (`START_RIDING_JUMP`, `floor(scale * 100)`);
  dismount is shift with a 60-tick remount cooldown.
- NOT in 1.20.1 (common assumptions): wolf armor (1.20.5+), the
  `Leashable` interface (1.21+), saddle-less horse or pig control,
  cat armor.

## 10. Hunting yield: kill, herd, harvest (survey, 2026-09-02)

Survey-stage truths for the animal-yield gameplay; read from the
decompiled sources and the vanilla data pack extracted from
`forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar`
(loot tables and biome worldgen are data-driven JSON, absent from the
decompile tree itself). No bot mirror exists yet - these records feed
the hunting-yield face proposal (pending ruling). Face identity here
is scenario/yield, per the convergence doctrine: combat owns the
fight, the hunting faces own the gain.

### 9.1 Direct-kill loot (data-driven per entity)

- Per-pool shape is always `set_count` uniform + optional
  `furnace_smelt` under `entity_properties flags.is_on_fire` +
  `looting_enchant` uniform 0-1 (e.g. `cow.json`: leather 0-2,
  beef 1-3; `pig.json` porkchop 1-3; `sheep.json` mutton 1-2 - wool
  is NOT death loot; `chicken.json` feather 0-2 + chicken 1;
  `rabbit.json` hide 0-1 + rabbit 1).
- **Burning prey cooks the drop**: the `furnace_smelt` function
  (SmeltItemFunction) swaps the raw item for its smelted form when the
  dying entity is on fire - Fire Aspect or any fire death yields
  cooked meat directly, no furnace leg (cow.json/pig.json/chicken.json
  pools; SmeltItemFunction.java).
- **Looting math** (LootingEnchantFunction.run:45-61): adds
  `Math.round(looting_level * uniform(min,max))` per pool - for the
  animals' uniform 0-1 providers that is one extra item per level in
  expectation (x0.5) per pool, uncapped (no `limit` set on animal
  tables). Works only with a player (KILLER_ENTITY) kill.
- **Rabbit's foot is a rare kill drop**: pool conditioned on
  `killed_by_player` + `random_chance_with_looting` chance 0.1,
  multiplier 0.03 (10% + 3% per looting level; rabbit.json pool 2).
- **Zero-death-loot animals** - their yield is entirely on the living
  side: goat (milk), bee (hive), fox, frog; turtle dies for seagrass
  0-2 + bowl only; panda drops bamboo 1; squid ink_sac 1-3 /
  glow_squid 1-3; cod/salmon/tropical_fish/pufferfish drop 1 fish +
  bone_meal 1; hoglin is the top porkchop prey (2-4 + leather 0-1).
- **Death XP**: `1 + random.nextInt(3)` = 1-3 per animal
  (Animal.getExperienceReward:131).

### 9.2 Breeding algorithm (Animal.java, AgeableMob.java)

- Feed an adult its breeding food while `age == 0` and not in love ->
  `inLove = 600` (30 s; Animal.mobInteract:141-158, setInLove:175).
- Two animals of the SAME class both in love -> one baby;
  `spawnChildFromBreeding` (Animal.java:218-236) then both parents
  `setAge(6000)` = 5-minute breed cooldown + reset love.
- **Breeding XP**: `nextInt(7) + 1` = 1-7 per newborn, gated by
  `RULE_DOMOBLOOT` (Animal.java:248).
- **Natural growth**: babies start at `age = -24000` and tick +1/tick
  = exactly 20 minutes to adult (AgeableMob.BABY_START_AGE, aiStep).
- **Feeding a baby accelerates growth by 10% of the REMAINING time
  per food item**: `getSpeedUpSecondsWhenFeeding(-age) = age/20 * 0.1`
  (AgeableMob.java:159-161, applied in Animal.mobInteract baby
  branch) - a fixed 20-minute growth is reachable with ~9 feedings
  (0.9^k series), cheaper when wheat is farmed.
- **Breeding foods** (isFood/TemptGoal): cow/sheep/goat wheat;
  pig carrot/potato/beetroot (Pig.java:54); chicken any of 6 seed
  types (Chicken.java:39-40); rabbit carrot/golden carrot/dandelion
  (Rabbit.java:318-319); turtle seagrass (Turtle.java:283). Turtle
  breeding additionally refuses love while `hasEgg()` (Turtle.java:258)
  and lays on its home beach instead of spawning a live baby.

### 9.3 Living-animal renewable yields (no kill required)

- **Wool**: shear yields `1 + nextInt(3)` = 1-3 of the sheep's color
  (Sheep.shear:214-231); `readyForShearing` = alive && !sheared &&
  !baby (:236); regrow when the sheep eats a grass block
  (EatBlockGoal, Sheep.java:116/122). Sheep keep producing forever on
  grazed grass.
- **Eggs**: `eggTime = nextInt(6000) + 6000` ticks = every 5-10
  minutes a hen drops an egg item (Chicken.java:48, 95-99). A thrown
  egg hatches a chick with p = 1/8, or 4 chicks with p = 1/32
  (ThrownEgg.onHit:57-59) - eggs are both yield and slow breeder.
- **Milk**: cow and goat convert an empty bucket to
  `MILK_BUCKET` on right-click with NO cooldown (Cow.mobInteract:81-89,
  Goat.java:211) - infinite-renewable, combat-free food-adjacent gain.
- **Mooshroom shearing** drops mushrooms and converts the mooshroom
  into a plain cow (MushroomCow.shear:172-176) - a one-shot wool-like
  harvest that downgrades the animal.
- **Turtle scute**: the baby -> adult age boundary drops 1 scute
  (Turtle.java:311) - the only "grow the animal for its shed" yield.

### 9.4 Prey difficulty and supply

- Attributes (createAttributes) x panic goal multipliers (addGoal):
  cow 10 HP / 0.20 speed, panic x2.0 (Cow.java:42,52); pig 10 HP /
  0.25, panic x1.25 (Pig.java:75,64); sheep 8 HP / 0.23, panic x1.25
  (Sheep.java:144,118); chicken 4 HP / 0.25, panic x1.4
  (Chicken.java:74,59); rabbit 3 HP / 0.30, panic x2.2 + explicit
  AvoidEntityGoal (Rabbit.java:267,97); turtle 30 HP / 0.25 but slow
  on land; goat 10 HP / 0.20 with ATTACK_DAMAGE 2 (rams back);
  hoglin 40 HP / 0.3 fighting speed / ATTACK_DAMAGE 6, brain-driven
  avoidance AI (Hoglin.java:53-58). Consequence: open-field chasing
  loses to panicking prey; every land prey except rabbit/goat is
  TEMPTABLE toward the bot with its breeding food (TemptGoal 1.1-1.25
  multiplier) - luring beats chasing.
- Supply: plains creature band sheep w12, pig w10, chicken w10,
  cow w8 (4-4 packs each), horse w5, donkey w1
  (worldgen/biome/plains.json spawners.creature) - one herd encounter
  is four animals of yield; animal spawn rule requires
  ANIMALS_SPAWNABLE_ON ground below and light > 8 (Animal.java:109-118).

### 9.5 Yield strategy derivations for the bot

- Weapon route: looting sword maxima every meat/hide pool in
  expectation; a fire route (burning kill) converts all meat to its
  cooked form on the spot - the two enchantments are the entire
  "yield algorithm" space on the kill side, no per-animal code.
- Passive-first economics: milk (no cooldown) > eggs (5-10 min clock)
  > wool (1-3 per graze cycle) are kill-free and repeatable; kill
  yield (meat/leather) is the only path to cooked-protein bulk.
- Breeding loop: two adults + breeding food compound into infinite
  meat/leather; input cost is farmed wheat/carrots/seeds (the farming
  domain feeds the husbandry loop), output is 1-7 XP per newborn on
  top of the kill yield.
- Device-layer gap for all living-side yields: right-click ON AN
  ENTITY with an item (mobInteract) has no bot verb today - the
  interaction faces cover block use only. Entity interaction (milk,
  shear, feed) is a prerequisite verb for the husbandry faces.
