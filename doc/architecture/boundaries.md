---
title: Boundary Contracts and Decision Ledger
last_verified: 2026-08-21
covers:
  - doc/decisions/0002-capability-model-task-arbiter.md
  - doc/decisions/0003-reflex-layer-preemption.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/decisions/0005-tick-pipeline-exception-policy.md
---

# Boundary Contracts and Decision Ledger

All future implementation happens inside the seams below. Interfaces are
frozen; implementations are free. When a change is needed, it is event-
driven via the reopen triggers at the end - never vibe-driven.

## The four boundaries

| ID | Sides | Contract | Status | Verification |
|---|---|---|---|---|
| A | Bot <-> MC engine | Read via WorldView (pure read, mockable, zero side effects). Write via Actor (intents only, idempotent, no self-computed physics). | FROZEN | core package has zero MC imports; offline unit tests pass against mocks |
| B | Process <-> Behavior | Process is side-effect-free; its only output is Directive{Goal, Overrides}. Behavior monopolizes Actor access through per-channel claims. Feedback flows back as ExecutionReport (see ADR-0004) - a request/response loop, not one-way commands. | FROZEN | adding a task = writing one new Process, touching zero Behaviors |
| C | ReflexLayer <-> Arbiter | Hard call-order constraint: reflex ticks first, non-null reflex skips mission for that tick. Preemption always carries InterruptionContext; resume validates world assumptions first. Reflex never enters arbiter numerics. | FROZEN (ADR-0003) | rewriting the whole rule table is invisible to the Process tier |
| D | Bot <-> Harness | Two protocol surfaces carrying three semantic shapes. Command channel: `submit(BotCommand) -> SubmitResult` (sealed: `Ok(taskId)` \| `Rejected(reason)`) and `cancel(taskId) -> boolean`. Event stream: `statusSnapshot(sinceEventId) -> EventBatch`. State snapshot: `getState() -> BotState`. Bot does not know the harness -- no LLM awareness, no consumer-count awareness, no polling-interval awareness. See [Boundary D protocol](#boundary-d-protocol) for the full contract (6 invariants, three-channel split, sync error / async execution split, model-relevant state fields). Signatures and protocol frozen; command vocabulary may grow. | FROZEN (signatures + protocol) | swapping the entire harness changes zero bot code |

Rigidity: A/B are constitutional; C is current law; D is a treaty whose
width is itself the promise - the narrower the seam, the purer the bot.

## Boundary D protocol

Boundary D is the only seam between the bot and whatever is driving it
(MCP harness, programmatic driver, CLI, test rig). It has two protocol
surfaces carrying three semantic shapes. This subsection is the **live
contract** for boundary D; the surface-level row above is a summary.
Reference for the design rationale: [disclosure-patterns.md](../reference/disclosure-patterns.md).

### Surfaces

| Surface | Direction | Pulled or pushed? | Carries |
|---|---|---|---|
| Command channel | harness -> bot | harness pulls (calls) | `submit`, `cancel` |
| Event stream | bot -> harness | harness pulls (polls) | `EventBatch` |
| State snapshot | bot -> harness | bot pushes (on change); harness can also pull via `getState()` | `BotState` |

The three semantic shapes map to the three Numen disclosure channels
documented in [disclosure-patterns.md §1](../reference/disclosure-patterns.md#1-the-three-channel-split-is-mandatory-not-a-stylistic-choice).
The event stream carries both generic world events and `TASK_*` events
that wrap a `TaskResult`; the state snapshot carries a model-relevant
summary; the command channel carries the verbs the harness issues.

### Command channel

```java
sealed interface SubmitResult {
    record Ok(String taskId)       implements SubmitResult {}
    record Rejected(String reason) implements SubmitResult {}  // structural, sync
}

SubmitResult submit(BotCommand command);
boolean       cancel(String taskId);
```

- `submit()` is **synchronous from the harness's perspective**: it
  returns either `Ok(taskId)` for a well-formed command the bot has
  accepted, or `Rejected(reason)` for a command with a structural
  error (unknown verb, malformed payload, validation failure).
  Structural errors are the caller's fault and the harness must see
  them now, not after polling.
- `cancel()` is synchronous: returns `true` if the task was found
  and cancellation was issued, `false` if the task was already
  terminal or unknown.
- All task execution feedback (running, completed, failed, stuck,
  timed out, rejected for execution reasons) flows through the
  event stream, not the command channel.

### Event stream

```java
record EventBatch(
    List<BotEvent> events,    // arrival order; ids > sinceEventId
    int  droppedCount,         // > 0 means synthetic EVENT_DROPPED is in `events`
    long latestEventId,        // cursor for the next pull
    long resetAt               // monotonic bot-restart marker
) {}

EventBatch statusSnapshot(long sinceEventId);
```

- The harness polls with `sinceEventId` (the cursor from the last
  `latestEventId` it saw). The bot returns events with id >
  `sinceEventId`, in arrival order.
- The cap is `DEFAULT_CAP = 200`. When full, oldest events are
  dropped; `droppedCount` is set; a synthetic `EVENT_DROPPED`
  event is included in the batch (so the harness sees the loss
  without having to track it itself).
- `resetAt` is a monotonic bot-restart marker. If the harness sees
  `resetAt` change, the in-memory queue has been wiped; it must
  reconcile via `getState()`. **Events do not survive bot
  restart.**
- `BotEvent` carries a `kind` (registered type), a `day`+`t`
  game-time stamp, an `urgent` flag, structured `attrs`, and a
  human-readable `text`. Unknown kinds get a `UNKNOWN` fallback
  that renders raw text to the harness.

### State snapshot

```java
BotState getState();
```

- Server-pushed on transition via `STATE_PUSH` events on the event
  stream; the harness caches. `getState()` is the resync call after
  a `resetAt` change.
- Contains only **model-relevant categorical fields**: bot pose
  (position, rotation, dimension), inventory (item type + count
  per slot, not durability or enchantment), selected hotbar slot,
  active effects (type + amplifier, not remaining ticks), current
  task summary.
- Durability, enchantment progress, and effect remaining ticks are
  **deliberately absent**. The harness renderer can compute the
  continuous values locally if it needs them; including them in
  `BotState` would cause a push every tick.

### Invariants (must hold)

1. **Every `BotEvent` carries a game-time timestamp** (`day` + `t`).
   The bot stamps it; the harness never recomputes it. Rationale
   and the cross-side (server + client) sharing of the `compose`
   method: [disclosure-patterns.md §3](../reference/disclosure-patterns.md#3-three-loss-prevention-invariants).
2. **Events do not survive bot restart.** `resetAt` is the
   surface. The harness must reconcile via `getState()` when
   `resetAt` changes.
3. **Drops are not silent.** When the event queue overflows, oldest
   events are dropped, `droppedCount` is set, and a synthetic
   `EVENT_DROPPED` event is included in the batch. The harness
   sees the loss; the bot does not pretend the drop didn't happen.
4. **`urgent` is information-freshness, not importance.** A `true`
   urgent flag means "the bot is currently making a wrong decision
   because it does not know this". Urgent events bypass the gate
   (the harness's `shouldDrain` short-circuits to true) and open
   a new decision round.
5. **Sync error channel is for structural failures only.** A
   command with a malformed payload returns `Rejected`
   synchronously from `submit()`. A well-formed command that the
   bot cannot execute right now returns a `TASK_REJECTED(taskId,
   reason)` or `TASK_FAILED(taskId, TaskResult)` event
   asynchronously. The split is the caller's-fault vs world's-state
   split; see [disclosure-patterns.md §10a](../reference/disclosure-patterns.md#detail-10a-sync-error-vs-async-execution-error).
6. **The bot does not know the harness.** No LLM awareness, no
   consumer-count awareness, no polling-interval awareness, no
   subscription model. The bot is a passive event producer; the
   harness is the active consumer.

### Out of scope (by design)

- **MCP / HTTP / gRPC adapters** live in the harness, not the bot.
  Bot speaks Java only; the harness picks its transport. A
  reference MCP adapter is a candidate addition to `tool/`.
- **LLM-side rendering** (XML vs JSON, the `toModel` /
  `chatPreview` lambdas, the `<events>` wrapper, the
  "world first, owner second" ordering) is the harness's
  responsibility. The bot emits structured events; the harness
  formats them. See [disclosure-patterns.md §2](../reference/disclosure-patterns.md#2-the-type-table-is-data-not-code)
  for the type-table pattern that the harness may apply to its
  own render table.
- **Conversation history, context window management, memory
  distillation** -- all on the harness side.
- **Numen's `EventOutbox` `SavedData` pattern** (server-side
  persistent queue for owner-offline scenarios) is not adopted.
  We have harness-always-on, not owner-offline. If cross-restart
  event replay becomes a requirement, it lives in a Stage 1+
  `JsonlJournal` per the [workplan](../guide/workplan.md), with
  `resetAt` remaining the boundary-level indicator.

## Decision ledger (binding)

1. LLM stays out of the bot; harness is an external brain.
2. Host = server-side fake player; physics owned by the engine.
3. Capability stack: WorldView / Actor / composite skills / orchestration.
4. Data-driven trio: Registry + Codec + ReloadListener; JSON says what,
   code says how.
5. core package has zero MC imports; offline unit-testable.
6. Process/Behavior duality with central arbiter: winner-take-all, DEFER,
   fresh intent enters queue head.
7. Goal is a pure data abstraction; search algorithms decoupled from goal
   types.
8. Movement modeled as five-tuple (src, dst, precondition, cost,
   execution state machine).
9. Reflex layer is an independent bypass: sensor -> blackboard -> rule
   table; it never competes in process arbitration.
10. Scenario combinations become rule-table data; mechanisms written once;
    no per-mob processes.
11. DefendProcess is a light planner; CombatBehavior owns micro-execution;
    reflex rules use dynamic priority functions.
12. InterruptionContext: preemption carries a snapshot; resume validates
    world assumptions first.
13. Behavior.tick() returns ExecutionReport (RUNNING / SUCCESS / FAILED /
    STUCK); stuck detection lives inside behaviors; processes consume
    reports and stay pure.
14. Actor = four independent channels (MOVE / ROT / USE / SLOT); per-tick
    claims with priority resolution; claims expire each tick.
15. Single tick entry: BotController on server tick END phase; fixed
    order reflex -> arbiter -> behaviors -> actor flush.
16. Phase 0 includes one minimal reflex rule so the full preempt ->
    resume chain runs against mocks before any real entity exists.
17. WorldView exposes three data primitives: BlockSnapshot, EntitySnapshot, CollisionShape.
    17a. WorldView must expose `isLoaded(pos): boolean`, distinguishing "unloaded" from "air". A naive `getBlockState(unloadedPos) == AIR` lies to the planner; the loaded/unknown distinction must be a first-class query.
    17b. WorldView queries support two view modes: `SNAPSHOT` (for the planner, a frozen view of a given tick) and `LIVE` (for the executor, a real-time view of the current tick). Under a single-threaded implementation the two are equivalent, but the interface must reserve a `ViewMode` parameter so future off-thread A* does not require an interface change. SNAPSHOT creation, invalidation, and lifecycle are entirely the caller's (the planner's) responsibility. WorldView promises only "return data per the requested `ViewMode` semantics"; snapshot storage, staleness, and disposal are NOT part of the WorldView contract -- if needed they go in workplan follow-ups, not in the interface.
18. First boundary-D semantics: GotoCommand{target, tolerance,
    timeoutTicks}; seam verbs stay submit/cancel/status.

## Deferred, with reopen conditions

| Item | Reopens when |
|---|---|
| A* internals, async replan worker | Phase 2 starts (Movement model already fixed; only replan() guts change) |
| Blackboard staleness/timestamps | first stale-path bug after pathfinding moves off-thread |
| ConstraintGuardian generalization interface | second concrete guardian need appears |
| Memory / waypoints / persistence | first cross-session task requirement |
| Inventory / crafting skills | after the locomotion slice passes acceptance |
| CombatBehavior micro-algorithms | never architected - implementation freedom inside boundary B |
| Fake-player carrier choice | spike opens at entity-binding start |

## Explicitly excluded (do not resurrect)

| Excluded | Reason |
|---|---|
| LLM integration (semantics, prompts, memory distillation) | harness responsibility; bot architecture never bends toward it |
| AttentionDirector / gaze vector | solves a nonexistent perf problem; adds real state-management tax |
| Meta-rule layer | dynamic priority functions cover it without Godel recursion |
| Client-side idle form | contradicts autonomous server-side operation |

## Reopen triggers

- **D** reopens when a required task cannot be expressed by the current
  command vocabulary (e.g., tasks needing streamed progress feedback).
  Extend the vocabulary; never the seam semantics.
- **C** reopens when reflex rules start conflicting with each other (one
  reflex's execution triggers another's condition). That is the real
  arrival of ConstraintGuardian parallel arbitration.
- **A/B** should never reopen. If one seems necessary, treat it as a
  failed prior decision: run the ADR reversal procedure explicitly -
  new ADR superseding the old - not quiet edits.

Related: [Architecture Overview](overview.md), [ADR index](../README.md)
