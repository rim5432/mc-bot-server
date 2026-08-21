---
title: ADR-0004 Tick pipeline, Actor channels, and execution feedback
last_verified: 2026-08-21
covers:
  - doc/decisions/0002-capability-model-task-arbiter.md
---

# ADR-0004 Tick pipeline, Actor channels, and execution feedback

- Status: accepted (2026-08)
- Amends: [ADR-0002](./0002-capability-model-task-arbiter.md) (completes
  boundary B into a request/response loop)

## Context

ADR-0002 left three engineering seams unspecified, all of which would be
hit within the first coding sessions:

1. Who calls the tick pipeline, and in what order?
2. How do multiple behaviors share one physical body (look direction is
   contested by pathing and combat simultaneously)?
3. How does a side-effect-free process learn that its goal was reached,
   failed, or that the bot got shoved off course - without reading world
   state itself (which would violate boundary B)?

Baritone's issue #3565 documents what happens when (2) has no central
authority: processes "step on each other's paws" via direct keyboard
overrides.

## Decision

### D1: single tick entry, fixed order

BotController registers once on the server tick END phase (world state
settled; outputs apply during next tick's physics - one tick latency,
inherent and acceptable). Order inside onTick():

```
reflexLayer.tick()          -> non-null directive skips everything below
arbiter.executeProcesses()  -> directives for behaviors
behaviors.tick(world, directives) -> ExecutionReports + Actor claims
actor.flush()               -> resolve channel claims, emit intents
```

### D2: Actor = four channels with per-tick claims

Channels: MOVE, ROT, USE, SLOT. Each tick, each channel accepts exactly
one winning claim. Behaviors submit claims carrying a priority; higher
priority wins; ties favor the incumbent holder (prevents rotation
jitter); claims expire at flush time - no persistent ownership state.
This is the concrete "shared resources go through request arbitration"
of boundary B.

### D3: ExecutionReport closes boundary B

```
ExecutionReport {
    status : RUNNING | SUCCESS | FAILED(reason) | STUCK
    metrics: progress snapshot (displacement, distance-to-goal, ...)
}
```

- SUCCESS is decided solely by evaluating the Goal's arrival predicate
  on the bot pose. Processes never evaluate predicates themselves.
- STUCK detection lives inside PathingBehavior (it owns displacement
  history): displacement < epsilon over N ticks while motion was
  intended.
- FAILED carries an enum reason (NO_PATH, TIMEOUT, INVALIDATED, ...).
- Processes consume reports to drive their own state machines; they read
  zero world state. Boundary B stays pure.

### D4: budgets live in commands, arrival lives in goals

GotoCommand{target, tolerance, timeoutTicks}. timeoutTicks is enforced
by the behavior layer (it owns the clock); the Goal predicate decides
arrival; tolerance is part of the goal construction (GoalNear semantics).

### D5: entity binding behind an adapter

WorldView/Actor have exactly one implementation seam: EntityBinding.
Phase 0 ships MockWorldView/MockActor only. The fake-player carrier
(vanilla-parity physics constants) is chosen by a spike when Phase 1
starts; nothing above the adapter knows or cares.

## Consequences

+ Look-direction fights between pathing and combat become claim-priority
  comparisons instead of last-writer-wins races.
+ Stuck/push-off recovery is testable offline: displacement history is a
  pure function over mock poses.
+ One-tick actuation latency is now a documented constant, not a surprise.
- Every behavior must re-assert channel claims every tick (cheap, but a
  discipline requirement; forgetting means losing the channel silently -
  acceptable default since silence must mean "no intent").
