---
title: Functional Convergence Map (device-layer capability envelope)
last_verified: 2026-08-26
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
- **[SHIPPED]** Triage ladder, frozen at seven rungs: LAVA 130 >
  SUFFOCATION 115 > SURFACE 110 > FIRE_ESCAPE 105 > FREEZE 100 >
  POWDER_SNOW_CLIMB 95 > ENGAGE 90 (decisions 24 + 26 + 27).
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
- **[GAP]** Fall protection, projectile dodge, automatic eating
  (designed in issue 0010), ranged
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
- **[GAP]** Shield block, loadout selection, multi-target, retreat
  choreography, bow / ranged combat.
- **[DEFERRED]** PvP, aggression toward non-hostile entities.

### 5. Take orders

- **[SHIPPED]** Tasks: goto a block, defend an area. One task at a
  time (winner-take-all arbiter); a higher-priority task defers the
  current one.
- **[SHIPPED]** Console verbs goto / cancel / stop / status /
  events / reset, frozen as the de-facto harness wire contract
  (issue 0011).
- **[GAP]** Follow / escort, harvest-and-place loops.
- **[DEFERRED]** Tool loadout (armor / equipment semantics).

### 6. Report back (harness seam)

- **[SHIPPED]** Submit / cancel commands; polled event stream with
  cursor-based gap recovery (a slow consumer is told what it missed,
  never silently); structured failure reasons; state snapshot of
  model-relevant fields only (pose, health, selected slot, active task).
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
- **[GAP]** Core-side click-sequence planner (materials placed by
  clicks, not container writes), chest in-engine scenario,
  CraftingView structured grid snapshot.
- **[GAP]** Use-item verbs — eating food (issue 0010's consumption
  half), buckets, bows — and use-block interactions (buttons,
  doors, levers); both ride the facade's Player-typed surface.
- **[DEFERRED]** Armor semantics on the carrier, enchantment-aware
  dig speed, match_tool loot fidelity.

## Known limits (v1 honest)

| Enemy type | What the bot does |
|---|---|
| Melee (zombie, spider) | Standoff → kill |
| Explosive (creeper) | Standoff → trades hits (accepted risk) |
| Ranged (skeleton, witch, pillager) | Refuses engagement, reports threat type |
| Any hostile (unarmed) | Times out — pre-check belongs to the driver |

Escaped-target semantics: a target that leaves scan radius after
grace counts as SUCCESS ("absent from scan"), not confirmed kill.
A target between leash (12) and scan (14) counts as LOST_TARGET.
The harness must not equate SUCCESS with a kill.

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
