---
title: Functional Convergence Map (device-layer capability envelope)
last_verified: 2026-08-28
covers:
  - doc/guide/workplan.md
  - doc/architecture/boundaries.md
---

# Functional Convergence Map

The device layer converges when the envelope below is stable,
test-gated end to end, and drivable by an external harness — not
when features run out. This map answers "what can this bot do" from
the bot's own perspective: see, move, survive, fight, take orders,
report back. Implementation detail lives in ADRs and boundaries.md.

Parity bar: wherever a row intersects a vanilla player verb, the
mechanics aim at player parity - exact engine math where it matters
(dig pacing, fluid physics, menu click machinery), documented
deviation everywhere else. The parity axes are ruled per issue
(0004 movement vocabulary, 0005 motion feel, 0007 interaction).

## Mission envelope

A server-side mechanical bot: given goals through boundary D, it
perceives, survives by reflex, navigates real terrain, defends
itself, and reports honestly — with zero LLM awareness inside.
v1 convergence: an external harness keeps one body alive and tasked
in ordinary overworld terrain indefinitely, without human intervention,
across reflex preemptions and task failures.

## What the bot can do

Legend: **[SHIPPED]** works in-engine with tests — **[GAP]** absent,
reopened on demand — **[DEFERRED]** outside v1.

### 1. See the world

- **[SHIPPED]** Reads blocks, whether a cell is passable / standable,
  and non-geometric traits (water, lava, ladder). New block shapes are
  covered automatically; new traits are a registry entry.
