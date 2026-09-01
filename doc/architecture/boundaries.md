---
title: Boundary Contracts
last_verified: 2026-09-02
covers:
  - doc/decisions/0002-capability-model-task-arbiter.md
  - doc/decisions/0003-reflex-layer-preemption.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/decisions/0005-tick-pipeline-exception-policy.md
---

# Boundary Contracts

All future implementation happens inside the seams below. Interfaces are
frozen; implementations are free. When a change is needed, it is event-
driven via the reopen triggers at the end - never vibe-driven.

**Decision index lives in [`doc/_state/boundaries.yaml`](../_state/boundaries.yaml)** —
machine-readable list of all 61 decisions with adoption dates, amendment
chains, and section mapping. This file carries the contract text; the YAML
is the citation-resolution index.

## The four boundaries

| ID | Sides | Contract | Status | Verification |
|---|---|---|---|---|
| A | Bot <-> MC engine | Read via WorldView (pure read, mockable, zero side effects). Write via Actor (intents only, idempotent, no self-computed physics); menu transactions ride the same write surface as imperative request-response methods on the actor binding (`MenuTransactions`: openMenu / openInventoryMenu / menuSnapshot / menuClick / closeMenu — never per-tick claims; ledger 29). Slot identity: `InventoryView` (binding-flat) and `MenuView` (menu-flat + `SlotRole`) are BOTH contract index spaces, deliberately not merged (ledger 31) - SlotRole is the only legal join key, and every translation must be a named bridge, never inline arithmetic. | FROZEN (menu vocabulary grew 2026-08-26, issue 0007 §6.2 A1 ruling) | core package has zero MC imports; offline unit tests pass against mocks |
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
State-snapshot field set (decision 32, issue 0013 R5; `foodLevel`
joined by decision 34): `pos`, `yaw`, `pitch`, `dimension`,
`itemCounts`, `selectedHotbarSlot`, `effectAmplifiers`,
`currentTaskSummary`, `healthHearts`, `freeSlots`, `foodLevel`.
Serialized wire keys shorten five components (H-R4: the key is
frozen even when the field renames): `dimension`->`dim`,
`itemCounts`->`items`, `selectedHotbarSlot`->`slot`,
`effectAmplifiers`->`effects`, `currentTaskSummary`->`task`,
`foodLevel`->`food` (decision 34); the rest travel verbatim. Health is bucketed to whole hearts (0..10; vanilla
runs 2 HP per heart) - the
change-detect channel compares whole records, so continuous fields
would push every tick; bucketing keeps the cadence honest.

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
  restart.** Shipped caveat: the counter seeds at 1 on every JVM
  boot, so epochs collide across process restarts; until persistent
  seeding lands (issue 0012 D5), a cross-boot wipe is detected by
  the id space restarting (`since` beyond the last known id), not
  by `resetAt` alone.
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
   `resetAt` changes. As shipped the marker does not survive a JVM
   restart either (it reseeds at 1), so cross-boot wipes are read
   off the id space instead; persistent seeding is tracked under
   issue 0012 D5.
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

