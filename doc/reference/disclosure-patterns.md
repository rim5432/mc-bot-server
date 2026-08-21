---
title: Numen Disclosure Pattern Notes (Design Reference)
last_verified: 2026-08-21
covers: []
---

# Numen Disclosure Pattern Notes (Design Reference)

Study notes on the **disclosure layer** of
[Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen) —
the seven-component system that handles "what the bot tells the world".
This is a focused distillation of one slice of Numen; for the overall
architecture (pathing, entity, task model, etc.) see
[numen-notes.md](numen-notes.md).

The disclosure layer is where the bot speaks. Decisions taken here
determine what an external harness can know about the bot, in what
order, under what backpressure, and across what failure modes. The
patterns in this doc are the reference for our boundary-D protocol —
see [boundaries.md](../architecture/boundaries.md) Boundary D row.

## Provenance

| | |
|---|---|
| Repo | `https://github.com/Dwinovo/minecraft-numen` |
| Commit read | `8a94cfd6c4589d1247cda093016e7f5b221282e7` |
| Read date | 2026-08-21 |
| Local mirror | `D:/_numen_ref` |
| License | Same as Numen: LGPL-3.0 (core) + MIT (api) + ARR (assets). See [numen-notes.md](numen-notes.md#license-caution) for the clean-room rule |

## Scope

This doc covers:

- The seven components that make up Numen's disclosure layer
- The three-channel separation principle (event stream / task result / state snapshot)
- The data-driven type table (5 fields per type, register at init)
- Three loss-prevention invariants (timestamp, no-offline-loss, drop-accounting)
- The `shouldDrain` discipline (gate is a question, not an action)
- The "urgent" definition that makes push-vs-pull work
- The multi-holder lock and the `takeWhile` drain ordering
- The state-push granularity rule ("model-relevant only")
- Two protocol-level details that are not in Numen: sync error vs async execution error; bot-restart `resetAt`

It does NOT cover:

- Pathing internals — see [numen-notes.md §15-21](numen-notes.md#15-pathing-3-way-split-with-the-algorithm-decoupled-from-the-scheduler)
- Task scheduling / Process lifecycle — see [numen-notes.md §12-13](numen-notes.md#12-task-model-3-layers--4-result-states-not-priority-numbers)
- Fake-player engineering — see [numen-notes.md §2-6](numen-notes.md#2-server-side-fake-player--serverplayer-subclass-not-a-custom-mob)
- Baritone equivalents — see [baritone-notes.md](baritone-notes.md)

## Component overview

| Component | Lives in | Role | Reuse value for us |
|---|---|---|---|
| `EventQueue` | `api/common/event/EventQueue.java:40-342` | The client-side input queue (owner words + world events, same pool) | High — backpressure + drain + locking semantics |
| `EventTypes` | `api/common/event/EventTypes.java:23-106` | The type table — every entry's id is looked up here for rendering, chat preview, interrupt semantics | High — `clearedByInterrupt` is data, not code |
| `EventOutbox` | `api/common/entity/EventOutbox.java:35-136` | The server-side persistent queue (`SavedData`) for owner-offline scenarios | Pattern, not direct copy — we have harness-always-on, not owner-offline |
| `NumenEvents` | `api/common/event/NumenEvents.java:128-177` | The single entry point for world events (Kind enum + auto timestamp + online/offline dispatch) | High — "one question can have only one answer" |
| `TaskResult` | `api/common/task/TaskResult.java:31-89` | The 4-flag result envelope sent to the LLM agent loop | Very high — direct copy |
| `CompanionStateWatch` | `api/common/src/main/java/com/dwinovo/numen/entity/CompanionStateWatch.java` | State push: change detection + throttling + only model-relevant fields | High — the granularity philosophy |
| `JsonlJournal` | `api/common/event/JsonlJournal.java:26-89` | Snapshot-rewrite JSONL persistence for the `EventQueue` | Pattern — fault tolerance, not the file format |

## Key findings

### 1. The three-channel split is mandatory, not a stylistic choice

This is the single most important design decision in the disclosure
layer. Disclosure is not one interface; it is three semantically
distinct channels:

| Channel | Sync model | Replayable? | Use case |
|---|---|---|---|
| **Event stream** (`NumenEvents` -> `EventQueue` / `EventOutbox`) | Async, batched, gate-decided consumption | Yes (via `JsonlJournal` and `EventOutbox`) | Things that happened: task finished, dimension crossed, hunger, owner hurt |
| **Task result** (`TaskResult`) | Sync return to the LLM agent loop; rendered as the `content` of a `role:tool` message in the next chat completion request | No (one-shot, terminal) | The result of a specific command the harness just issued |
| **State snapshot** (`CompanionStateWatch`) | Server-push, client-cached, rendered into the prompt | No (cache, not log) | The current world state — pose, inventory, effects |

The class doc on `NumenEvents` states the rule:

> "The single entry point for world events. Persistent task chains, task completion, dimension crossings, third-party content packs — everything writes here. One class, one method." *(translated)*
>
> "Every extra emit path multiplies the policy decisions ('timestamped or not?', 'owner offline — now what?', 'buffer or not?'), and they inevitably fork: for the same situation ('task finished while the owner is offline') one path drops it outright while another keeps six entries. One question can have only one answer, so there is only this one entry point." *(translated)*

The cost of merging the three channels is spelled out in the source:
"one problem can only have one answer". If event-stream and
state-snapshot are merged, the answer to "what is the bot doing right
now" vs "what just happened" diverges. If event-stream and
task-result are merged, the answer to "did the command I just sent
succeed" gets conflated with "what random thing happened in the
background".

For us, the three channels map to:

```
EventBatch   = event stream  (Numen's NumenEvents + EventQueue + JsonlJournal)
TaskResult   = task result   (Numen's TaskResult, ships through the event stream as TASK_COMPLETED)
BotState     = state snapshot (Numen's CompanionStateWatch)
```

The three semantic shapes travel over two protocol surfaces: a
**polled event stream** (events + task results, harness pulls on its
own schedule) and a **server-pushed state channel** (changes pushed
on transition, harness caches). Two surfaces, three semantic shapes,
no mixing.

### 2. The type table is data, not code

`EventTypes` defines one record per type:

```java
public record Type(
    String id,                          // type id, written to the persisted entry
    Function<String, String> toModel,         // rendered into the user message
    Function<String, String> chatPreview,     // rendered into chat; null = no chat
    boolean clearedByInterrupt,  // owner-stop wipes this? (true for instructions, false for facts)
    boolean fromOwner            // owner's words or world's events? determines ordering
) {}
```

Five built-in types ship in the static block: `query`, `event`,
`compact`, `clear`, `goal`. Third-party types can be registered at
mod init. Unknown types fall back to a `UNKNOWN` sentinel that
renders raw text to the model and skips chat — so a "forgot to
register" bug surfaces as one extra line, not as silent data loss.

The two fields that buy the most are `clearedByInterrupt` and
`fromOwner`.

**`clearedByInterrupt`** — what gets cleared are the superseded
instructions, not the facts *(translated)*.
QUERY/COMPACT/CLEAR/GOAL clear on owner stop (they're instructions).
EVENT does not clear (it's a fact). This rule lives in the table,
not in code. Adding a new type answers the question with one row,
not with a new `if` branch in the queue.

**`fromOwner`** — the world's affairs go into `<events>` sorted by
time; the owner's words always go last *(translated)*.
The render pipeline uses this to separate the two buckets in
the user message: the model reads "first see clearly what happened,
then what the owner wants". The order is a property of the type,
not of the queue, not of the sender.

For us, the type table directly maps to the `kind` field of our
`BotEvent` and the dispatch methods on `BotEvents`. The 5-field
shape (render / chat-preview / interrupt / owner-side) is the
design pattern; the specific field names are ours to adapt.

### 3. Three loss-prevention invariants

These three invariants are the reason the disclosure layer survives
the failure modes the project has actually seen in production.

**Invariant 1: every event carries a game-time timestamp.** Format:
`<event kind="..." day="N" t="HH:mm">`. The class doc on
`NumenEvents.compose`:

> "The model can judge staleness itself (the iron mined before death drops at the death site); we do not have to clear its inventory for it." *(translated)*

The `compose(dayTime, ...)` signature accepts `dayTime` (not
`MinecraftServer`) **so the client can use the same code path for
death events** — at the moment of death the server body is already
gone, so the client composes the event with the timestamp it last
knew. Both sides share this one constructor so that the event that
most needs a timestamp is never the one that lacks it *(translated)*.

For us: every `BotEvent` carries a `day` + `t` pair (or a single
`gameTimeTicks` long — to be decided). The bot stamps it, never the
harness, never the render path.

**Invariant 2: events do not disappear on the writer's own failure.**
The class doc on `EventOutbox`:

> "The brain runs on the owner's client; the moment the owner logs off, the client-side queue no longer exists. But her body keeps working on the server: tasks finishing, mobs attacking, dimensions crossing. These things need somewhere to lie in wait until the owner returns — otherwise 'I finished mining for you', the single most worth-reporting event, is precisely the one most likely to be lost." *(translated)*

`NumenEvents.emit` writes to `EventOutbox` (server-side `SavedData`)
when the owner is offline. When the owner logs back in, `take(uuid,
now)` returns the **original `Entry` records** — not pre-rendered
strings: "rendering collapses type and timestamp into a single string,
so the receiving end can no longer know 'this happened three hours
ago'" *(translated)*.

For us, the failure mode is different: the harness is always on,
but the bot may restart (server reload, JVM OOM, mod update). The
in-memory `EventQueue` is lost on restart. The protocol-level rule
is "events do not survive bot restart" — see Finding 10 for the
`resetAt` field that surfaces this to the harness.

**Invariant 3: drops must not be silent.** `EventQueue.push(...)`
enforces `DEFAULT_CAP = 200` and counts dropped entries. On the
next drain, a synthetic event ("~N things could not be recorded",
translated) is inserted into the batch. The class doc on `EventQueue`:

> "Dropping is allowed; silent disappearance is not. Locks may be held a long time (an external brain can run all day), and without a cap the queue grows until it bursts the context window — but the owner must know whether what they are seeing is the whole or a fragment." *(translated)*

For us: our `EventBatch` carries a `droppedCount` field. When
`droppedCount > 0`, the bot also inserts a synthetic
`EVENT_DROPPED` event into the batch describing the loss. The
harness sees the loss without having to track it itself.

### 4. The gate is a question, not an action

`EventQueue.shouldDrain(now, level)` is a pure function of state.
Three rules, no exceptions:

```
urgent present  + not locked  -> drain
urgent present  + locked      -> wait for lock
no urgent                      -> check size threshold OR oldest age threshold
```

The class doc on `EventQueue`:

> "'Draining' means 'leaves when due', not 'emits immediately'. `shouldDrain` only reads state; ask it repeatedly and the answer stays consistent. If the upper layer cannot drain for protocol reasons (a user message cannot be inserted in the middle of assistant `tool_calls`), it simply asks again next tick — there is no such thing as a 'missed drain', so there is no need to remember an error-prone 'I wanted to drain' state." *(translated)*

`now` is **wall-clock time** (`System.currentTimeMillis`), not game
tick. The class doc:

> "Game time freezes when a single-player world exits; computing 'how long has this been waiting' from it would conclude that nothing ever ages." *(translated)*

For us: `shouldDrain(now, level)` becomes a method on our
`EventQueue` (or an equivalent gate function in the harness-facing
facade). The `level` parameter (1-10) is the harness's "how
aggressive do you want the bot to be about reporting" knob —
`level=1` is "tell me everything", `level=10` is "batch up to 10
items or 33 minutes of age". The function signature is the
protocol; the threshold and wait math are implementation.

### 5. Multi-holder lock; queue knows nothing about meaning

`EventQueue.lock(holder) / unlock(holder)` accept opaque string
tokens. The queue maintains a `Set<String>` of holders; `locked()`
is true iff the set is non-empty. The class doc:

> "The lock governs **outflow only** — not intake, and not clearing; whether the owner pressed stop-and-clear has no bearing on whether she can still speak." *(translated)*
>
> "The queue does not know what those strings mean (she died? the external brain took over?) — only that someone is holding a lock." *(translated)*

The point: a complex client (multiple consumers, lockable per
consumer) can use opaque tokens to coordinate; the queue doesn't
need to know what they mean. Adding a new consumer type is a
one-line `lock(myConsumerId)` call, not a queue API change.

For us: we may not need multi-holder locking in Stage 0 (one
harness, one consumer per bot). But the contract should reserve
the door: `lock(holder) / unlock(holder)` is part of the
`EventQueue` API even if Stage 0's reference implementation only
ever holds one token. Adding it later as a `lock` parameter is a
protocol change; adding the method as no-op-compatible is not.

### 6. `takeWhile` drains in order, stops at first non-text entry

`EventQueue.takeWhile(predicate, now)` takes from the head while
the predicate holds; the first entry that fails the predicate and
everything after it stay in the queue. The class doc:

> "The queue therefore drains **in order**: when it hits an entry that must not be treated as text (e.g. memory compaction), everything before it goes first and that entry waits at the head for the next safe point. No queue-jumping — so there is no need to answer 'does it jump the queue?' for every type added in the future." *(translated)*

Why this matters: the model can emit a `COMPACT` or `CLEAR` event
to ask the bot to compact its memories. The queue drains up to that entry,
sends the prefix as the next round's input, and leaves the
compact/clear at the head to be handled after the prefix is
consumed. No priority order between types needs to be negotiated
at queue time.

For us: the equivalent is `EventBatch.takeWhile(predicate)` — the
harness's pull can specify "give me everything until the first
`EVENT_COMPACT`". The bot can implement this without knowing the
harness's logic.

### 7. Per-kind urgency is hard-coded in the dispatch methods

`NumenEvents` has 8 hard-coded `Kind` values, each with a default
urgency decided by the dispatcher:

| Kind | Urgent by default | Notes |
|---|---|---|
| `HUNGRY` | always | Bot won't eat on its own; `hungerReported` latch in `NumenPlayer` prevents spam |
| `BODY_LOG` | never | Steady-state body-maintenance narration |
| `DIMENSION_CHANGE` | non-urgent | Crossing worth knowing, not worth interrupting |
| `DEATH` | n/a | Composed on client side, body is gone |
| `TIMER` | non-urgent | Reminder, not "thing done" |
| `WOKE` | non-urgent | Polled by `NumenPlayer.pollWokeUp` once per tick |
| `OWNER_HURT` | by HP threshold | Safe zone = just inform; danger zone = urgent |
| `TASK_FINISHED` | by status | `done` / `failed` / `timeout` = urgent; `stopped` = not urgent — owner knows |

The defining line:

> "Urgent is not 'important' — it is how necessary an item's freshness is to the model's current decision." *(translated)*

Or, more directly, the dispatch doc:

> "`true` = she does not yet know this, and what she is currently doing is therefore wrong. On reaching the client queue, urgent immediately carries off everything accumulated and opens a new round; non-urgent accumulates until there is enough of it, it has aged enough, or an owner message arrives to piggyback on." *(translated)*

For us: per-kind urgency is hard-coded in our `BotEvents.emit(...)`
family of methods (e.g. `gotHungry(companion, foodLevel)` always
emits urgent; `bodyLog(companion, text)` never does). The urgency
flag goes on the `BotEvent` envelope. The harness's `shouldDrain`
consults it.

### 8. State push granularity: "model-relevant only"

`CompanionStateWatch` (the state-push component) decides what to
push and when. The rule: only fields the model actually reasons
about get pushed. The class doc:

> "Compare only 'which item, how many' — durability and enchantments stay out of the comparison, otherwise every pickaxe swing counts as a change." *(translated)*

And the time-based counter example:

> "Effects compare only 'which types, which amplifiers'; remaining time decreases every tick — using it as the change criterion would mean pushing a packet every tick. Durations are still sent; the client ticks them down locally." *(translated)*

The principle: change detection uses **discrete categorical
fields** (what items, what effect types, what levels), not
**continuous or fast-moving fields** (durability, enchantment
progress, effect remaining ticks). The harness-side renderer can
compute the continuous values locally if it needs them.

For us: the `BotState` interface lists the categorical fields.
Durability / enchantment / effect remaining ticks are deliberately
absent. The state-push component change-detects on the categorical
fields and emits `STATE_PUSH` events on transitions.

### 9. `TaskResult` is 4 orthogonal flags, not "did it work"

`TaskResult` is a record:

```java
public record TaskResult(
    boolean success,         // did it achieve the goal?
    String message,          // short human-readable summary
    boolean timedOut,        // ran out of time
    boolean interrupted,     // cancelled (e.g. owner interrupt)
    Map<String, Object> data // task-specific structured payload
) {}
```

The class doc:

> "**'Did it work' is not one question.** A move_to can succeed, fail (unreachable), run out of time, or get cancelled — and the model's next move differs in each case, so each gets its own field rather than being folded into a message the model has to parse."

> "`success` ≠ `!timedOut && !interrupted`."

`toJson()` renders field names verbatim to match common
tool-result conventions — a model trained on standard tool-result
shapes can read it without a custom system prompt.

The four flags are **orthogonal**. A task can be `success=true`
and `timedOut=false` (it succeeded), or `success=false` and
`timedOut=true` (it ran out of time), or `success=false` and
`interrupted=true` (owner stopped it), or `success=false` with
both `timedOut` and `interrupted` false (it failed for some other
reason). Folding these into a single status string would force the
model to parse the message to distinguish them.

For us: the `TaskResult` record shape is a direct copy. The four
flags map to our boundary-D TaskResult; the model reasons about
each flag independently.

### 10. The protocol-level details Numen does not surface

Numen's design handles the event-channel side well. Two protocol
details are not in Numen but are required by our boundary-D
contract. We add them here so future readers of either doc see
the complete picture.

**Detail 10a: sync error vs async execution error.** A command's
failure has two layers:

- **Structural error** (the caller's JSON is wrong: verb is
  unknown, target type is malformed). The harness needs to know
  **now**, not after waiting for an event. Returned synchronously
  from `submit()`.
- **Execution error** (the command is well-formed but the current
  world state can't execute it: target occupied, path
  unreachable, resource missing). The harness wants to combine
  this with other context before deciding the next move. Returned
  as an event (`TASK_REJECTED(taskId, reason)` or
  `TASK_FAILED(taskId, TaskResult)`).

Numen handles structural errors in the LLM tool-call
infrastructure (the harness's JSON validator catches them). We
surface them explicitly because our boundary D is consumed by
non-LLM harnesses (programmatic drivers, CLIs) that need the
sync-error path:

```java
public sealed interface SubmitResult {
    record Ok(String taskId) implements SubmitResult {}
    record Rejected(String reason) implements SubmitResult {}  // structural, sync
}
```

Everything else (accepted, running, completed, failed, stuck, timed
out) flows through the event stream.

**Detail 10b: bot restart `resetAt`.** Numen's `EventOutbox`
exists because the **owner** may be offline while the **body**
keeps working. Our case is different: the **harness** is always
on, but the **bot** may restart (server reload, JVM OOM, mod
update). The in-memory `EventQueue` is lost on restart.

The `EventBatch` carries a `resetAt: long` field. The harness
sees `resetAt` change and reconciles via the `BotState` snapshot.
The protocol-level invariant: **events do not survive bot
restart**. This must be stated explicitly so harness code does
not silently assume "events are complete" across a restart.

(Stage 0 ships in-memory only with `resetAt`. Stage 1+ adds
`JsonlJournal` persistence with replay — the workplan tracks
this. Either way, the harness's view of "events since
`sinceEventId`" is bounded by the most recent `resetAt`.)

## Mapping to mc-bot-server

The three-channel split is what carries across — not the specific
Numen class names. The data-driven type table, the three
invariants, the gate-as-question, the sync-error / async-execution
split, the state-push granularity rule — these are the patterns we
copy. The classes and method names are ours to design.

| Numen component / pattern | Our equivalent | Where it lives |
|---|---|---|
| `EventQueue` (backpressure + lock + drain) | `EventQueue` (or `EventChannel`) | `boundaries.md` Boundary D protocol; implementation in workplan |
| `EventTypes` (5-field table, register at init) | `BotEventKind` (or equivalent) | same |
| `EventOutbox` (SavedData, owner-offline) | Deferred — we don't have the owner-offline case | out of Stage 0 scope |
| `NumenEvents` (single entry point) | `BotEvents.emit(...)` family | workplan |
| `TaskResult` (4 flags + data) | `TaskResult` record | workplan |
| `CompanionStateWatch` (push, change-detected, model-relevant) | `BotState` interface + state-push event | workplan |
| `JsonlJournal` (snapshot rewrite, fault-tolerant) | Deferred to Stage 1+ | workplan |
| `EventBatch` with `droppedCount` + `resetAt` | Stage 0 contract | `boundaries.md` Boundary D protocol |
| `SubmitResult = Ok(taskId) \| Rejected(reason)` | Stage 0 contract | `boundaries.md` Boundary D protocol |
| Gate-as-question (`shouldDrain(now, level)`) | `EventQueue.shouldDrain(now, level)` | workplan |
| Multi-holder lock | Reserved in `EventQueue` API; Stage 0 single-holder reference impl | workplan |
| `takeWhile(predicate)` | `EventBatch.takeWhile(predicate)` | workplan |

## Cited files (read from `8a94cfd6c4589d1247cda093016e7f5b221282e7`)

```
api/common/src/main/java/com/dwinovo/numen/event/
  EventQueue.java
  EventTypes.java
  JsonlJournal.java
  NumenEvents.java

api/common/src/main/java/com/dwinovo/numen/entity/
  EventOutbox.java
  NumenPlayer.java (for hungerReported latch + pollWokeUp polling patterns)
  CompanionStateWatch.java (for state-push granularity)

api/common/src/main/java/com/dwinovo/numen/task/
  Task.java
  TaskDispatch.java
  TaskResult.java
  TaskRecord.java
  AbstractCompanionTask.java (in core/, but uses the task model)

api/common/src/main/java/com/dwinovo/numen/agent/tool/
  ServerToolTransport.java (for the request-response shape that TaskResult ships through)
```

## Maintenance

After verifying the content against a newer Numen commit:

```bash
python tool/mcbot_tool.py doc touch disclosure-patterns
```

If we add or change the three-channel protocol, update both this
doc and `boundaries.md` Boundary D row.
