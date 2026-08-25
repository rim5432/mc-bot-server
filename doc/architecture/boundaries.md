---
title: Boundary Contracts and Decision Ledger
last_verified: 2026-08-25
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
    record Ok(String taskId, boolean idempotencyReplay)
                                            implements SubmitResult {}
    record Rejected(String reason)         implements SubmitResult {}  // structural, sync
}

SubmitResult submit(BotCommand command);                       // idempotencyKey=null (fallback dedupe by verb+args)
SubmitResult submit(BotCommand command, String idempotencyKey); // null = fallback; non-null = explicit key
boolean       cancel(String taskId);
```

- `submit()` is **synchronous from the harness's perspective**: it
  returns either `Ok(taskId, replay)` for a well-formed command the
  bot has accepted, or `Rejected(reason)` for a command with a
  structural error (unknown verb, malformed payload, validation
  failure). Structural errors are the caller's fault and the harness
  must see them now, not after polling.
- `cancel()` is synchronous: returns `true` if the task was found
  and cancellation was issued, `false` if the task was already
  terminal or unknown.
- All task execution feedback (running, completed, failed, stuck,
  timed out, rejected for execution reasons) flows through the
  event stream, not the command channel.

#### Idempotency key (C scheme: verb+args fallback OR client-supplied key)

LLM harnesses retry on timeout by default — a `submit` that does
not return in time is *re-sent*, not *re-thought*. Without a
dedupe key, a retried command spawns a duplicate mission: two
`Goto` tasks, two terminal events, two `taskId`s. The harness
sees a successful `Ok` for both but only the first one moves
the bot; the second is pure noise. Idempotency is therefore not
a "nice to have", it is a contract requirement forced by the
LLM driver's actual behaviour.

The seam carries a single optional `idempotencyKey` argument on
`submit(BotCommand, String)`:

- **Explicit key** (`idempotencyKey != null`): the client
  (LLM harness) supplies a stable per-attempt identifier
  (UUID, monotonic counter, or content hash). Two `submit`
  calls with the same key collapse to the first one — the
  second returns `Ok(originalTaskId, replay=true)` without
  re-executing. The harness sees `replay=true` and knows
  this `Ok` carries no new execution, only a deduped
  pointer. Explicit keys survive across the LLM driver's
  full retry budget.
- **Fallback key** (`idempotencyKey == null`): the bot
  derives a dedupe key from `verb + canonical(args)`. Two
  structurally identical commands submitted in the same
  dedupe window collapse the same way. This is the "harness
  responsibility = 0" path for callers that do not
  implement retry-with-key; the cost is that any two
  same-verb-same-args submits in the window are also
  collapsed, which is the right behaviour for "harness
  re-sent the same command" and the wrong behaviour for
  "harness intentionally issued two same-verb-same-args
  tasks" — that is the harness's signal to use an
  explicit key.
- **Dedupe window**: the cache lives in the
  `CommandChannel` implementation and is keyed on the
  effective idempotency key. A cached `taskId` is
  retained until the task reaches a terminal state
  (`TASK_COMPLETED` / `TASK_FAILED` / `TASK_CANCELLED` /
  `TASK_REJECTED`); replay inside the same window is the
  common LLM-retry case. When the task ends, the entry is
  evicted and the next submit with the same key is a
  fresh acceptance — the harness's retry is no longer
  "the same request", it is "a new request carrying the
  same key". This is the LLM-friendly behaviour: a
  harness that retries after a long timeout should not
  get an opaque error, it should get a working mission
  that the bot will execute. The window does NOT span
  bot restarts; `resetAt` already wipes the in-memory
  queue and the cache follows.
- **What the bot does NOT do**: it does not invent a key on
  the harness's behalf when the harness is the dedupe
  author. It does not interpret the key as LLM intent.
  The bot is still harness-blind (invariant 6); the key
  is opaque to it.

**Rationale for C (both fallback and explicit)**: LLM
harness retry is real, but the harness's ability to mint
a stable per-attempt key varies. The MCP-driven harness
mints a UUID per `tools/call`; the in-process test rig
just calls `submit` twice. Forcing one shape would
penalise the other. C is the same answer as
[Stripe's idempotency-keys design](https://stripe.com/docs/api/idempotent_requests)
and HTTP's `Idempotency-Key` header: a client-supplied
header when the client has one, a content-hash fallback
when it does not. The two paths converge in the cache
and the same `Ok(taskId, replay)` answer.

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
- Mission lifecycle is event-visible: `TASK_PAUSED` (reflex
  preemption, urgent), `TASK_RESUMED` (clean revalidation),
  `TASK_DROPPED` (failed revalidation, urgent), `TASK_CANCELLED`
  (harness cancel). **Completion latency is one tick**: a behavior
  reporting SUCCESS retires the mission on the NEXT pipeline tick,
  so `TASK_COMPLETED` arrives on the tick after the goal predicate
  passed. Harness reconnect logic must not treat that tick of
  silence as a lost event.

#### Behind-consumer recovery contract

Events answer "what happened". State answers "what is". A consumer
whose `sinceEventId` is older than the queue's oldest retained
entry needs the second one first — the events between its cursor
and the queue head are gone, by design (queue rotation), and no
amount of re-polling will recover them. Per-consumer queues would
fix the symptom but not the cause: the cause is that the
consumer's mental model of the bot has drifted, and the
restoration primitive for that is `getState()`, not event replay.

When the queue detects a stale cursor it inserts a synthetic
`EVENT_GAP` at the head of the polled page:

- **Kind**: `EVENT_GAP` — distinct from `EVENT_DROPPED` (push-time
  overflow) so the harness can tell "I personally fell behind"
  from "the queue was saturated". They are different operational
  problems; conflating them loses the diagnostic.
- **Attrs**: `count` (number of events lost between the caller's
  cursor and the page head), `since` (the caller's stale cursor),
  `oldest` (the id of the first real event the page contains, or
  `lastEventId + 1` if the queue is empty and everything was
  lost). Stable keys; the harness can map them.
- **`urgent: true`** — the gate (harness's `shouldDrain`
  short-circuit) opens the moment the page is delivered, the same
  way it does for `TASK_PAUSED` and `TASK_DROPPED`. The harness
  treats the gap as a critical-update signal, not background.
- **Position**: head of the page, before any real event with
  id >= `oldest`. The first thing the harness sees after it
  hands the page to the consumer loop.
- **First-poll case** (cursor 0): the gap does **not** fire.
  Cursor 0 is the "give me everything" sentinel; the lost-range
  signal does not apply to a caller with no prior id. A harness
  that crashed without persisting its cursor also gets the full
  first-time page; the lost-range signal in that case is the
  `resetAt` marker, not a gap.
- **Empty-queue case** (post-`reset()` or all-rotated-out with a
  non-zero cursor): the gap does **not** fire on an empty queue.
  After a deliberate `reset()` the lost range is a wipe, not a
  rotation overflow; the `resetAt` marker is the signal. A
  rotation overflow that fully evicts the queue is so rare it is
  not worth a false-positive gap; a harness noticing "no events
  for a long time" should reconcile via `getState()`.

The harness's recovery sequence on `EVENT_GAP` is fixed and is
the contract, not a recommendation:

1. Call `getState()` to read the current pose, inventory,
   selected slot, active effects, and current task summary.
2. Reset the event cursor to `oldest` from the gap's attrs
   (NOT to `sinceEventId + 1` — that range is gone, polling it
   would just produce another gap).
3. Resume the normal `statusSnapshot(latestEventId)` loop.

The bot never tries to be clever about this. It does not
"remember" the lost events, it does not offer a backfill, and
it does not block on the harness's recovery. The gap is a
one-shot notice on the page where the loss would otherwise be
invisible.

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
7. **Reflex ticks may emit non-reflex verdicts.** When a reflex
   freeze lands in a mission's one-tick retirement lap, the decided
   mission's `TASK_COMPLETED` / `TASK_FAILED(attrs.reason)` is
   emitted on the reflex tick itself - possibly followed by other
   tasks' `TASK_PAUSED`. Replay state machines must therefore treat
   any `TASK_*` event as authoritative regardless of pause state;
   the sequences `COMPLETED → PAUSED → RESUMED` and
   `PAUSED → RESUMED → COMPLETED` are both legal. A live park never
   emits a verdict for the parked task; parked-mission verdicts can
   only arrive after `TASK_RESUMED` (or as `TASK_DROPPED`).

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
    reflex rules use dynamic priority functions. "Light planner" includes
    the decision NOT to fight: hostiles whose tactics structurally defeat
    melee (rangedTypes data) are refused at engage time
    (`TASK_FAILED(attrs.reason=ENGAGEMENT_REFUSED, attrs.threatType)`),
    never chased to timeout.
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
19. Shape-vs-traits split: geometry is derived from
    `CollisionShape`, non-geometric properties (climbable, liquid,
    damaging) come from `BlockTraitsRegistry`. Movement viability
    (can the body pass, can it stand) reads the shape; movement
    vocabulary expansion (ladder, swim, pillar) reads the traits.
    The split exists because "is the top half of this cell solid"
    is a property the shape answers for free (the box) while "is
    this a ladder" is a property the shape cannot infer (the
    collision box is empty above the rung). Putting geometry in
    the traits registry would force a per-id entry per block;
    putting traits in the shape would force a box scheme that
    pretends non-geometric behaviour. The trait registry is
    state-aware: `minecraft:stone_slab[type=bottom]` and
    `[type=top]` are different keys, because the two halves have
    different damage / climb answers. Type-only keys collapse
    them and the first half-slab the bot walks across teaches
    the planner the wrong lesson. Registry default for an
    unknown id is `BlockTraits.defaults()` (the safe "no
    special property" record); a missing entry is the registry's
    problem to surface, not the planner's.
    19a. Every new Movement must answer "can its full physical
    precondition be derived from `CollisionShape` plus the small
    traits registry" before it lands. Diagonal corner clearance
    (4-cell sweep, 1x2 vertical) is the first case the rule
    applied to; future Movement classes (Climb, Drop, Ladder,
    Swim, Pillar) are gated by the same answer.
    19b. Settled geometry semantics of the two predicates
    (issue 0002, adopted at the Stage 2 closeout review).
    `STEP_UP_REACH` (0.625) gates `passable()` - what a hop
    clears; `STANDABLE_THRESHOLD` (0.5) gates `walkableTop()` -
    the floor of MC's partial-top spectrum (slab 0.5, soul sand
    0.875, dirt path 0.9375 above it; farmland 0.25 and beds
    0.375 below it are not routing surfaces). The split exists
    because step-up reach and standability are different
    physical quantities that briefly shared one constant. A
    PARTIAL top is additionally walkable only if the box spans
    >= BODY_WIDTH (0.6) on both horizontal axes - the footprint
    rule that makes a fence post answer false: top high enough,
    footprint nothing to balance on. Fence cells are walls -
    passable false, walkableTop false; the way past a fence is
    the missing-post cell, which is EMPTY and already answers
    true. Pose-aware parameterization reopens via issue 0003
    without touching this entry.
    20. Plan-progress fuse and vertical trigger gate (issue 0001,
    adopted through its four-PR resolution chain). Stuck detection
    measures PLAN-PROGRESS, never per-tick motion: three OR
    criteria - waypoint index advanced, goal distance decreased,
    3D distance to the current waypoint beat the latched minimum
    by more than STUCK_EPSILON (a margin, not equality - in-place
    jitter must not mint new minima). External replan triggers
    (exhaustion, offPath, freshness drop) must not clear the
    accumulator; only real progress or the fuse firing resets it.
    The vertical gate complements the fuse: airborne ticks skip
    trigger evaluation (a falling pose would score false progress
    or request replans against snapshots the freshness check
    discards), and the landing edge bypasses the replan cooldown
    so the post-landing pose is the next plan's start cell. Fuse
    without gate leaves the airborne replan waste; gate without
    fuse leaves the free-fall limbo; both are required.
    HARD RE-TEST TRIGGER: the 3D-distance criterion and the
    motion-detector subsumption hold iff every waypoint gap is
    <= 1 cell AND steering is move-at-waypoint. Any change to
    BasicMoves.from (a long-range move), AStarPathFinder.reconstruct
    (waypoint simplification), or a path-smoothing pass (issue
    0005 P1.2 is the first queued one) must re-run the limbo and
    detour coverage (LimboCharacterizationGateTest plus the detour
    cases) and upgrade the criterion to arclength projection if
    waypoint gaps exceed one cell.
    Trigger fired 2026-08-24 by issue 0005 P1.2 (PlanSmoother,
    collinear-run collapse plus same-Y line-of-sight merge).
    Resolution: the arclength upgrade is NOT needed - merged
    segments are straight, so waypoint distance stays monotone
    along a segment - but two follow-throughs landed in the same
    change: off-path drift now measures XZ distance to the WALKED
    SEGMENT (previous-to-current waypoint; mid-segment distance to
    the endpoint is legitimate, not drift), and the criterion-3
    latched minimum resets on waypoint advance (a stale minimum
    would silence criterion 3 for a whole segment and false-STUCK
    a moving body on a detour leg). Limbo and detour coverage
    re-run green (offline suite plus runGameTest).
    21. Unreachable-goal escalation (NoPathEscalator, PathingBehavior
    package). A drained A* open set was always an immediate NO_PATH
    (empty waypoints -> empty cursor -> FAILED same tick); the gap
    was the budget-cut PARTIAL loop: each individual search
    "succeeds" locally (confidence >= MIN_PARTIAL_CONFIDENCE), the
    follower adopts, walks, exhausts, replans - and the mission
    burns its whole tick budget on a goal it can never reach
    (observed in the wild: a floating goal cell, 21s of planning
    churn, TIMEOUT at 600 ticks). Semantics frozen here: every
    adopted partial is a WITNESS; a witness whose terminal waypoint
    fails to improve the best-ever terminal-to-goal distance by
    more than 0.5 blocks (an axis step is 1.0) increments the
    ledger, an improving witness resets it, and WITNESS_LIMIT (3)
    consecutive non-improving witnesses declare FAILED(NO_PATH) -
    the mission process keeps its existing verdict mapping, nothing
    new crosses boundary B. Each witness also escalates the next
    search's node budget (x4 per witness, capped at 320k; wall
    clock unchanged), so the verdict only lands after the search
    had a progressively fair chance: a plateau at maximum budget is
    evidence of unreachability, not of a stingy search. Improvement
    is measured against the BEST-EVER distance, never the previous
    witness - a legitimate detour that temporarily moves the
    terminal away cannot mint false witnesses. The ledger resets on
    goal change and on any complete-route adoption; the count rides
    the keepalive event as `noPathWitnesses` for harness-side
    verdict anticipation.
    22. Reflex action vocabulary (ReflexAction, boundary C growth
    within ADR-0003 section 2's "the layer decides WHAT the frozen
    body does"). Rules name an action KIND; the controller owns the
    kind-to-Intent mapping - rules stay pure blackboard functions
    and Intent shapes stay owned by exactly one place. Kinds:
    FREEZE (MOVE(0,0), the default) and ASCEND (held jump: vanilla
    LivingEntity#aiStep routes a held jump in water deeper than the
    fluid-jump threshold to jumpInFluid, +0.04/tick upward impulse;
    the engine computes all motion, boundary A holds). The drowning
    rule SURFACE_ON_LOW_AIR (issue 0004 F6(2), after the 2026-08-24
    spawn-lake death) grounds its numbers in decompiled vanilla:
    ThreatBlackboard.airSupply drains 1/tick from 300 and regenerates
    4/tick; beginTick resets it to FULL so a rig whose sensor never
    stamps air reads as breathing (no air data must not mint a
    drowning reflex); the sensor (not the pipeline) stamps it -
    air is body state with no WorldView expression, same category
    as botHealth. Trigger 80 (~4s of air left; terminal swim-up is
    ~0.16 blocks/tick against 0.8 vertical drag), release 200 (the
    ascend holds through surface-bobbing regen instead of flapping
    at the film). Priority ordering is part of the contract:
    SURFACE (110) must outrank FREEZE (100) - freezing while
    drowning converts one lethal condition into two.
    23. Reflex-carried idle combat (ENGAGE_ON_HOSTILE_PROXIMITY,
    ReflexAction#ENGAGE; after the 2026-08-24 night-cave death).
    Resolves the workplan's standing ruling - standing idle-band
    DefendProcess vs reflex-carried combat directive - as a hybrid:
    the RULE only notices the threat (boundary-C shape, pure
    blackboard function, trigger 6 / release 14 / priority 90, below
    the survival holds); the CONTROLLER converts a winning ENGAGE
    decision into one preemption tick (shared park skeleton) plus a
    factory-built reflex-owned DefendProcess submitted to the
    arbiter; from the next tick the fight runs THROUGH the mission
    stage, because a fight is multi-tick state (target tracking,
    leash, grace) and a held-body reflex cannot carry it. The
    factory is wiring-owned (identity, timeout, type sets) and
    nullable - no factory degrades ENGAGE to the freeze hold. Two
    controller-side guards are part of the semantics: (a) the
    threat-gone resume path runs only when NO reflex fired this tick
    AND no mission is seated - otherwise the parked mission would
    steal the body back on the submission's very next tick (the
    one-tick window) or mid-fight; (b) a resubmit cooldown (40
    ticks, deliberately above the rule's hold window plus a
    release-band transit) absorbs both a threat fleeing through the
    release band and the hysteresis-held rule with no threat left -
    neither may mint a defend per tick, and the engagement tail
    must not mint a spurious instant-SUCCESS defend. Trigger 6 sits
    inside melee closing range and below ranged kiting standoff
    (~8-10): a skeleton at its preferred distance never trips the
    rule, because engaging it would only mint ENGAGEMENT_REFUSED
    churn (decision 11). Ranged idle threats remain an open gap
    (workplan survival gate).
    23a. Engage-chain slot discipline (review-hardened follow-up to
    23; three sibling holes shared one root - the controller created
    a two-level control chain (parked original + reflex fight) that
    TaskArbiter's single paused slot could not represent). Fixes,
    frozen here: (a) SINGLE-SLOT INVARIANT in the arbiter - parking
    over an occupied paused slot evicts the occupant through
    revalidate-and-requeue (its own resume(ctx); valid -> back to
    pending, invalid/dead -> onContextInvalidated), so no mission is
    ever orphaned; the controller calls the eviction explicitly
    before parking so a DROPPED revalidation reaches the harness as
    TASK_DROPPED (the arbiter never emits). (b) RESUME-GUARD
    DISPATCH on the paused occupant's identity: when the FIGHT is
    the parked one (a survival reflex parked it mid-fight), resume
    it whenever the seat is free - even while ENGAGE keeps firing,
    because threat-present is exactly when the fight must resume
    (gating on decision==null seats the requeued original over it
    and strands the fight in the paused slot); when the ORIGINAL is
    the parked one, resume only on a fully quiet tick with nothing
    seated and no fight awaiting its pending seat (the submission's
    one-tick window - there the arbiter must SELECT the fight).
    (c) The hysteresis activation edge is <= (boundary value fires),
    aligned with computePriority's documented "at or below" - a
    strict < shifted every hysteretic rule's effective trigger one
    tick late.
    24. Vitals pass and the extreme-scenario reflexes (issue 0008
    D1/D2/D3/D5; ruling batch 2026-08-25). D1: ThreatBlackboard
    gains fireTicks, freezeTicks, inWall - all entity-state reads in
    the airSupply category (sensor-stamped suppliers, beginTick
    reset to safe) - and the inLethalFluid field dead since ADR-0003
    finally has a writer. D2: ASCEND_IN_LETHAL_FLUID (priority 130,
    ASCEND - held jump routes to jumpInFluid(LAVA); lava is 4 HP/tick
    with no interval, the one vital that outranks everything). D3:
    FREEZE_ON_SUFFOCATION (priority 115, FREEZE - 1 HP/tick instant
    class where the rescue direction is UNKNOWN, so halting is the
    only strictly-correct reflex response; inWall on this codebase
    is almost always a pathing bug, the reflex doubles as the
    siren). D5: MinimalReflex.tick gains an airSupply parameter -
    a crashed bot with air < 80 holds jump; one more if in the same
    dependency class as the lava flag, ADR-0005 D3 intact. D4 ruled
    NO RULE: fire is self-answering (extinguished by water the
    ongoing route touches) and sense-only since D1. The triage
    ladder is now five rungs and frozen: LAVA 130 > SUFFOCATION 115
    > SURFACE 110 > FREEZE 100 > ENGAGE 90. RELOAD-PARITY RULE (found
    live in review): a reflex type registered by assembly code MUST
    land with its ReflexRuleJson parse/write branch AND its default
    datapack row in the same change - /reload replaces the whole
    table, so a code-only rule is silently dropped by the first
    reload; the RuleTableGateTest
    shippedDatapackTableCoversEveryCodeRegisteredRuleType gate pins
    the lockstep. Boolean-condition rules (lava, suffocation) carry
    only their priority in JSON - the inverted 0/1 signal's
    hysteresis thresholds are code constants, not tunable data.
    25. Block capability axis opened - dig first (issue 0009; user
    ruling 2026-08-25 "start enriching the bot's block capability",
    motivated by the gravel-suffocation escape). Decision 14's
    "four channels" grows to FIVE: INTERACT carries held block
    interaction (USE stays entity-melee; the same channel 0007
    section 6.2 hypothesized, pulled forward because bare-hand
    mining is inventory-free). Vocabulary: `Intent.Dig(CellPos)` - a
    claim this tick IS the button state; multi-tick destroy progress
    accumulates adapter-side in DigExecutor (mirrors
    ServerPlayerGameMode: per-tick = digSpeed / destroySpeed /
    (correctTool ? 30 : 100), x0.2 airborne, break at 1.0 via
    `level.destroyBlock(pos, true)`, crack stages through
    `destroyBlockProgress`; deviations: no stop-destroy 0.7 shortcut
    and no Forge LeftClickBlock/onBlockBreakEvent hooks - both
    Player/client-shaped, deferred with the 0007 facade). The
    arithmetic lives in core `DigPacing` (gravel 18 hand-ticks,
    stone 150, hardness-0 pops, unbreakable never). Reflex half:
    `ReflexAction.DIG` maps (controller) to MOVE hold + ROT aim
    (presentation only - the executor never reads facing) +
    INTERACT dig claims from the decision's `actionTarget`
    (`ReflexRule.actionTarget(board)`); a targetless DIG degrades to
    the freeze hold - missing data must not mint a dig-at-null.
    FREEZE_ON_SUFFOCATION is renamed DIG_ON_SUFFOCATION (115 /
    hold 10 unchanged, ladder unchanged): with dig capability the
    rescue direction is KNOWN (the eye block, sensor-stamped onto
    `ThreatBlackboard.suffocationBlock`), superseding the stopgap
    whose whole argument was the unknown direction. The old type
    string is a hard parse error (loud datapack update, not silent
    drop); the reload-parity gate pins the lockstep. Crashed-state
    suffocation stays freeze-class - MinimalReflex keeps "a few ifs"
    (issue 0009 F1).
    26. Reflex ESCAPE action and rescue mission handoff (issue 0008
    D2-upgraded + D5-revised; user ruling 2026-08-25 "must have
    shortest-shore escape" and "fire find-water by burn-to-death
    threshold"). `ReflexAction.ESCAPE` is the mission-handoff twin of
    ENGAGE: the controller spends one preemption tick (FREEZE hold),
    calls `rescueMissionFactory.get()` to mint a GotoProcess to a
    safe cell, registers + requests control, and from the next tick
    the rescue runs through the normal mission stage. Bookkeeping
    mirrors ReflexEngageSeat in ReflexRescueSeat (maySubmit /
    submitted / retireFinished / rescueIsParked /
    rescueAwaitingSeat, 80-tick resubmit cooldown — longer than
    ENGAGE's 40 because a swim/walk route takes longer to resolve
    than a fight). The resume guard dispatches on both fight and
    rescue seat states (a reflex-owned rescue parked by another
    reflex resumes whenever its seat is free, same as ENGAGE).
    RescueMissionFactory (adapter) reads body state at call time:
    inLava -> nearest non-liquid shore cell (passable + walkable top
    below, 12-block radius scan); remainingFireTicks > 0 -> nearest
    water-adjacent cell; neither -> null (degrades ESCAPE to FREEZE).
    Two rules: ESCAPE_ON_LAVA (130, replaces ASCEND_IN_LETHAL_FLUID
    — the old type string is a hard parse error; pure ASCEND floated
    the body to the surface but never reached shore, so the bot
    still burned) and EXTINGUISH_FIRE (105, between SURFACE 110 and
    FREEZE 100; triggers only when fireTicks/20 >= health — the
    lethal band; non-lethal fire stays sense-only per the original
    D5 intent). Both carry reload-parity JSON forms + datapack rows.
    In-engine: `escapesLavaToShore` (5x5 pool, fire-resist, body
    reaches dry ground) and `findsWaterWhenBurning` (health=5,
    fireTicks=100, water 4 blocks east, fire extinguished by
    contact).
    27. Powder-snow climb reflex (issue 0008 F6; user ruling
    2026-08-25 "this how to solve"). `ClimbOutOfPowderSnowRule`
    (CLIMB_OUT_OF_POWDER_SNOW, priority 95, trigger freezeTicks=100)
    emits ASCEND (held jump). Powder snow is climbable
    (Entity.isStateClimbable returns true, decompiled Entity.java:764
    — same class as ladders/vines), so the held-jump impulse walks
    the body up and out of the snow; no mission handoff needed.
    Trigger at 100 leaves 40 ticks (2s) before the fully-frozen
    damage phase (1 HP/40t at freezeTicks>=140). Priority 95 slots
    below FREEZE(100) and above ENGAGE(90): at 3 HP the bot freezes
    rather than climbs (the climb takes ticks; freeze damage is
    slow enough that FREEZE+harness is safer). Design constraint:
    `ReflexHysteresis` is direction-locked to "lower signal = more
    dangerous" (the air-supply shape in SurvivalReflexLayer.decideHysteresis);
    freezeTicks is "higher = more dangerous", so the rule uses pure
    `computePriority` (same shape as EscapeLavaRule/ExtinguishFireRule).
    Thaw is -2/tick once clear, so the freeze budget recovers fast.
    Reload-parity: JSON branch + datapack row + lockstep gate
    (7 rule types). In-engine: `climbsOutOfPowderSnow` (3x3 two-deep
    pit, body climbs out, no freeze damage taken).
    28. Harness surface convergence (issue 0011; user ruling
    2026-08-25 "the harness-interaction side is getting messy"). The
    CONSOLE verb layer is frozen contract surface, not convenience:
    six verbs (status / goto / cancel / stop / events / reset), one
    JSON object per answer, machine-readable `reason` on failure -
    the table lives in the issue and grows by one row per new
    process kind, decided in the shipping issue, never as an ad-hoc
    brigadier branch. Known wart, documented not fixed: `/bot events
    since` is a console Integer while the cursor contract is long
    (repair rides the first non-RCON transport). RESET is the
    ADR-0005 5a promise finally wired - clears latch + counter,
    echoes `crashed` so a harness resetting a healthy bot learns it
    did nothing. EVENTS narrowing (`[only] <kindPrefix>`, e.g.
    TASK_) lives on EventBatch.narrowedToKindPrefix so every
    transport shares it; two non-negotiables: the cursor fields
    (latestEventId, resetAt, droppedCount) stay the TRUE stream
    values - the harness's next poll advances by what existed, not
    what was shown - and the cursor-integrity kinds (EVENT_GAP,
    EVENT_DROPPED) survive every filter, because hiding a gap is a
    silent lie. The STATE_PUSH-vs-status-vs-KEEPALIVE responsibility
    table (issue 0011 section 3) binds disclosure growth: state
    channels answer "what is the model now", keepalives answer "is
    the plan progressing", and no fourth overlap may appear. Session
    runtime (a tool/harness subpackage: rcon transport + cursor/reconnect state
    + auto-journal) is QUEUED with its first customer - the survival
    rehearsal driver - not built speculatively; MCP/HTTP stays
    deferred until a non-RCON consumer exists (the boundary-D
    reopen trigger).

## Deferred, with reopen conditions

| Item | Reopens when |
|---|---|
| A* internals, async replan worker | Phase 2 starts (Movement model already fixed; only replan() guts change) |
| Blackboard staleness/timestamps | first stale-path bug after pathfinding moves off-thread |
| ConstraintGuardian generalization interface | second concrete guardian need appears |
| Memory / waypoints / persistence | first cross-session task requirement |
| Inventory / crafting skills | after the locomotion slice passes acceptance |
| CombatBehavior micro-algorithms | never architected - implementation freedom inside boundary B. OPERATIONALIZED 2026-08-22: melee rides USE ("act with main hand"); the adapter resolves a press as reach+cone against living hostiles (cone, not ray-clip - EntitySnapshot granularity is block-level). DefendProcess verdicts come from scans/leash/timeout only; locomotion reports never decide a fight. pausedReflexes stays deferred - freeze outranking combat is correct survival behavior. |
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
