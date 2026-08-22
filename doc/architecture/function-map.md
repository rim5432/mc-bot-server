---
title: Functional Convergence Map (device-layer capability envelope)
last_verified: 2026-08-22
covers:
  - doc/guide/workplan.md
  - doc/architecture/boundaries.md
---

# Functional Convergence Map

The device layer converges when the ENVELOPE below is stable,
test-gated end to end, and drivable by an external harness - not
when features run out. This map is the single place that answers
"where is all of this going"; code and workplan entries must be
reconcilable against it.

## Mission envelope (what this thing IS)

A server-side mechanical bot: given goals through boundary D, it
perceives, survives by reflex, navigates real terrain, defends
itself, and reports honestly - with zero LLM awareness inside.
Convergence for v1: an external harness can keep one body alive and
tasked in ordinary overworld terrain indefinitely, without human
intervention, across reflex preemptions and task failures.

## Capability tree and status

Legend: [SHIPPED] test-gated in-engine - [SKELETON] mechanism works,
depth pending - [GAP] vocabulary absent, reopen-triggered -
[DEFERRED] outside v1, see boundaries reopen table.

### Perception (boundary A read half)
- [SHIPPED] Blocks + collision shapes + block traits, cell-granular;
  snapshot views for off-thread planning (decision 17b)
- [SHIPPED] Entity scans (nearest-hostile, bearing-blind radius)
- [DEFERRED] Spatial memory / waypoint persistence (first
  cross-session requirement reopens)

### Locomotion (movement five-tuple family)
- [SHIPPED] Walk / diagonal / climb-up / drop<=3 over A* with replan
  ladder (exhaustion / drift / progress fuse) and wall-clock-bounded
  async worker on immutable snapshots
- [GAP] Parkour, pillar-up, swimming, door handling - each lands as a
  Movement + traits data; mechanisms written once (decision 8/10)
- [DEFERRED] ConstraintGuardian generalization (second guardian need)

### Survival (reflex bypass, boundary C)
- [SHIPPED] Sensor -> blackboard -> rule table -> preemption/resume
  chain; datapack-driven rule table (JSON hard-errors on typos)
- [SKELETON] One rule (freeze-on-low-health); scenario packs are
  rule-table DATA per decision 10 - growth is content, not code
- [DEFERRED] pausedReflexes (process-suppresses-reflex); freeze
  outranking combat is correct v1 behavior

### Combat (boundary B planner/executor split)
- [SHIPPED skeleton] DefendProcess engages nearest hostile at
  standoff range; CombatBehavior aims and paces swings; USE channel
  resolves reach+cone melee
- [GAP] Loadout selection, ranged, multi-target, retreat-choreography
  - micro-algorithms are implementation freedom INSIDE boundary B;
  none may add a fifth Actor channel (decision 14 frozen)
- [EXCLUDED] PvP griefing behaviors; non-hostile entity aggression

### Task tier (orchestration)
- [SHIPPED] goto (GoalBlock/Near), defend, cancel, terminal events
  with structured attrs; winner-take-all arbiter with DEFER semantics
- [GAP] Follow/escort, harvest-place loops - each is ONE new Process
  touching zero Behaviors (boundary B acceptance test)
- [DEFERRED] Inventory / crafting skills (after locomotion slice
  acceptance); tools loadout

### Harness seam (boundary D)
- [SHIPPED] submit/cancel verbs, cursor-drained event stream with
  gap recovery + structured failure reasons, state snapshot channel
- [GAP] Transport choice (MCP vs HTTP vs stdio) - bot stays blind;
  the seam already froze the six invariants
- [DEFERRED] JsonlJournal replay across restarts (first cross-session
  requirement); idempotency-key persistence

## Convergence criteria (definition of v1 done)

1. Envelope stability: every [SHIPPED] row above has offline layer-1
   tests plus in-engine gametests; no red suite at commit time.
2. Harness handoff: a programmatic driver (no LLM) keeps one body
   alive >= 10 minutes of mixed day/night overworld with only
   boundary-D verbs - surviving at least one reflex preemption and
   one failed-task recovery without human input.
3. Vocabulary honesty: any capability a harness might assume that is
   NOT in this map is answered by the deferred/excluded tables, not
   by silence.
4. Map-code reconciliation: workplan stages may reorder freely, but
   a shipped item MUST update its row here in the same commit
   (enforced by doc check discipline, review-enforced today).

## Non-goals

See boundaries.md "Explicitly excluded". This map adds nothing to
that list; when a candidate feature fits neither tree nor exclusions,
the decision goes through the ledger, not into this file silently.