The verdict texts live in doc/architecture/ledger.md; this index is
where every `boundaries.md decision N` citation resolves. Entries
extend only forward - amendments annotate both sides (the chains are
listed under the ledger's Amendment chains section), and
ordering-critical numbers sit behind test gates, not behind prose.

| # | Entry | Adopted |
|---|---|---|
| 1 | LLM stays out of the bot; harness is an external brain |  |
| 2 | Host = server-side fake player; engine owns physics |  |
| 3 | Capability stack: WorldView / Actor / skills / orchestration |  |
| 4 | Data-driven trio: Registry / Codec / ReloadListener |  |
| 5 | core package has zero MC imports |  |
| 6 | Process/Behavior duality; central winner-take-all arbiter |  |
| 7 | Goal is a pure data abstraction |  |
| 8 | Movement modeled as a five-tuple |  |
| 9 | Reflex layer is an independent bypass |  |
| 10 | Scenario combinations are rule-table data |  |
| 11 | DefendProcess light planner; structural refusals at engage time |  |
| 12 | InterruptionContext snapshot; resume revalidates world first |  |
| 13 | Behavior.tick returns ExecutionReport |  |
| 14 | Actor four independent channels, per-tick claims |  |
| 15 | Single tick entry; fixed reflex -> arbiter -> behaviors -> flush order |  |
| 16 | Minimal reflex rule exercises the full preempt chain early |  |
| 17 | WorldView exposes BlockSnapshot / EntitySnapshot / CollisionShape |  |
| &nbsp;&nbsp;17a | isLoaded(pos) distinguishes unloaded from air |  |
| &nbsp;&nbsp;17b | ViewMode SNAPSHOT/LIVE reserves the off-thread seam |  |
| 18 | First boundary-D semantics: GotoCommand, submit/cancel/status |  |
| 19 | Shape-vs-traits split for movement viability vs vocabulary |  |
| &nbsp;&nbsp;19a | New Movements derive preconditions from shape plus traits |  |
| &nbsp;&nbsp;19b | Settled STEP_UP_REACH / STANDABLE_THRESHOLD geometry semantics |  |
| 20 | Plan-progress fuse and vertical trigger gate | 2026-08-24 |
| 21 | Unreachable-goal escalation: honest NO_PATH | - |
| 22 | ReflexAction vocabulary (boundary C growth) | 2026-08-24 |
| 23 | Reflex-carried idle combat (ENGAGE) | 2026-08-24 |
| 24 | Vitals pass and the extreme-scenario reflexes | 2026-08-25 |
| 25 | Block capability axis opened - dig first | 2026-08-25 |
| 26 | Reflex ESCAPE action and rescue mission handoff | 2026-08-25 |
| 27 | Powder-snow climb reflex | 2026-08-25 |
| 28 | Harness surface convergence (console + disclosure tables) | 2026-08-25 |
| 29 | Menu transactions are imperative request-response | 2026-08-26 |
| 30 | Consumer-count drain-lock retired from the event stream | 2026-08-26 |
| 31 | Slot identity dual-space accepted; dual-RCON re-eval trigger | 2026-08-26 |
| 32 | State-snapshot field set pinned at BotState's categorical rule | - |
| 33 | Harness interaction model codified: space is a namespace, time is job control | 2026-08-27 |
| 34 | Carrier food lifecycle: Path A FoodData on the body, free-regen floor retired, EAT reflex consumption | 2026-08-27 |
| 35 | Third reflex seat promotion: ReflexMissionSeat unifies ENGAGE/ESCAPE; FORAGE action + ACQUIRE rule | 2026-08-27 |
| 36 | Shapeless recipes cataloged via ingredient-index encoding; scarce-first pattern resolution | 2026-08-27 |
| 37 | Bow ranging over USE: hold-draw semantics amend decision 14; melee ranking suppressed while ranging | 2026-08-27 |
| 38 | Grammar fold: /actions/ retired, write addresses the noun; read re-homed to documents; ls universal | 2026-08-28 |
| 39 | Directed engagement (/entities/<id>/attack task) + batch craft chains (harness plans, bot batches) | 2026-08-28 |
| 40 | Attack kill-confirmation: health-zero sighting = SUCCESS (amends 39) | 2026-08-28 |
| 41 | Architecture wave: package cycles dissolved, tickOnce single home, menu kind single table | 2026-08-28 |
| 42 | Audit completion round: cellOf/RangedLoadouts/TargetTracker extractions, controller constructors 4->2 (amends 41) | 2026-08-28 |
| 43 | stop-contract fix: /bot stop sums all task handlers; H1 api/ thickness census closed | 2026-08-29 |
| 44 | Ponytail re-audit: ReflexSeat folded into ReflexMissionSeat; gametest rig helpers absorbed | 2026-08-29 |
| 45 | Rulings + engine batch: issue 0016 wired, HungryProcess/eat bugs fixed, PIT gates 150/200 | 2026-08-29 |
| 46 | Anvil/XP surface + sneak steering gate + combat attributes + dig tool awareness; 52 gametests green | 2026-08-29 |
| 47 | Mechanics QA execution: engine suite 67 green (two consecutive runs), DigExecutor harvest gate fixed, gametest receipts + status currency line | 2026-08-30 |
| 48 | Ranged/survival QA batch 1: facade getItemBySlot/getProjectile bridging (private-field trap family), eye-line ballistics, bobber Vec3; 72 green twice | 2026-08-30 |
| 49 | Ranged/survival batch 2: rear-shield direction gate, bow tap band, shears select; rescue covered by existing pins, shield warmup N/A in 1.20.1; 75 green | 2026-08-30 |
| 50 | Boundary-D honesty round, receipt-closed: resetAt epoch allocator (EventEpochStore SavedData) + BotChunkTicket forceload; C2b/C2-post red->green live | 2026-08-31 |
| 51 | Combat range routing stateful + weapon-aware: committed draws finish through band flips, bow answers point-blank when unarmed of better melee, bow-only opens at standoff (amends 37) | 2026-09-01 |
| 52 | Reactive shield: WorldView.getProjectiles + ProjectileThreats geometry; combat order + hit-course flight + carried shield preempts both attack paths; melee hold-item gate closes the bow/shield-held press race | 2026-09-01 |
| 53 | Async planner verdict weight: PathResult.drainedOpenSet separates drained open sets (NO_PATH) from budget cuts and failed futures (CUT = re-ask); starved-worker flake root-caused | 2026-09-01 |
| 54 | Goal vocabulary growth: GoalRange(center, min, max) band predicate + band-edge heuristic in Goals.heuristicOf; DefendProcess/AttackProcess ranged standoff migrates from GoalNear(10) to GoalRange(8,12) so a closing target triggers backward replan (closes issue 0018, amends 51) | 2026-09-01 |
| 55 | Core nullness strategy: six-site get-deref audit (temporal/structural/guarded invariant classes), JsonFields.require convergence for JSON codecs, SurvivalReflexLayer data-dependency refactor, entrySet rewrites, GetDerefGateTest regression guard (api/+core/ scope, zero exemptions at launch); CI static-analysis required-check registration (outside-repo-unverifiable, one-time human Settings verification) | 2026-09-01 |
| 56 | Steering frame decoupling + band-aware progress: MOVE decomposition against a BodyYawSource (strafe/backpedal kiting when the combat aim owns ROT), Goals.distanceOf feeds fuse criterion 2 + NO_PATH witnesses; amends 54's environmental misattribution | 2026-09-01 |
| 57 | Pooled-run hostile leaks: wall-less shared template + 5-block structure spacing + tickPresence recruitment funnel leaked hostiles through later scenarios' 1-wide geometry (the sneakcrawl jam); leak-site tail discards + per-tick foreign sweep where the passable geometry is one cell wide; failed-scenario leaks linger until a later sweep (no pass/fail listener in 1.20.1) | 2026-09-01 |
| 58 | DefendProcess mid-fight stance re-route: the ranged-vs-melee decision is re-derived every engaged tick from the live loadout through the same predicate family as engage; ranged-typed target with a vanished loadout refuses rather than unarmed-chases; target identity stays locked | 2026-09-01 |
| 59 | Static-analysis meta-layer: canary bidirectional pinning for three text-scan gates (ZeroMcImport commented-import + getderef simple-arg + EnglishOnly CJK; nested-arg get-deref pinned as must-not-match blind spot); SpotBugs 4.10.4 NP verified silent on assignment-then-deref (injection probe), NullAway-core deferred with triggers; exemption registry triaged to three-element discipline (justification + target + permanent/trigger status), InMemoryEventQueue AT_NONATOMIC trigger tied to off-thread dispatch path changes | 2026-09-01 |
| 60 | NullAway-core expansion: full @Nullable annotation alignment across 32 core/ files (97 initial warnings, 0 after paydown), AnnotatedPackages api+core at ERROR severity; null defense topology closed (chained get-deref = GetDerefGateTest default test, assignment-then-deref = NullAway -Plint, constructor entry = record compact constructors, cross-process boundary = api/ type contracts). B2 symmetric version pinning (errorprone plugin 5.1.0, error_prone_core 2.42.0, nullaway 0.11.2). ConcurrencyPrimitiveGateTest: core/ single-threaded by policy, synchronized/volatile/concurrent.* references gated with canary set-equality, sole pre-existing AtomicLong replaced with long[] holder (zero exemptions) | 2026-09-01 |
| 61 | Directed taming: the /entities/<id>/tame task verb over the taming.chain face, engine-proven. Intent.InteractEntity(entityId) on the INTERACT channel is the use-on-entity press (decision 25 one-shot family's entity-direction sibling of InteractBlock; USE stays entity-melee); the executor resolves the claim UUID against the server level, gates the shared 3.0 interaction reach, and rides Player.interactOn, pressing by id not crosshair. Overrides gains Tame{targetId, typeKey}, emitted only after a verified sighting so TameFoodCatalog picks the species' tame item with zero world scans; verdict is the snapshot's tamed fact (ledger 40 directly-judged shape) with typed refusals NOT_TAMEABLE / ALREADY_TAMED / NO_TAME_ITEM / TARGET_DEAD; the 12-tick press cadence doubles as the sit-toggle guard via process-before-behavior retirement of the tamed directive. Attribution repair riding the arc: BotProcess.onTick is @Nullable (null = the post-terminal return, not a bug) and HungryProcess's post-failure terminal hold re-pinned from the 20b200d NullAway-init regression | 2026-09-02 |

**CI enforcement (outside-repo-unverifiable):** the static-analysis job
(`.github/workflows/ci.yml`, runs `qualityCheck -Plint` on every push and
pull_request) is asserted to be a required branch-protection check. Branch
protection lives in GitHub Settings, not in any git object, so this
assertion cannot be proven from repository content. Verification is a
one-time human check of the Settings/Branches page. Recorded here so a
future agent does not infer "CI runs it" from ci.yml alone and mistake
presence for enforcement. Decision 55 registers the same fact.

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

Related: [Architecture Overview](overview.md), [ADR index](../decisions/)