---
title: Player Behavior RE - reverse-engineered vanilla mechanics truths
last_verified: 2026-08-29
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
- **Movement exhaustion** feeds from actual distance traveled; sprint
  burns 10x the walk rate per block.
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
- **Powder snow is climbable** (escape is pure held jump); the freeze
  kill sits at freezeTicks = 100 (decision 27).
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
  counts as opaque (mirrored by MeleeResolver.sightBlocked).
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