- **[SHIPPED]** Scans living entities in a radius, identifies hostile
  types, tracks the nearest threat. Bearing-blind by design (rear
  creeper is the reflex layer's reason to exist).
- **[SHIPPED]** Synchronous perception reads over boundary D:
  `/block x y z` returns one cell's id, `/blocks x y z dx dy dz`
  returns a volume (capped 4x4x4 = 64 cells by the RCON payload
  limit), `/entities [radius] [limit]` lists living entities with
  health, distance, and a self flag. All read the live WorldView on
  the server thread between ticks (issue 0013 slice 1).
- **[SHIPPED]** Container scan: `/scan [radius] [limit]` lists nearby
  block entities that implement Container (chests, crafting tables,
  etc.) with type, position, and distance. Reads only loaded chunks,
  never force-loads terrain as a side effect.
- **[SHIPPED]** Bobber perception: `WorldView.getBobbers(center, radius)`
  returns `BobberSnapshot(pos, hookedEntity)` for fishing hooks in range.
  Projectiles are invisible to the LivingEntity-shaped entity scan, so the
  bobber carries its own snapshot seam. It deliberately carries no bite
  flag — vanilla keeps nibble private (reflection stays behind the H-R1
  test door), so the consumer derives a bite from consecutive snapshots'
  vertical dip (issue 0010 section 7, P3a).
- **[GAP]** Pose-aware collision (sneaking body fits through 1.5-high
  gaps) — needed for shield-block and narrow-edge traversal.
- **[DEFERRED]** Spatial memory / waypoint persistence across sessions.

### 2. Move around

- **[SHIPPED]** Locomotion vocabulary: walk, diagonal, jump up one
  block, drop up to 3 blocks, swim horizontally and upward, sprint
  when the road ahead is clear (carrier-local rule: strong forward
  claim, dry ground, 5-block eye clip; ~1.3x speed observed). Lava
  locomotion is pinned in-engine (crossesLavaTrench); shore escape
  is a reflex row below.
- **[SHIPPED]** Pathfinding resilience: reroutes on obstruction,
  recovers when shoved, reports clean failure on unreachable goals,
  and escalates stalled budget-cut partial searches to NO_PATH
  instead of burning the mission timeout (decision 21; live
  verification queued in workplan follow-up 6).
- **[GAP]** Parkour jumps, pillar-up, door handling, sneak-walk along
  1-wide edges.
- **[DEFERRED]** Climb (ladders / vines) — trait exists, no move yet.
- **[DEFERRED]** Deliberate underwater depth control (Shift = dive
  on the reserved `yya` axis; v1 ships ascent only — issue 0004 D3).

### 3. Survive (reflex layer)

- **[SHIPPED]** Treated as a player by the world (ruling
  2026-08-25): hostiles acquire the body on sight through a
  carrier-side presence pass — their own follow range, line of
  sight, only free target slots. Unprovoked combat is possible;
  pinned by hostilesAggroOnSight.
- **[SHIPPED]** Vitals holds: freezes below the health trigger and
  surfaces at 80 of 300 air (decision 22) — ASCEND holds jump and
  vanilla fluid physics does the swimming.
- **[SHIPPED]** Hunger system: a full vanilla FoodData lives on the
  body (exhaustion / saturation / foodLevel), ticked every server tick
  with verbatim vanilla math — exhaustion drain, saturated fast regen
  (heal saturation/6 per 10 ticks), foodLevel>=18 slow regen (1 HP per
  80 ticks), and starvation damage below foodLevel 0. Movement feeds
  exhaustion from actual distance traveled (sprint burns 10x the walk
  rate per block). Natural-regeneration gamerule and difficulty gate the
  regen/starvation branches exactly as vanilla (issue 0010 Phase 4 eat
  slice, dossier 2026-08-27).
- **[SHIPPED]** Lava shore escape (decision 26): one preemption
  tick, then a rescue GotoProcess paths to the nearest safe cell —
  lava deals 4 HP per tick with no interval, the one vital that
  outranks everything (decision 24).
- **[SHIPPED]** Suffocation dig-out (decision 25): the eye block is
  a known rescue target, so the rule digs it out with authentic
  vanilla hand-dig math (~15 of 20 health points for dirt class);
  an unbreakable eye block still kills, and the preemption's
  TASK_PAUSED is the siren either way.
- **[SHIPPED]** Fire: lethal band only (fireTicks/20 >= health)
  triggers EXTINGUISH_FIRE toward water; non-lethal fire is sensed
  but ruleless — self-answering below the kill threshold
  (issue 0008 D5).
- **[SHIPPED]** Powder-snow climb (decision 27): freezing at
  freezeTicks=100 climbs out — powder snow is climbable, so the
  action is pure held jump.
- **[SHIPPED]** Idle engage (decision 23): hostiles closing to
  melee range while idle get one preemption tick, then a
  reflex-owned defend mission runs the fight through the arbiter.
  ESCAPE (decision 26) is its survival twin.
- **[SHIPPED]** Eat when hungry (issue 0010 consumption half): when
  foodLevel drops to 16 or below AND the hotbar carries food, the
  reflex selects the highest-nutrition slot (VanillaFoodCatalog over
  the item registry) and holds USE — the adapter's rising-edge gate
  consumes exactly one item per firing episode through the vanilla
  finishUsingItem chain (sound, probability effects, shrink, EAT game
  event) plus FoodData nutrition. Priority 85 sits below ENGAGE (90):
  a dead bot does not eat. Hungry with no food never fires — that is
  the acquisition half (forage / hunt / fish, HungryProcess), still
  open in issue 0010. The same eat path fires when a USE press meets
  a held food item outside a reflex (BindingActor.applyUse: food
  wins over melee).
- **[SHIPPED]** Triage ladder (decisions 24 + 26 + 27 + 0010 D6),
  eight rungs strictly ordered - ESCAPE_ON_LAVA > DIG_ON_SUFFOCATION >
  SURFACE_ON_LOW_AIR > EXTINGUISH_FIRE > FREEZE_ON_LOW_HEALTH >
  CLIMB_OUT_OF_POWDER_SNOW > ENGAGE_ON_HOSTILE_PROXIMITY >
  EAT_WHEN_HUNGRY. Values are tuning data in reflex_rules.json;
  ReflexPriorityOrderGateTest pins the inventory and the order, so
  this list stays prose-only.
- **[SHIPPED]** Rules are datapack JSON; new survival scenarios are
  data, not code. A code-registered rule type must land with its
  JSON branch and datapack row in the same change, or the first
  /reload silently drops it (ledger 24 reload-parity gate).
- **[SHIPPED]** Preemption semantics: a reflex tick skips all
  mission work — except ENGAGE and ESCAPE, which each spend
  exactly one tick preempting.
- **[SHIPPED]** After any pipeline crash the bot latches into a minimal
  state: ascend from lethal fluid or critically low air, otherwise
  nothing (decision 24 widened the air check; still one if-flag per
  vital, ADR-0005 D3 class). The latch clears
  on harness reset or death+respawn (crash counter preserved).
- **[SHIPPED]** Food acquisition (issue 0010 acquisition half):
  HungryProcess is a four-strategy state machine — ASSESS picks the
  first viable strategy, FORAGE walks to a berry bush and digs it,
  FISH equips the rod, casts, settles, and reels on a vertical-dip
  bite signal (600-tick budget, engine-unverified), HUNT engages the
  nearest passive food mob with the same Attack directive vocabulary
  DefendProcess uses, and COLLECT walks the prey's last cell for the
  drop pickup. Strategy ordering: forage > fish > hunt (0010 §4.3).
  Every tick checks inventory first — food landed means FOOD_ACQUIRED
  and the EAT reflex owns chewing. No viable strategy or burned budget
  is FOOD_STRATEGY_EXHAUSTED (structured failure to the harness).
- **[GAP]** Fall protection, projectile dodge, ranged
  idle threats (a skeleton kiting at standoff never trips the melee
  engage trigger — responding needs ranged tactics or retreat policy),
  fighting while at low health (the freeze hold currently wins that
  arbitration).
- **[DEFERRED]** Process-suppressed reflexes (a mission pauses a reflex).

### 4. Fight

- **[SHIPPED]** Melee combat: engages the nearest hostile at standoff
  range, aims, paces swings, checks line of sight (lava blocks vision).
  Targets that leave the leash radius fail cleanly; targets lost from
  scan count as "absent" (death or escape, not confirmed kill).
- **[SHIPPED]** Honest refusal: ranged hostiles (skeleton, stray,
  pillager, witch) are refused at engage time with the threat type
  reported — the bot never chases a fight it cannot win.
- **[SHIPPED]** Bow ranged combat (ledger 37, amends decision 14):
  CombatBehavior auto-selects the bow when the target sits between
  melee reach and BOW_RANGE, the hotbar carries a bow, and inventory
  carries arrows — the bow owns the SLOT channel while ranging
  (suppressing holdBestWeapon, which would otherwise switch off a bow
  every tick since bows carry no attack-damage modifier). Draw pacing
  holds Use(true) for BOW_CHARGE_TICKS then one Use(false) fires
  BowItem.releaseUsing with the accumulated charge; arrow-drop lift is
  `0.0028 * range^2`. BindingActor.applyUse routes a bow held item to
  facade.startUsingItem on the rising edge, pumps tickUseLoop every
  held tick (the facade is never player-ticked), and releases on the
  falling edge or on USE claim loss (orphaned-charge guard).
- **[GAP]** Shield block, weapon auto-selection (dig tool
  auto-selection is shipped; picking the best sword for the current
  target is not), multi-target, retreat choreography.
- **[DEFERRED]** PvP, aggression toward non-hostile entities.

### 5. Take orders

- **[SHIPPED]** Harness-submittable tasks: goto a block, dig one block
  (`/bot dig x y z [timeoutTicks]`), composite mine
  (`/bot mine blockType count [timeoutTicks]` — search, walk, dig,
  collect, repeat). One task at a time (winner-take-all arbiter); a
  higher-priority task defers the current one. Dig (issue 0013) and
  mine (issue 0014) grew the verb table per decision 28: one row per
  process kind, decided in the shipping issue. **Defend is not a
  harness verb** — the ENGAGE_ON_HOSTILE_PROXIMITY reflex creates
  internal DefendProcess instances (engage radius 8, leash 12, ranged
  types refused) when hostiles close to melee range; the harness
  observes them as TASK_* events but cannot submit one.
- **[SHIPPED]** Console verbs on `/bot`: goto / dig / mine / cancel /
  stop / status / events / reset. The original six (issue 0011) are
  the frozen core; dig and mine extend the table by decision 28.
  Every answer is one JSON object with an `ok` boolean; failures carry
  a machine-readable `reason`.
- **[SHIPPED]** Menu verbs on `/menu`: open (container at x y z),
  open-inventory, wear (equip best available armor), snapshot, close,
  deposit (slotRole item count), take (slotRole [count]), craft
  (recipeId). All drive the vanilla click machinery through the Player
  facade (issue 0007 Phase 2 + wear slice).
- **[SHIPPED]** World verbs: `/block`, `/blocks` (volume read),
  `/entities`, `/place x y z face` (one-shot block placement with
  post-state read), `/use x y z face` (one-shot vanilla right-click
  chain — block.use first, then item.useOn on PASS; returns
  `used:true/false` + target block id; P1), `/equip slot` (hotbar
  select 0..8).
- **[SHIPPED]** Inspection verbs: `/recipes list [offset] [limit]`
  paginates the full recipe catalog (RCON payload cap forces paging),
  `/recipes <itemId>` looks up recipes by result id.
- **[GAP]** Follow / escort, harvest-and-place loops.
- **[DEFERRED]** Weapon auto-selection (armor equipping and dig tool
  auto-selection are shipped; picking the best sword for the current
  target is not).

### 6. Report back (harness seam)

- **[SHIPPED]** Submit / cancel commands; polled event stream with
  cursor-based gap recovery (a slow consumer is told what it missed,
  never silently); structured failure reasons; state snapshot of
  model-relevant fields only: pos (block cell), yaw / pitch,
  dimension, itemCounts (main inventory aggregated by item id, armor
  and offhand excluded), selectedHotbarSlot (0..8), effectAmplifiers
  (active potion effects by type, remaining ticks deliberately absent),
  currentTaskSummary, healthHearts (bucketed to whole hearts 0..10 so
  regen ticks do not flood the change detector), freeSlots (empty
  main-inventory count), foodLevel (0..20). Change-detecting: a
  STATE_PUSH fires only when a field actually moves.
  Event vocabulary: TASK_* lifecycle (COMPLETED / FAILED / PAUSED /
  RESUMED / DROPPED / CANCELLED / REJECTED), STATE_PUSH, BOT_CRASHED,
  KEEPALIVE (plan-progress liveness), BLOCK_BROKEN (per dig/mine
  completion with pos + blockId + taskId), EVENT_GAP / EVENT_DROPPED.
- **[SHIPPED]** Idempotent submit: duplicate commands collapse to the
  first acceptance.
- **[GAP]** Transport beyond the RCON console bridge (MCP / HTTP /
  stdio) — the bot stays blind to the choice.
- **[DEFERRED]** Cross-restart event replay (JsonlJournal).

### 7. Manipulate the world

- **[SHIPPED]** Reads its own inventory every perception tick — a
  41-slot immutable snapshot (main + armor + offhand) keyed by
  registry id (issue 0007 Phase 1).
- **[SHIPPED]** Selects the held hotbar slot through the SLOT
  channel — live tool supply for digging, and the selected slot
  in every state snapshot.
- **[SHIPPED]** Digs with vanilla pacing math: per-tick tool speed
  and correct-tool-for-drops drive both the 30/100 divisor and
  whether drops spawn (issue 0009 + 0007 tool supplier).
- **[SHIPPED]** Drops the selected stack (Q / Ctrl-Q semantics)
  and places BlockItems against a clicked face — default state,
  air cells only, BlockItem.canPlace parity guards.
- **[SHIPPED]** Opens crafting tables and chests and drives them
  through vanilla click machinery via a Player facade; crafting
  resolves against the real RecipeManager (issue 0007 Phase 2).
- **[SHIPPED]** Core-side click-sequence planner over the
  CraftingView lens: whole-stack lifts, one-item-per-cell
  deposits, remainder returns, quick-move take — executed through
  the actor's MenuTransactions (issue 0007 Phase 2). The
  walk-to-table acceptance loop pins the full arc.
- **[SHIPPED]** Recipe-driven crafting: adapter RecipeCatalog
  translates shaped recipes off the live datapack into pure
  RecipeViews (tag-expanded, pattern-relative); planRecipe resolves
  accepted kinds against actual inventory and anchors the pattern on
  whatever surface is open. Chest flows ride planWithdraw /
  planDeposit QUICK_MOVE sequences; pullsCraftsAndBanksByRecipeId
  chains chest → table → chest end to end.
- **[SHIPPED]** Armor equipping: `/menu wear` classifies every vanilla
  ArmorItem (VanillaArmorCatalog over the frozen item registry, flat
  slots 5..8 head..feet), picks the highest-protection candidate per
  slot, and executes lift-swap-return triples through the menu
  transaction surface. An unclassifiable worn piece is never stripped;
  the planner cannot rank what it does not know.
- **[SHIPPED]** Composite mining (`/bot mine`): searches a 16-block
  radius for the target block type, walks to the nearest candidate,
  digs it with vanilla pacing, stands on the broken cell for a pickup
  window so vanilla auto-pickup collects the drop, then repeats until
  the requested count is broken or no candidates remain. Per-target
  failures (STUCK, move budget, dig budget, unloaded mid-dig) skip
  that target and return to search rather than failing the mission.
  Termination counts blocks broken, not inventory items (issue 0014 §3
  review correction: a block's drop id routinely differs from its block
  id). BLOCK_BROKEN fires once per broken block.
- **[SHIPPED]** Equipment attribute mirror: the inventory container is
  the source of truth, the vanilla equipment slots are a write-through
  mirror over it (getItemBySlot / setItemSlot map mainhand → selected
  hotbar, offhand → slot 40, armor → flat 5..8). LivingEntity.
  detectEquipmentUpdates diffs the mirror every tick and applies each
  item's attribute modifiers — a held sword raises ATTACK_DAMAGE, worn
  armor raises ARMOR, with zero extra wiring. The mob model renders
  held and worn items for free (issue 0007 Phase 4 combat/wear slices).
- **[SHIPPED]** Use-block via the vanilla right-click chain (P1):
  `/use x y z face` and the INTERACT channel's InteractBlock claim
  both dispatch through InteractBlockExecutor, which runs the vanilla
  non-sneak order — `BlockState.use(level, facade, MAIN_HAND, hit)`
  first; only on PASS does `ItemStack.useOn(UseOnContext)` run
  (BlockItem placement included). This means a right-click on a door
  opens it even while holding a block, exactly as vanilla. Direction-
  aware placement (stairs facing, snow layers, waterlog) and
  replaceable target cells ride BlockItem.place's own state machine,
  retiring the Phase 1 default-state debt. Container UI blocks
  (MenuProvider) are deliberately skipped — the menu verbs own that
  surface. Two deviations from ServerPlayerGameMode.useItemOn are
  recorded: the Forge RightClickBlock hook is not fired, and the
  carrier is a PathfinderMob acting through BotPlayerFacade rather
  than a ServerPlayer.
- **[SHIPPED]** Dig tool auto-selection (P4): before a mission's
  aim-and-dig pair drives, ToolSelector scans the hotbar for the item
  whose `destroySpeed` against the target block is strictly faster
  than the held item and submits one SLOT claim. Equal speed buys
  nothing (vanilla DiggerItem answers `speed` only inside its
  mineable tag; wrong tool and empty hand both return 1.0), so the
  held slot wins ties and equal-speed churn never flickers the
  selection. Mission path only — the suffocation reflex digs without
  this step so an emergency dig never spends a SLOT claim that could
  collide with the EAT reflex's select-and-use pair.
- **[GAP]** Shapeless recipes (no fixed grid cells to describe) and
  multi-step crafting chains (e.g. log → planks → sticks as one
  request).
- **[GAP]** Use-item verbs — buckets — both ride the facade's
  Player-typed surface. Eating food is shipped (EAT reflex + USE
  press consumes the held item via finishUsingItem + FoodData.eat;
  issue 0010 consumption half). Bows are shipped (§4 ranged combat).
  Use-block interactions are shipped (§7 vanilla chain above).
- **[DEFERRED]** Enchantment-aware dig speed, match_tool loot
  fidelity, weapon auto-selection.

## Known limits (v1 honest)

| Enemy type | What the bot does |
|---|---|
| Melee (zombie, spider) | Standoff → kill |
| Explosive (creeper) | Standoff → trades hits (accepted risk) |
| Ranged (skeleton, witch, pillager) | Refuses engagement, reports threat type |
| Any hostile (unarmed) | Times out — pre-check belongs to the driver |

Escaped-target semantics: a target that leaves the engaged tracking
scan (14 blocks) after the 10-tick grace fails as TARGET_ESCAPED —
the bot cannot distinguish death from escape (EntitySnapshot carries
no death flag), so it reports the conservative failure verdict (issue
0006), not SUCCESS. A target between leash (12) and tracking scan
(14) fails immediately as LOST_TARGET. The harness must re-scan to
confirm a kill; TASK_COMPLETED on a defend means the scan came up
empty at submission time (area clear), not a confirmed kill.

## Reflex-tick event semantics

A reflex tick may emit non-reflex task events. When a freeze lands
in the one-tick retirement lap (mission decided on tick N, freeze
fires on tick N+1 before retirement), the verdict — TASK_COMPLETED
or TASK_FAILED with attrs.reason — arrives on the reflex tick,
immediately followed by the body-halt. The harness must therefore
not assume "events during a reflex tick are reflex-only"; treat any
TASK_* event as authoritative regardless of pause state. A live park
emits TASK_PAUSED only; verdicts for parked missions arrive after
TASK_RESUMED.

## Convergence criteria (v1 done)

1. Every [SHIPPED] row has offline tests plus in-engine gametests;
   no red suite at commit time.
2. A programmatic driver (no LLM) keeps one body alive ≥ 10 minutes
   of mixed day/night overworld using only boundary-D verbs, surviving
   at least one reflex preemption and one failed-task recovery.
3. Any capability a harness might assume that is not in this map is
   answered by the GAP / DEFERRED rows, not by silence.
4. Map-code reconciliation: a shipped item must update its row here
   in the same commit (review-enforced; doc check surfaces drift).

## Non-goals

See boundaries.md "Explicitly excluded". This map adds nothing to
that list.
