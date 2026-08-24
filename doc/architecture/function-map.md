---
title: Functional Convergence Map (device-layer capability envelope)
last_verified: 2026-08-24
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

- **[SHIPPED]** Walk, diagonal, jump up one block, drop up to 3 blocks,
  swim horizontally and upward. Lava execution is vocabulary-complete
  but has no in-engine scenario yet. Pathfinding reroutes on
  obstruction, recovers when shoved, and reports clean failure on
  unreachable goals.
- **[GAP]** Parkour jumps, pillar-up, door handling, sneak-walk along
  1-wide edges.
- **[DEFERRED]** Climb (ladders / vines) — trait exists, no move yet.

### 3. Survive (reflex layer)

- **[SHIPPED]** Freezes on low health (single rule, offline-gated;
  in-engine scenario pending). Reflex rules are datapack JSON; new
  survival scenarios are data, not code. A reflex preemption skips
  all mission work for that tick.
- **[SHIPPED]** After any pipeline crash the bot latches into a minimal
  state: only ascend from lethal fluid, nothing else. The latch clears
  on harness reset or death+respawn (crash counter preserved).
- **[GAP]** Fall protection, projectile dodge, automatic eating.
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

- **[SHIPPED]** goto a block, defend an area, cancel. One task at a
  time (winner-take-all arbiter); a higher-priority task defers the
  current one.
- **[GAP]** Follow / escort, harvest-and-place loops.
- **[DEFERRED]** Inventory management, crafting, tool loadout.

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
