---
title: ADR-0002 Capability model and TaskArbiter
last_verified: 2026-08-21
covers: []
---

# ADR-0002 Capability model and TaskArbiter

- Status: accepted (2026-08)
- Depends on: architecture principles in [Architecture Overview](../architecture/overview.md)
- Background research: [Baritone notes](../reference/baritone-notes.md)

## Context

The device layer receives intents from an external LLM harness. Multiple
intents may compete (mine vs follow vs defend), and uncontrolled
concurrency would mean races over movement input. Baritone's
`IBaritoneProcess` + `PathingControlManager` (read and verified, see
reference notes) proves a workable contract, but its history also shows
that survival reactions cannot live inside this competitive model at all
(issues #786, #162). Survival handling is a separate mechanism with its
own decision record: see [ADR-0003](./0003-reflex-layer-preemption.md).

## Decision

### 1. Unit of capability = BotProcess, fixed contract

```
isActive(): boolean            // does this process want control?
priority(): int                // task-channel band, see below
onTick(calcFailed, isSafeToCancel): ProcessDirective
onLostControl()                // mandatory reset, idempotent
resume(InterruptionContext)    // see ADR-0003; validates world assumptions
onContextInvalidated(InterruptionContext)
displayName(): String          // HUD/status surface
```

### 2. One TaskArbiter per bot, winner-take-all

Each tick the arbiter collects processes reporting `isActive()`, puts
newly-active ones first, sorts by descending priority, polls `onTick`
down the list. `DEFER` steps aside; any other directive wins control;
a non-temporary winner calls `onLostControl()` on all remaining active
processes. This mirrors Baritone verbatim because we verified it works.

### 3. Task-channel priority is a banded budget, not free integers

| Band | Range | Examples |
|---|---|---|
| ROUTINE | 0-99 | MineProcess, FollowProcess, FarmProcess |
| TACTICAL | 100-199 | DefendProcess (= 150) |
| reserved | 200-999 | future escalation bands |

Bands are validated at construction (fail fast on out-of-band values).
Survival reflexes are NOT numbers here: they never enter the arbiter,
they preempt it from outside (ADR-0003). The old phrase "dual channel"
means exactly this: different mechanisms, not bigger numbers.

### 4. DefendProcess is the only combat task, and it only plans

One tactical combat scheduler, not one process per mob type. It emits a
`CombatPlan` (primaryTarget, desiredRange, preferredWeapon, stance,
abortThreshold) and nothing else. Micro-execution (positioning, swing
timing, LOS peeking) belongs to CombatBehavior in the behavior tier.
CombatBehavior must not branch on entity type; plans carry numbers only.
Rationale: combat logic attracts feature creep; separating plan from
execution keeps both replaceable (verified pattern in Baritone's
PathingBehavior/LookBehavior split; anti-pattern cost documented in its
issue #3565 re: InputOverrideHandler lacking central authority).

### 5. Self-describing capabilities toward the harness

Every capability exposes `{id, verbs, goalSchema}` so the harness can
discover the device surface at runtime; adding a capability requires no
harness change.

### 6. API purity

All contracts above are plain Java interfaces + POJOs in an `api`
package, JSON-schema-expressible. No Minecraft types leak across the
harness boundary.

## Consequences

- Arbitration is O(active processes) per tick; expected n < 20, negligible.
- Conflicting harness intents degrade into a priority-band question
  instead of input races.
- Every process author pays a one-time cost implementing
  resume-validation (ADR-0003); this is the price of correct preemption.
- Adding a mob-combat tactic means extending TacticalPlanner data/rules,
  never a new process.

## Amendments

- 2026-08: boundary B completed into a request/response loop -
  Behavior.tick() returns an ExecutionReport and Actor access is
  formalized as per-channel claims. See
  [ADR-0004](./0004-tick-pipeline-actor-channels.md).
