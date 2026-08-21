---
title: ADR-0003 ReflexLayer preemption semantics and interruption recovery
last_verified: 2026-08-21
covers: []
---

# ADR-0003 ReflexLayer preemption semantics and interruption recovery

- Status: accepted (2026-08)
- Depends on: [ADR-0002](./0002-capability-model-task-arbiter.md)
- Evidence: Baritone issues #786 (mobs kill bot while mining), #162
  (deaths during recalculation; community fallback was attaching an
  external killaura - an admission the in-process model cannot do it),
  #3565 (structural critique: input authority and cancellation are the
  hard parts). All three read and verified on 2026-08-21.

## Context

Survival reactions need sub-second response: a creeper fuse is ~30 ticks
and the safe interrupt window is under 10. Processes are task-local by
design (MineProcess watches ore, not its back), so threat awareness
cannot be a process concern without leaking survival checks into every
process. Scenario combinations (mob x terrain x distance) explode if
each becomes a task; they compress fine if they become data.

Minecraft is a single-threaded synchronous tick world. Any design that
smuggles in threads or "hard real-time clocks" creates concurrency bugs
to solve a problem that does not exist here.

## Decision

### 1. Separate mechanism: SurvivalReflexLayer, outside the arbiter

Three cooperating parts, one purpose ("stay alive"), no DEFER, no queue:

- **ThreatSensor**: runs every tick, omnidirectional, task-independent.
  Emits structured threats (type, pos, distance, bearing sector incl.
  behind, fuse progress for creepers). Filter = sort by urgency, take
  top-K. No attention state machine (rejected, see below).
- **ThreatBlackboard**: single-writer (the sensor), written at tick
  boundary before anything reads it. Holds active threats, overall level,
  derived fields: pincer detection (threat vectors > 90 deg apart),
  nearest cover direction, nearest water direction. Derived fields are
  what make retreat rules hazard-aware (never flee into lava).
- **Rule table**: data-driven conditions -> reflex actions with dynamic
  priorities (below). Adding a counter to a new mob = adding JSON, not
  code.

### 2. Call-order constraint replaces "priority numbers"

```
onTick():
    reflex = reflexLayer.tick(world, blackboard)
    if (reflex != null):
        arbiter.forcePauseAll(reflex.cause())   // notification, not request
        actor.clearIntents()                    // single-writer handover
        reflex.execute(actor)
        return                                  // mission skipped this tick
    arbiter.executeProcesses()
```

The guarantee is *ordering within one synchronous tick*, not a clock
domain. Reflex priority is computed per rule per tick:

```
computePriority(bb):
    base (e.g. creeper-fuse 400, skeleton-ranged 300)
  + imminence ((fuseBudget - fuseTicks) * weight)     // continuous escalation
  + compound adjustments (pincer, LOS, health)         // self-contained function
```

No static fields patched by meta-rules; no rule-modifies-rule recursion;
each rule is unit-testable alone. The engine picks max(computePriority).

### 3. Preemption captures InterruptionContext; resume revalidates the world

At preemption time the executor records: tick, bot pose, targeted block
and/or entity id, cause enum (CREEPER_FUSE, SKELETON_LOS, ...).

`BotProcess.resume(ctx)` MUST first call
`ctx.isWorldAssumptionValid(now)`; if false, `onContextInvalidated(ctx)`
drops or re-targets the task instead of blindly continuing. Example:
after a creeper blast, MineProcess must abandon a target whose face is
now buried by fallen gravel.

Invariant (from Baritone #3565's cancellation lessons): while a reflex
holds the actor it is the ONLY writer to actor inputs; graded interrupts
respect physical safety (mid-fall / mid-parkour states expose
safeToCancel=false upward, and lethal-threat rules may override at
accepted fall-damage cost).

### 4. Scope guard

Implement exactly one reflex layer now: SurvivalReflexLayer. The
generalization toward ConstraintGuardians (friendly-fire guard, server-
rule guard, hunger guard) is recorded as a Phase 3 direction. Do not
extract the interface until a second concrete guardian exists -
single-implementation interfaces are speculative tax.

## Rejected alternatives (kept on record)

| Alternative | Why rejected |
|---|---|
| Threat response as high-priority processes | task-local vision; arbitration latency; scenario explosion |
| AttentionDirector / gaze vector | adds managed state to solve a nonexistent perf problem; top-K suffices |
| Meta-rule layer rewriting priorities | Godelian recursion; dynamic computePriority does it without a second layer |
| Async survival clock / worker-thread sensor | MC is synchronous; staleness handling is Phase 2 work when pathfinding moves off-thread |

## Consequences

+ Worst-case threat response drops to same-tick ordering (no arbitration).
+ Scenario growth stays data-shaped (JSON rules), code stays fixed.
+ Every BotProcess author implements resume-validation once; this is the
  core correctness cost of preemption.
+ Rule flapping (SEEK_COVER <-> resume oscillation at condition
  boundaries) must be damped with hysteresis margins inside rule
  conditions; treat as required invariant, reviewed per rule addition.
