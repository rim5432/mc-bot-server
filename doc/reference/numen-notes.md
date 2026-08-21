---
title: Numen Architecture Notes (Design Reference)
last_verified: 2026-08-21
covers: []
---

# Numen Architecture Notes (Design Reference)

Study notes on [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)
as a design reference for this mod's device layer, specifically the **non-LLM
kernel** (pathing, event bus, task model, fake-player engineering). LLM-side
content (agent/ tool/ skill packages) is excluded from scope — see
[ADR-0002](../decisions/0002-capability-model-task-arbiter.md) for why we keep
the harness outside the JVM.

## Provenance

| | |
|---|---|
| Repo | `https://github.com/Dwinovo/minecraft-numen` |
| Commit read | `8a94cfd6c4589d1247cda093016e7f5b221282e7` |
| Read date | 2026-08-21 |
| Local mirror | `D:/_numen_ref` |
| Target MC | 1.21.1 |
| Loader | **multiloader** — Fabric Loom + NeoForged ModDev (`build.gradle:3-7`) |
| Active ref | Baritone (we already track this in [baritone-notes.md](baritone-notes.md)) — read both side by side |

## License caution

Three separate licenses, not one:

| File | Scope | License |
|---|---|---|
| `LICENSE` | core + main mod code | **LGPL-3.0** |
| `LICENSE-API` | **only** `com.dwinovo.numen.api` subpackage | MIT |
| `LICENSE-ASSETS` | art / visual / audio assets | All Rights Reserved |

Learning the architecture is free. Copying code verbatim would impose LGPL
obligations. Treat all notes below as clean-room concept references only —
mirror the patterns, do not transcribe files. (Same rule as
[baritone-notes.md](baritone-notes.md#license-caution).)

## Repository layout

```
build.gradle          <- multiloader: fabric-loom-remap 1.14.10 + moddev 2.0.141
api/                  <- pure interfaces (entity/, event/, task/, agent/, network/)
  common/             <- platform-agnostic api
  fabric/, neoforge/  <- per-loader thin bindings
core/                 <- the non-LLM engine (this is what we study)
  common/             <- shared: pathing/, combat/, scan/, task/, tools/, debug/, init/
ai/                   <- LLM side (out of scope for us)
ui/                   <- client UI
```

`api/common` is what our `api/` package is meant to mirror: **pure interfaces +
DTOs, no Minecraft types leak across the harness boundary** (which lines up
with our [ADR-0002 §6](../decisions/0002-capability-model-task-arbiter.md#6-api-purity)).

## What we read (and what we ignore)

| Package | Read it? | Why |
|---|---|---|
| `api/common/entity/` | YES | The full server-side fake-player engineering we had only hand-waved |
| `api/common/event/` | YES | The event bus + JSONL journal + offline outbox we want for replay debugging |
| `api/common/task/` | YES | The task model: 3 layers + 4 result states + frozen lifecycle |
| `api/common/network/payload/` | YES (skim) | Network protocol shape; we need the same packet design |
| `core/common/pathing/` | YES (deep) | A* + planner pool + executor; we adopt this in Phase 2 |
| `core/common/task/base/` | YES | `AbstractCompanionTask` skeleton is a perfect template for our `BotProcess` |
| `core/common/scan/` | YES | Block scanner, ring spiral, target index — useful for the perception layer |
| `core/common/combat/` | YES | Attack plan / haven / loadout / swing — same shape our `CombatBehavior` needs |
| `core/common/debug/` | YES (skim) | PathDebug + PathDebugRenderer — our visualization reference |
| `core/common/tools/` | NO | LLM tool implementations, redundant for us |
| `ai/`, `ui/`, `agent/` | NO | LLM client + UI; out of project scope |

## Key findings

### 1. Module isolation is enforced at the Gradle level

`api/common/` is a separate Gradle subproject. `core/common/` depends on
`api/common` but **not the other way** (this is what the
`// mod_id differs per mod family` comment in `build.gradle:9-15`
*(translated)* is for — preventing `project(':api:common')`
from accidentally resolving back to `:core:common` and creating a cyclic
task graph). Our project is single-loader single-subproject today, so the
isolation is conceptual; if we ever publish `mcbotserver-api` separately
to let third-party harnesses link against us, this is the pattern to follow.

### 2. Server-side fake player = `ServerPlayer` subclass, not a custom Mob

`NumenPlayer extends ServerPlayer`
(`api/common/src/main/java/com/dwinovo/numen/entity/NumenPlayer.java:33`).
The class doc states the reasoning verbatim:

> "Replaces the old custom `NumenEntity` Mob so the companion is a first-class
> player — native interaction/combat code paths (universal mod compatibility),
> its own player inventory, and free chunk loading + playerdata persistence by
> virtue of being a list-resident player."

For us this matters when/if we ever do server-side fake players (not in
Phase 0 plan, but the door is open). The engineering decisions we get for
free by extending `ServerPlayer`:

- Native interaction / combat code paths
- Player inventory
- Chunk loading + playerdata persistence
- A `connection` slot that vanilla's `ServerPlayerGamePacketListenerImpl`
  keys off of (we'd still need `FakeConnection` — see below)

### 3. `FakeConnection` is the non-obvious core of fake-player engineering

`api/common/src/main/java/com/dwinovo/numen/entity/FakeConnection.java:42-133`

Two non-obvious engineering decisions, both required for `placeNewPlayer` to
accept the connection:

| Decision | Why |
|---|---|
| `super(PacketFlow.SERVERBOUND)` | `validateListener` rejects a CLIENTBOUND mismatch; a server-side player connection is **serverbound** (the *client* sends to it) |
| `new EmbeddedChannel(this)` in ctor | `setupInboundProtocol` inside `placeNewPlayer` configures the channel pipeline, which NPEs on a channel-less connection. Registering this Connection as the embedded channel's handler fires `channelActive` → sets `this.channel`, so the pipeline setup has a channel to work on |

Outbound packets are still discarded by the `send` override (the embedded
channel is never written to, so it never buffers/leaks). Keep-alive is
neutralised by no-op `disconnect` — a fake player never answers keep-alive,
so vanilla's timeout would fire otherwise.

**The `getRemoteAddress() = LOOPBACK` trick is not cosmetic.** It is a
compatibility patch for ecosystem mods that **brute-cast**
`SocketAddress → InetSocketAddress` in login event listeners (IP whitelists,
chat bridges, login firewalls). The cast fails on the netty `LocalAddress`
that vanilla uses for in-memory connections, throwing inside the player-join
event and **locking out real players** alongside the fake one. The class
doc puts it bluntly: "this crashes only appear when other mods are
installed; you can't reproduce it in your own clean test setup."

`@Internal` annotation on the class signals that it is **not** part of the
public API — the LOOPBACK detail might change without notice.

### 4. `InputDriver` is the smallest possible input emulation

`api/common/src/main/java/com/dwinovo/numen/entity/InputDriver.java:21-76`

Six methods, all under 10 lines each. It writes:

| Method | What it sets |
|---|---|
| `stepToward(p, target, sprint)` | `p.yRot/yHeadRot` (yaw) + `p.setXRot(12.0f)` (look at ground, not last `lookAt`) + `p.zza=1, p.xxa=0` + `p.setSprinting(...)` |
| `lookAt(p, point)` | `p.lookAt(EYES, point)` (yaw + pitch) |
| `jump(p)` | `p.jumpFromGround()` (on land) or `p.setDeltaMovement().add(0, 0.04, 0)` (water/lava, per-tick stroke — fake player has no client to do the "hold jump to swim" key) |
| `sneak(p, on)` | `p.setShiftKeyDown(on)` |
| `halt(p)` | zeros all locomotion input; **must be called every tick while idle** — last forward input would otherwise keep the body walking |

The class doc is the key insight: "a fake player has no client to send
movement packets, so the server's own player tick runs `travel` against
these inputs and nothing overrides the resulting position — that is what
makes input-driving a server-side body work."

This is the **substitute for vanilla client key packets** in a server-side
body. The Phase 0 `Actor` (single-writer movement handover, see
[architecture/overview.md](../architecture/overview.md#runtime-layering))
talks to this kind of surface, not to anything higher-level.

### 5. `CompanionChunkLoader` is a region ticket with self-cleaning timeout

`api/common/src/main/java/com/dwinovo/numen/entity/CompanionChunkLoader.java:34-97`

Two pieces working together:

1. `ChunkMapCompanionMixin` forces `skipPlayer == true` for companions,
   stripping the (wasted) client chunk-tracking and packet machinery. As a
   side effect this also strips the player loading ticket.
2. `CompanionChunkLoader.refresh(companion)` then re-adds a **bounded
   region ticket** (5×5 chunk pad at ENTITY_TICKING level), with a
   **40-tick self-expiring timeout**, re-stamped every 20 ticks (half the
   timeout) or immediately on chunk crossing. Companions sharing a chunk
   share the same ticket — a departing companion never yanks the pad out
   from under one that stayed.

The "self-cleaning" property is the trick: `DistanceManager.addTicket`
resets the created-tick of a re-added ticket, so a live companion keeps
the pad alive; the instant it stops ticking (dormancy, death, despawn,
chunk unload, even a crash) the ticket simply times out. Nothing is leaked
and nothing has to be remembered and removed.

**For us:** when the WorldView is asked about an unloaded chunk, the
answer is "I don't know" — but "I don't know" cannot be conflated with
"this is air" or the pathfinder will route through unfinished terrain
without realising. The contract for our `WorldView` interface (the
"read half" of the perception/action split) must expose
`isLoaded(pos)`/`getChunkStatus(pos)` explicitly. Mock implementations
default to "all loaded" so unit tests stay simple; the real
implementation never synchronously triggers chunk generation
(see also finding 7 on `LoadedOnlyView`).

### 6. `SafeSpawn` and `CompanionLifecycle` are the two pieces we had skipped

**`SafeSpawn` is package-private and lives next to the entity** it
serves (`api/.../entity/SafeSpawn.java:28-97`). Algorithm: 10 random
attempts in a 7×3×7 box around the anchor, then a deterministic sweep
nearest-first per layer that climbs upward. Every candidate must offer
solid non-hazardous footing, fire/lava-free at feet and head, breathable
head cell, and room for a standing player hitbox. Critically: spots
"hugging the anchor" are rejected so the body isn't shoved into a wall
by entity pushing in narrow spaces.

**`CompanionLifecycle` is the engine/toolpack seam**
(`api/.../entity/CompanionLifecycle.java:20-39`). Three event types:

| Event | Fires on | Listener is invoked on |
|---|---|---|
| `fireRemove` | body leaving world (dormancy, dismissal, death-despawn) | server |
| `fireDeath` | body just died, before corpse is removed | server |
| `fireAbort` | owner interrupted this companion's brain | **client** |

The `fireAbort` asymmetry is the key: a toolpack's abort listener is the
natural place to send its own "stop the body" packet. Listeners are
`Consumer<NumenPlayer>` / `Consumer<UUID>` registered via `onRemove` /
`onDeath` / `onAbort`, held in `CopyOnWriteArrayList`s. The engine
"knows none of this — it is a scheduler and a body, nothing more."

**For us:** the device layer's `BotController` must have explicit
lifecycle hooks for the four states `ALIVE / DEAD / RESPAWNING /
TERMINATED`, regardless of whether we ever do server-side fake players.
The seam belongs to the device layer (server side) and is a *capability
of the device*, not an LLM concern.

### 7. The cache package reveals two view modes that must not be conflated

`core/common/src/main/java/com/dwinovo/numen/core/pathing/cache/`

| Class | Used by | Reads from | Triggers chunk gen? |
|---|---|---|---|
| `LoadedChunks` | A* worker (off-thread) | **snapshot** of chunk-provider state, captured on main thread once per tick | no |
| `CachedNavView` | A* worker (off-thread) | `LoadedChunks` snapshot — live `LevelChunk` section palette for the captured chunk, AIR for non-captured | no |
| `LoadedOnlyView` | `PathExecutor` (main thread, every tick) | `level.getChunkSource().getChunkNow(...)` — in-memory lookup only | **no, never** |

The split is essential:

- `LoadedChunks` snapshot is **immutable** after construction (only the
  shared chunk *contents* are live, the map structure is fixed) — safe to
  hand to an off-thread A* worker. A reader that catches a `RuntimeException`
  from a palette resize (rare main-thread race) yields AIR; the executor
  re-costs against the live world and replans, so a one-off bad cell
  self-corrects.
- `CachedNavView` uses a **single last-chunk pointer**, not a per-cell
  memo. The class doc is explicit: "The previous design used a
  `Long2ObjectOpenHashMap` per-position memo that ballooned to millions of
  entries over a multi-second search, repeatedly resized, and the resulting
  GC churn stop-the-worlds the (integrated) server thread — every entity
  stutters in lockstep." A* expands locally, so consecutive reads land in
  the same chunk 99% of the time; the last-chunk cache is O(1) footprint
  and never allocates on the hot path.
- `LoadedOnlyView` is the executor's safety net: even on the main thread,
  it must not synchronously generate new chunks. A path that extends past
  the loaded boundary would otherwise block the tick thread on chunk gen.

**For us:** the WorldView is a family of views, not a single interface.
The "perception read" the path planner uses (a snapshot, possibly stale)
is a different view from the "perception read" the executor uses (live,
but never blocking). Naming this distinction now prevents us from writing
one interface that pretends to be both.

### 8. `EventQueue` is much more than a JSONL log file

`api/common/src/main/java/com/dwinovo/numen/event/EventQueue.java:40-342`

It is four concerns welded together with a single rule:
**"Now should we drain?"** is a function of state, not an action.

| Concern | API | Behaviour |
|---|---|---|
| **Storage** | `Journal` interface (injected) | JsonlJournal writes a full snapshot rewrite; `Journal.NONE` for things that delegate persistence elsewhere (`SavedData` in server case) |
| **Backpressure** | `push(...)` | `DEFAULT_CAP = 200`. When full, **drop oldest, count dropped**. On next drain, emit one extra "~N things could not be recorded" event so the drop is not silent |
| **Locking** | `lock(holder) / unlock(holder)` | Multi-holder: a set of opaque string tokens, all must be released. Queue doesn't know what any of them mean (dead? external brain took over?) — only that somebody is holding |
| **Drain decision** | `shouldDrain(now, level)` | Pure function. urgent present → true. Otherwise `size >= thresholdOf(level)` or `oldestAgeMs(now) >= maxWaitMsOf(level)`. **`now` is wall-clock**, not game tick — game tick freezes on singleplayer exit, which would make "how long was this lying around" read as zero |
| **Drain execution** | `takeWhile(predicate, now)` | Take from head while predicate holds. Lets the model emit "compact" or "clear" entries that *stop* the drain in a safe spot, so a real event before them gets sent first |

The class doc nails the philosophy *(translated)*:

> "There is no fourth rule. Who sent it, what type it is, what she was
> doing at the time — none of that is looked at."
>
> "**'Draining' means 'leaves when due', not 'emits immediately'.**
> `shouldDrain` only reads state; ask it repeatedly and the answer
> stays consistent. If the upper layer cannot drain for protocol
> reasons (a user message cannot be inserted in the middle of assistant
> `tool_calls`), it simply asks again next tick — there is no such
> thing as a 'missed drain', so there is no need to remember an
> error-prone 'I wanted to drain' state."

The `EventTypes` table is also data-driven: 5 built-in types
(`query`, `event`, `compact`, `clear`, `goal`) with `toModel` /
`chatPreview` / `clearedByInterrupt` / `fromOwner` lambdas, plus a
register-at-mod-init extension point. Unknown types fall back to a
`UNKNOWN` Type that renders the raw text to the model and skips chat — so
a "forgot to register" bug shows up as one extra line, not as silent data
loss.

### 9. `NumenEvents.emit` is the **single entry point** for world events

`api/common/src/main/java/com/dwinovo/numen/event/NumenEvents.java:128-177`

The class doc states the rule *(translated)*:

> "**The single entry point for world events.** Persistent task chains,
> task completion, dimension crossings, and third-party content packs —
> everything writes here. One class, one method."
>
> "**Two things always happen here:** (1) **stamp the timestamp** —
> every event carries the in-game day and time. The model can judge
> staleness itself (the iron mined before death drops at the death
> site), so we do not have to clear its inventory for it;
> (2) **no loss while the owner is offline** — into `EventOutbox`,
> persisted with the save and re-delivered on login. Every kind of
> event enjoys this rule, no exceptions."

The `compose(dayTime, ...)` signature accepts `dayTime` (not
`MinecraftServer`) **so the client can use the same code path for death
events** — at the moment of death the server body is already gone, so
the client composes the event with the timestamp it last knew.

Per-kind urgency is hard-coded:

| Kind | Urgent by default? | Notes |
|---|---|---|
| `HUNGRY` | always | She won't eat on her own; hungerReported latch in `NumenPlayer` prevents spam |
| `BODY_LOG` | never | Steady-state body-maintenance narration |
| `DIMENSION_CHANGE` | non-urgent by default | A crossing worth knowing about, not worth interrupting |
| `DEATH` | n/a | Composed on client side, body is gone |
| `TIMER` | non-urgent | Reminder, not "thing done" |
| `WOKE` | non-urgent | Polled by `NumenPlayer.pollWokeUp` once per tick |
| `OWNER_HURT` | by HP threshold | Safe zone = just inform; danger zone (around the same "vanilla can't move" line as hunger) = urgent |
| `TASK_FINISHED` | by status | `done` / `failed` / `timeout` = urgent; `stopped` (owner-cancelled) = not urgent — they know |

### 10. `EventOutbox` is the server-side persistent queue

`api/common/src/main/java/com/dwinovo/numen/entity/EventOutbox.java:35-136`

Extends `SavedData`. One `EventQueue` per companion UUID. When the owner
goes offline, `NumenEvents.emit` writes here. When they log back in,
`take(uuid, now)` returns the **original `Entry` records** (not
pre-rendered strings — "rendering flattens type and timestamp into a
string, and the receiving end can't tell 'this happened three hours
ago' from 'this just happened'"). `Journal.NONE` is injected so the
state lives in `SavedData` rather than a sidecar file.

`forget(uuid)` is called on companion despawn — entries are dropped
with the body. No "stale outbox" cleanup is ever needed because the
outbox is keyed by companion UUID and the UUID dies with the body.

### 11. `JsonlJournal` is a snapshot rewrite, not an append log

`api/common/src/main/java/com/dwinovo/numen/event/JsonlJournal.java:26-89`

The class doc explains the choice *(translated)*:

> "It has exactly one purpose — inputs that went unconsumed when the
> game closed are still there on next login; the companion does not
> lose its memory. The file is small (a few inputs), and rewriting the
> whole thing spares us an entire append/compact bookkeeping layer
> that an incremental log would need."

Three fault-tolerance rules all written into the body:

1. **Bad lines skip** — `try` per line, log a warning, continue.
2. **Read failure → empty box** — never let a corrupt file block the queue.
3. **Write failure → just log** — the rule: "a broken input queue must
   never stall the conversation" *(translated)*.

Path is injected (not hardcoded), making the class pure JVM with no MC
dependency. The class can be unit-tested without a Minecraft runtime.

### 12. Task model: 3 layers × 4 result states, not priority numbers

`api/common/src/main/java/com/dwinovo/numen/task/Task.java:31-80`

The interface collapses what we usually call "chain / task / pattern /
reflex" into one type. The only behavioural differences are:

- **`tick` returns a terminal state** → bounded task (mine 64 blocks,
  done, vacate). **`tick` always returns `RUNNING`** → standing task
  (fish until owner tells you to stop). Same code, two uses.
- **Who put it where** → see `TaskDispatch`'s three verbs.

`TaskSelector` runs three layers, **no priority numbers, no bands, no
chains**:

```
1. Reflex     multiple, registration order   (self-preserve; can't wait for the model)
2. Sync       0 or 1, in-round                 (short, bounded, blocks the round)
3. Current    ★ one slot ★                     (owner says so; empty = standing)
```

`TaskResult` is **four flags, not one boolean**
(`api/.../task/TaskResult.java:31-89`). The class doc spells it out:

> "**'Did it work' is not one question.** A move_to can succeed, fail
> (unreachable), run out of time, or get cancelled — and the model's
> next move differs in each case, so each gets its own field rather
> than being folded into a message the model has to parse."

```
success: bool
message: string           // short human-readable
timedOut: bool
interrupted: bool
data: map<string, any>    // task-specific payload
```

`toJson()` renders the field names verbatim to match common
tool-result conventions — a model trained on standard tool-result
shapes can read it without a custom system prompt.

`TaskDispatch` is a 3-verb static router
(`api/.../task/TaskDispatch.java:33-107`):

| Verb | When to use |
|---|---|
| `ctx(toolCallId, companion)` | Build the tool context. Always. |
| `runSync(companion, record, reply)` | Body-bound + bounded-short (sub-second, fixed deadline) — block the round, get result, reply |
| `setTask(companion, record, args, reply)` | Body-bound + unbounded (time depends on world: distance / mobs / resources) — accept-and-receipt, finish via `task_finished` event |

The same fishing code, `fish(count=64)` returns a terminal state and
yields the slot, `fish()` (no count) returns `RUNNING` forever and
occupies the slot. The decision is the *count parameter*, not a
separate code path.

`setTask` has one rejection rule (worth copying): if the current
task is "freshly accepted, not even one tick old", refuse — the
reasoning is "the model dispatched two tasks in the same round, it
should see the first result before deciding the second."

### 13. `AbstractCompanionTask` is the frozen lifecycle template

`core/common/src/main/java/com/dwinovo/numen/core/task/base/AbstractCompanionTask.java:60-322`

The lifecycle methods (`start`, `tick`, `result`) are **`final`** so
subclasses cannot break the contract. The hooks subclasses override are
narrow: `onStart`, `onTick`, `cleanup`, `preconditions`, `successMessage`,
`timeoutMessage`, `cancelledMessage`, `resultData`. The skeleton handles:

- Precondition chain (run in order, first failure wins)
- Crash capture (`crashed(phase, e)`) — see finding 14
- `pendingTerminal` pattern for one-shot tasks that finish in `onStart`
  (drop, equip) — without this, the record sits `RUNNING` for one tick
  with work already done, and an owner Stop in that window would ship
  "interrupted" for work that actually happened
- `runChild(Task)` for bounded sub-goal composition
- `stop(companion, why)` for `StopReason.PREEMPTED` releases the body
  (zero locomotion inputs, drop sneak) but **keeps every logical field
  intact** — including the nav plan. Deliberately does NOT call
  `nav.stop()`. The plan is what lets `resume()` pick back up.
  (`StopReason.REPLACED` and `BODY_GONE` go through `buildResult`'s
  `cleanup()`.)

The `planningInFlight` deadline extension
(`AbstractCompanionTask.java:127-134`) is the same principle as
[ADR-0003](../decisions/0003-reflex-layer-preemption.md#2-call-order-constraint-replaces-priority-numbers)
reflex freezeTick, applied to pathing: when the async search is in
flight, the tick is "frozen" and does not burn the deadline budget.

### 14. Tick crash capture: one task failure ≠ server crash

`AbstractCompanionTask.java:144-164` and `NumenPlayer.java:318-343`

The `crashed(phase, e)` and `reportTickFailure(ex)` methods share a
principle that the class docs state bluntly *(translated)*:

> "Tasks run every tick inside the server main loop, and what a task
> does is inherently luck-based: building calls vanilla callbacks on up
> to eight **arbitrary** blocks per tick, mining touches arbitrary block
> entities, blueprints come from player-directory files that can be
> edited at will. Any one of these throwing becomes a 'Ticking entity'
> server crash — the player loses this entire playthrough of the save,
> and the cause was a single block."
>
> "**An exception that bubbles up into `ServerLevel`'s entity tick loop
> gets wrapped by vanilla into a `ReportedException` that flips the whole
> server main loop — the watchdog declares a crash after sixty seconds and
> force-stops the server, and a roomful of players disconnect together.**"
>
> "**The most common thrower is not us.** The companion sits in the
> player list as a 'player'; when other mods receive its lifecycle events,
> they never imagine this 'player' has no real connection. That class of
> throwers we can never fully plug (plug one specific cause and the next
> mod will pick up something else) — all we can do is not let it flip the
> table."

The fix is the same in both places: **catch `RuntimeException`, log once
with full context, latch so the same body does not spam the log 20 times
per second**. A task failure is a reportable event; a server crash is
not. We must do the same in our `BotController` from day one — see
Phase 0 action items.

### 15. Pathing: 3-way split with the algorithm decoupled from the scheduler

`core/common/src/main/java/com/dwinovo/numen/core/pathing/`

```
astar/        <- algorithm only (AStarPathFinder, BinaryHeapOpenSet, Path, CutoffPath, SplicedPath, ...)
calc/         <- the scheduler (PathPlannerPool, NavGoal)
execute/      <- the follower (PathExecutor, PathingCore, PlayerNav, AimProcessor, SprintPolicy)
cache/        <- the world view (CachedNavView, LoadedOnlyView, LoadedChunks, PathCaches)
goal/         <- goal algebra (GoalCompiler)
goals/        <- concrete goals (GoalBlock, GoalXZ, GoalComposite, GoalInverted, GoalRunAway, ...)
moves/        <- movement primitives + their cost model
settings/     <- behaviour data (NavSettings, ScaffoldMaterials)
util/         <- stateless predicates (BlockHelper, BlockEntityAware, NavProfiler)
```

The split is exactly what we want: A\* can be unit-tested without ever
starting a thread; the planner pool is small enough to reason about;
the executor's complexity (failure escalation, sprint policy, cost
re-validation) is isolated from both.

### 16. `PathPlannerPool`: the shape that doesn't explode

`core/common/src/main/java/com/dwinovo/numen/core/pathing/calc/PathPlannerPool.java:32-81`

A **fixed-size** thread pool of `max(2, cores - 2)` threads with an
**unbounded queue**. Daemon threads, `MIN_PRIORITY`,
`allowCoreThreadTimeOut(true)`. The class doc cites Goetz *JCiP* §8.2
as the reference pattern for compute-bound work at scale.

The previous shape was `max = Integer.MAX_VALUE + SynchronousQueue` —
spawning a new thread per submission — and that *did* explode in
practice. A companion holds at most one in-flight search (it polls
before re-dispatching), so the queue is bounded by companion count
anyway. The fixed pool is the right answer; the unbounded queue is fine
because the upper bound is small.

`MIN_PRIORITY` is a "cheap best-effort assist on top of the hard thread
cap" — when both game tick and search thread are runnable, the OS
prefers the latency-sensitive one. The hard cap is what guarantees no
starvation.

`PathPlannerPool` exposes `liveThreads / activeThreads / peakThreads /
completedTasks` for `NavProfiler` to read — performance telemetry
without intrusion.

### 17. `AStarPathFinder`: anytime with two-stage timeout and 7-coefficient best-so-far

`core/common/src/main/java/com/dwinovo/numen/core/pathing/astar/AStarPathFinder.java:21-182`

Notable decisions:

| Decision | Why |
|---|---|
| **22 movement primitives** (`Moves.values()`) | The full action vocabulary of a Minecraft player — ascend, descend, traverse, parkour, pillar, fall, diagonal, etc. — is the edge alphabet |
| **7-coefficient best-so-far** (`COEFFICIENTS[]`) | "each of the seven tiers tracks the minimum of `estimatedCostToGoal + cost / COEFFICIENTS[i]`" *(translated)* — multi-objective relaxation for partial paths, not just the goal-reaching one |
| **Two-stage timeout** | `failureTimeoutMS = 2000` when no usable partial exists yet; switches to `primaryTimeoutMS = 500` once a path beyond 5 blocks is found. The exhaustive search gets more time when there's nothing to return; the refinement gets less time because partial is already on the table |
| **Wall clock check every 64 nodes** | Cheaper than per-node `System.currentTimeMillis()` — about half a millisecond at typical expansion rates |
| **Chunk border counter** | `pathingMaxChunkBorderFetch = 50` — number of times we let A* try to cross into an unloaded chunk before giving up |
| **Minimum improvement threshold** | `0.01` per re-relaxation; prevents floating-point noise from re-relaxing nodes 1000 times |
| **Favoring** | A cost multiplier per destination hash, applied AFTER cost is known valid, lets the planner *prefer* certain areas (e.g. lit caves, away from lava) without making them free |
| **World border snapshot at construction** | Worker thread only reads from a snapshot — no main-thread `getWorldBorder()` access. The border can change while the search runs and that's OK, we use the construction-time value |
| **`cancelRequested` flag** | Cooperatively cancellable from another thread |
| **Returns `PathCalcResult`** | Best partial or `FailureType.NO_PATH` (no usable partial). Never throws on "no path" |

`maxNodesPerSearch = 2_000_000` is explicitly **a safety net, not a
limit** — the class doc:

> "**Do not use it as a 'node cap' to squeeze CPU**: large composite
> mining goals (dozens of ores searched together) have weak heuristic
> guidance, and exploring past 50k nodes before finding a path is
> routine — cutting there produces 'misjudge no-path → blacklist nearby
> ores → take a longer route around them'. CPU belongs to the thread pool
> and thread priorities; this value is only a safety net." *(translated)*

### 18. `PathExecutor`: 7-segment failure escalation, not Pure Pursuit

`core/common/src/main/java/com/dwinovo/numen/core/pathing/execute/PathExecutor.java:45-606`

The grep for `purepursuit` returns zero matches. `PathExecutor` is a
**failsafe state machine with depth-bounded recursion** over an
already-computed path, not a Pure Pursuit controller. Per tick:

1. **Recursion-depth guard** at entry — if `onTick0()` recurses more
   than `path.length()` times, give up (SUCCESS/validPosition
   inconsistency causes infinite advance-then-regress).
2. **Position check** at the current movement's `validPositions`:
   - If inside: reset `ticksNotInValid`.
   - If outside: try **backward scan** (`findBackwardMatch` — regress
     to the first movement whose valid set contains the feet).
   - If still outside: try **forward skip +3** (`findForwardSkip` —
     +1 is the movement's own SUCCESS, +2 is intentionally skipped, +3
     onwards). The +2 skip is deliberate: a movement just past current
     can have a valid position the player hasn't entered yet.
   - If still outside: increment `ticksNotInValid` watchdog; >20 ticks
     → **cancel and replan**.
3. **Distance-from-path check** — if `> 2 blocks`, start `ticksAway`;
   `> 200 ticks` (10 s) → cancel. `> 3 blocks` (regardless of ticks) →
   cancel immediately.
4. **Block cache refresh** for the ±10 movement window — if any
   movement's `toBreak`/`toPlace`/`toWalkInto` set changed, rebuild
   the full remaining set. This is what `PathDebugPayload` reads.
5. **Chunk-loaded test on the next movement's destination** — if not
   loaded, **pause the path** (clear keys, return). Don't try to
   generate chunks from the executor thread.
6. **Cost re-verification on entering a new movement** — look ahead
   `costVerificationLookahead` (5) movements; if any is now `COST_INF`
   (world changed since path was computed), cancel with cause.
7. **Cost tolerance check** — for movements that were originally costed
   against unloaded chunks, the loaded cost may have gone up.
   `maxCostIncrease = 10` ticks of cost drift is the cap.
8. **Movement itself** — `movement.update()` returns SUCCESS / RUNNING /
   UNREACHABLE / FAILED. UNREACHABLE/FAILED → cancel.
9. **In-flight-best-path pause check** — if the partial path the
   background A* is currently producing would loop back through the
   player's feet, **stop walking** so the better path can take over.
10. **Sprint policy** — Sprint is **decided at the executor level**, not
    per-movement. Movement primitives *request* sprint (e.g. on
    traverse→ascend combo), the executor evaluates the request against
    path context (downhill sprint doesn't release the key, etc.) and
    either forces the SPRINT key or writes the sprint state directly.
    Same-source *(translated)*: "Sprint does not go through the key:
    the SPRINT key requested by a movement primitive is confiscated by
    the executor, which decides uniformly in the context of the moves
    before and after (straight jump onto a ledge, downhill chains,
    V-shaped gullies, jumps before a fall) and writes the entity's
    sprint state directly."
11. **Liveness signal** — `ticksSinceProgress` increments every tick;
    reset only on **active digging** (a hard block takes dozens of ticks
    to break — that's normal work, not stuck). If non-progress exceeds
    the timeout, cancel and log at INFO (not DEBUG) with the
    movement name, src/dest blocks, neighbouring blocks, exact player
    position — "movement stalls are the most common class of pathing
    fault, which is why this logs at INFO, not debug — if this never
    hits disk in a release build, production issues can only be guessed
    at. This is the first scene of any investigation." *(translated)*

The class doc warns the price of this complexity:

> "Sprint does not go through the key: the SPRINT key requested by a movement primitive is confiscated by the executor... **hand the request over to policy as a fact; apply every side effect of the verdict here, in one place.**" *(translated)*

This is what we want our Phase 2 pathing to look like. Pure Pursuit
would be simpler but is one specific robot algorithm and is not what
Numen does.

### 19. `NavSettings` is the entire behaviour surface as data

`core/common/src/main/java/com/dwinovo/numen/core/pathing/settings/NavSettings.java:20-285`

All fields are `public` and `mutable`. Singleton (`get()`). The class
doc:

> "All fields are public and mutable; writing them at runtime takes
> effect immediately. Global singleton via `get()`." *(translated)*
>
> "Block/item list fields are exposed through lazy getters (first access
> is what touches the registry), so pure-logic unit tests can use the
> remaining numeric fields without bootstrapping the MC registry."
> *(translated)*

Highlights:

- `blockBreakAdditionalPenalty = 30` — "30 ≈ walking 6.5 extra blocks.
  She is a guest living in someone else's world: breaking blocks is the
  last resort when no detour exists, not a shortcut." *(translated)*
  The comment then calibrates: mining bots use ~2 (tools make it nearly
  free), settled NPCs use ∞ (no breaking), companions sit in the middle
  at 30. Same reasoning should anchor our cost model.
- `fallDamageCostPerPoint = 20` — "rather than losing half a heart,
  take a four-block detour — pain is no longer free, but a height that
  cannot kill you is still a road." *(translated)*
- `pathCutoffFactor = 0.9` + `pathCutoffMinimumLength = 30` — long
  paths get tail-trimmed by 10%; short paths don't bother.
- `cutoffAtLoadBoundary = false` (default) — paths extend into
  unloaded terrain; the executor pauses at the boundary.
- `profile = false` flag toggles `NavProfiler` window logging.
- `allowInventory` (default true) — when on, planning considers the
  full backpack, and the executor auto-moves scaffold / water-bucket
  into the hotbar before the movement that needs it.
- `sprintAscends = true`, `overshootTraverse = true` — the kind of
  feel-tuning flags that adjust quality without code changes.
- `maxNodesPerSearch = 2_000_000` — same safety-net semantics as the
  A* finder.

### 20. `BlockHelper` is the five-predicate terrain judgement

`core/common/src/main/java/com/dwinovo/numen/core/pathing/util/BlockHelper.java:50-411`

Stateless predicates over `BlockGetter + BlockPos`. The five questions
the executor answers per cell:

| Predicate | What it decides |
|---|---|
| `canWalkThrough(level, pos)` | Can the body occupy this cell? Explicit deny list (fire, cobweb, sweet berry, slab, trapdoor, honey, etc.) — these have empty/partial collision shape and the raw shape test would wrongly pass them. Iron door falls through to the collision test (no redstone, can't open); wooden door/gate returns true (executor opens it by right-click) |
| `canWalkOn(level, pos)` | Solid floor? Explicit allow list for non-full-cube floors (farmland, path, soul sand, slabs, stairs, azalea, ladders, glass, chests, ender chest). Magma and honey explicitly denied. Water is walkable on only if water is also above (i.e. the body floats at the surface, never on the top cell) |
| `isStandable(level, feet)` | 2 cells of clearance above a solid floor — the canonical "valid standing spot" |
| `isHazard(level, pos)` | Lava, magma, cactus, sweet berry, fire, end portal, cobweb, bubble column — never stand in / next to break |
| `avoidWalkingInto(level, pos)` | All fluids (water too, unlike `isHazard`) plus the same hazard set — used by "is it safe to keep moving into the cell ahead" checks (walk-while-break suppressor, descend safeMode), where even water counts because the current shoves us |

Plus three engine-decision helpers:

- `isDoorwayPassable(level, doorPos, fromPos)` — orientation-aware
  (an open door perpendicular to the approach **still blocks** because
  its panel swings across the gap)
- `canPlaceAgainst(level, pos, face)` — face sturdiness test (the
  same one vanilla uses), plus an explicit refuse list (bamboo,
  pointed dripstone, moving piston, scaffolding, shulker box,
  amethyst cluster) where placement clicks would misbehave
- `canHarvest(inv, state)` — `requiresCorrectToolForDrops` blocks
  with no correct tool in inventory are vetoed; the cost model and
  break/mine tools both go through this single source of truth

**The coordinate convention** is worth memorising:

> "A 'feet position' `p` is walkable as a standing spot when: `p` and
> `p.above()` are pass-through (air/non-colliding) — room for the 2-tall
> entity body, and `p.below()` is solid-walkable — something to stand on."

And `playerFeet(level, x, y, z)` adds a 0.1251 nudge upward (soul sand
and farmland top out slightly lower) plus a slab-bump: if the
nudged cell is a slab, return the cell above it. This is what
reconciles "standing on a bottom slab" with the move graph, where a
move onto a slab targets the cell above the slab.

### 21. The path debug visualisation goes through a network payload

`api/common/src/main/java/com/dwinovo/numen/network/payload/PathDebugPayload.java`

Because the path executes on the server but the user (and the LLM
client) is on the client, every visible signal of the path's state has
to cross the network. The payload's shape is the canonical example:
it carries the path itself, the **set of blocks to break**, the
**set of blocks to place**, and the **set of cells the body squeezes
through** (computed once per `recalcBP` tick) — same source of
truth as the executor, no separate projection.

For us: every bit of introspection on the device layer (path preview,
inventory, task status, chunk status) will end up in a network payload.
Plan for the protocol surface early; it is hard to add later.

## Mapping to mc-bot-server

Per [baritone-notes.md](baritone-notes.md#mapping-to-mc-bot-server) style.

### What we already had right (validated by Numen)

- ADR-0001 (moddev-legacyforge) — irrelevant to Numen (they multiloader)
  but the **api/ core isolation principle is mirrored**: their
  `api/common` is exactly the discipline we want on our `api/`
  package.
- ADR-0002 (capability / arbiter) — Numen's 3-layer `TaskSelector` is
  a slightly different cut of the same idea: layers of precedence
  instead of priority bands. The principle "different mechanisms for
  different urgencies, not bigger numbers" is identical.
  **But:** Numen's `TaskResult` is 4 flags (success/timeout/interrupted
  + message), not a single boolean. The boundary-D contract we write
  should use the 4-flag shape — the model reasons about it cleanly.
- ADR-0003 (reflex layer) — Numen's `CompanionLifecycle` event seam
  is the same pattern (engine fires, toolpacks subscribe). The pause
  semantics for tasks under preemption are also the same: keep the
  plan, release the body.

### What Numen reveals we hadn't answered

| Question | Numen's answer | Our answer |
|---|---|---|
| Server-side bot lifecycle states | `ALIVE / DEAD / RESPAWNING / TERMINATED` with three event kinds (remove/death/abort), `fireAbort` fires on client | **Open** — `BotController` lifecycle hooks need definition in Phase 0 |
| Boundary D: session-lock vs command-dispatch | Numen is **command-dispatch** — `setTask` accepts and receipts, `task_finished` event delivers result. No `acquire/release` pair | **Already aligned** (ADR-0002), but we should write the 4-flag `TaskResult` into boundary D explicitly |
| Tick exceptions: where to catch | `crashed(phase, e)` in the task skeleton, `reportTickFailure` on the body; both latch to one log per body | **Must do from day one** in our `BotController`. Without it, any third-party mod throwing on a "fake player" lifecycle event crashes the server |
| WorldView is a family, not a single interface | `LoadedChunks` (snapshot) + `CachedNavView` (worker view) + `LoadedOnlyView` (executor view, never blocks). Two-views-on-the-same-world is the rule | **We should name this now** in our `WorldView` design — even if the Phase 0 implementation is one class, leave a comment that the planner and the executor need different views |
| Event system: replay and offline handling | `EventQueue` (with backpressure, locking, drain decision) + `JsonlJournal` (snapshot rewrite, fault-tolerant) + `NumenEvents` (single entry, auto timestamp, online-vs-offline dispatch) | **Phase 1** to adopt the shape; for now, the architecture note that event publishing is one method, not many |
| ChunkLoader is hard | Mixin `ChunkMapCompanionMixin` + `CompanionChunkLoader` with region ticket + 40-tick self-expiring timeout + cross-chunk re-centre + shared ticket per chunk | **Defer until server-fake-player work**, but the lesson is "WorldView cannot pretend chunk status is uniform" — `isLoaded(pos)` is non-negotiable |

### What Numen does that we should not copy

- **`NumenActuator` does not exist** in their code. The user's
  prior analysis referred to it; we confirmed there is no
  `acquire/release` pair in the public API. The closest analogue is
  `ServerToolTransport`, which is request-response over a network
  payload, **not** a session lock. The "session-lock vs
  command-dispatch" framing in some planning docs is therefore not
  even a dichotomy Numen confronts.
- **Elytra / multiloader / 1.21.1** — Numen targets MC 1.21.1 with
  Fabric + NeoForge multiloader. We target 1.20.1 Forge single-loader
  (ADR-0001). Code reading is conceptual, never a copy.
- **Multiloader per-platform subproject** — their `api:common`,
  `api:fabric`, `api:neoforge` triplet is irrelevant to us.
- **`UUID` ownership field on the body** — only relevant if we go
  server-side fake player; do not add now.
- **`reportTickFailure`'s latch is per-body** — when the body is
  gone and a new one is summoned, the latch resets. We should
  decide our own latch scope (per-bot vs per-session) in our
  `BotController` design.

## Cross-check against frozen decisions (added 2026-08-21)

This doc was first drafted before
[boundaries.md](../architecture/boundaries.md),
[ADR-0004](../decisions/0004-tick-pipeline-actor-channels.md), and
[workplan.md](../guide/workplan.md) existed. Reading them changes the
status of several recommendations below. The matrix maps each Numen
finding to its current status in the project:

| Status | Meaning |
|---|---|
| **✓ FROZEN** | Already covered by a binding decision in `boundaries.md` or `ADR-0004`. No new action — read the existing decision. |
| **🔧 OPEN** | Still a genuine open question; needs a decision or a workplan item. |
| **⏸ DEFERRED** | Explicitly deferred by a "Deferred, with reopen conditions" entry in `boundaries.md`. The workplan gates it. |
| **🚫 OUT-OF-SCOPE** | Numen-specific (server-side fake player, multiloader, 1.21.1) — do not adopt in our project. |

### Recommendations that became FROZEN by other decisions

| Numen finding | Already covered by | What changed |
|---|---|---|
| Boundary D: command-dispatch (not session-lock) | `boundaries.md` Boundary D + decision 1, 6 | The "session-lock vs command-dispatch" framing from prior planning is settled |
| Task model 3 layers | `boundaries.md` decision 9, 10, 13 + ADR-0004 D1 | We use `reflex → arbiter → behaviors → actor.flush` (4 stages), not Numen's `reflex / sync / current` (3 layers). Numen's "sync" layer doesn't apply to our external harness |
| TaskResult 4-flag shape | `boundaries.md` decision 13 + ADR-0004 D3 | We use `ExecutionReport { status: RUNNING \| SUCCESS \| FAILED(reason) \| STUCK, metrics }` — 4 states, not Numen's 5 (`success / timedOut / interrupted / data`). FAILED carries an enum reason (NO_PATH, TIMEOUT, INVALIDATED) per ADR-0004 D3 |
| `StopReason.PREEMPTED` keeps the plan | `boundaries.md` Boundary C + ADR-0003 §3 | Already aligned. Plan is preserved across preemption; resume revalidates world |
| Goal algebra | `boundaries.md` decision 7 + decision 8 + workplan Stage 0 (Goal predicate + GoalBlock + GoalNear stubs) | Reserved at primitive level; concrete algebra is Stage 0 work |
| Movement five-tuple model | `boundaries.md` decision 8 | The `(src, dst, precondition, cost, execution state machine)` is the model |
| Data-driven behaviour parameters | `boundaries.md` decision 4 + workplan Stage 2 "rule-table JSON loading" | Registry + Codec + ReloadListener trio; `NavSettings`-style singleton is the model |
| Capability unit = fixed contract | `boundaries.md` decision 6 + ADR-0002 §1 | Process contract (`isActive / priority / onTick / onLostControl / resume / onContextInvalidated / displayName`) is the contract; the 7 lifecycle methods are the shape |
| Single tick entry, fixed order | `ADR-0004 D1` | `reflex → arbiter → behaviors → actor.flush`, server-tick END binding |
| Actor central authority | `ADR-0004 D2` | Four channels (MOVE / ROT / USE / SLOT) with per-tick claims; ties favor incumbent holder; claims expire at flush |
| Path debug goes through network | `ADR-0004 D1` + Boundary D | PathDebugPayload is on the path; the harness protocol is the same wire |
| Path debug visualization | (implicit in `ADR-0004`) | Phase 2 backlog, gated on Stage 1 success |
| FakeConnection's "no-op, never throw" | `ADR-0004 D5` (EntityBinding adapter) | Our adapter is the boundary; the lesson generalises: any adapter default must be "no-op, never throw, never block" |
| Path executor failure escalation | `boundaries.md` decision 8 (Movement five-tuple) + decision 18 (workplan Stage 2) | A\* + executor is Stage 2 work; the **seven-segment** failure escalation in `PathExecutor` is the design reference, not the implementation (we write our own; the shape is what matters) |
| BlockHelper five predicates | `boundaries.md` decision 8 + 17 | WorldView reserves `BlockSnapshot / EntitySnapshot / CollisionShape` primitive families up front; concrete predicates are Stage 2 |
| PathPlannerPool shape (fixed-size, daemon, MIN_PRIORITY) | `boundaries.md` decision 18 (workplan Stage 2 async replan worker) | Same shape; never `max=MAX_VALUE + SynchronousQueue` |
| `maxNodesPerSearch` as safety net, not a normal cap | `boundaries.md` decision 18 (reopen when async replan goes off-thread) | Same principle; comment in code when we ship it |

### Recommendations that are still OPEN

| Numen finding | Open because | Where it belongs |
|---|---|---|
| `BotController.tick()` MUST catch `RuntimeException` + latch one log per body | **Not addressed** by any FROZEN decision. ADR-0004 D5 mentions "server-tick END binding thin adapter" but says nothing about exception handling inside it. The Numen principle (one task failure ≠ server crash) is **not in the boundaries doc** | New ADR-0005 candidate, or extension of `BotController` workplan item with explicit exception-latch policy. **Highest priority** — without this, a third-party mod's lifecycle callback crash kills the server |
| `WorldView.isLoaded(pos)` | Decision 17 reserves `BlockSnapshot / EntitySnapshot / CollisionShape` primitive families but says nothing about chunk-load state as a first-class query | Add to the WorldView Stage 0 workplan item as a reserved method, with a mock that defaults to "loaded" |
| `EventQueue` 4 concerns (backpressure / locking / drain / wall clock) | We don't have an event bus yet (no EventQueue equivalent in the project) | Phase 1+; reference the design when we build it |
| `JsonlJournal` snapshot-rewrite + fault-tolerant | We don't have an event journal | Phase 1+; same as above |
| `NumenEvents.emit` single entry point | We don't have a world-events layer yet | Phase 1+; design with the harness protocol in mind |
| `planningInFlight` deadline freeze (search-in-flight does not burn deadline) | ADR-0004 D4 puts timeoutTicks enforcement in the behavior layer; the search-in-flight freeze is a refinement of that principle, not addressed | Stage 2 when A\* worker is added; ADR-0004 might need a note, or just land as a behavior-layer implementation detail |

### Recommendations that are DEFERRED (gated by `boundaries.md`)

| Numen finding | Deferred by | Reopens when |
|---|---|---|
| Server-side fake player = `ServerPlayer` subclass, with `NumenPlayer.tick()` override | `boundaries.md` "Fake-player carrier choice" → "spike opens at entity-binding start" (Stage 1, workplan item L) | Stage 1 starts. The choice between server-side fake player, client-side bot, or hybrid is a **deliberate spike** — do not pre-decide |
| `BotController` lifecycle 4 states `ALIVE / DEAD / RESPAWNING / TERMINATED` | Same as above | Same as above. The whole "what is the lifecycle of the body" question is meaningless until we have a body |
| `CompanionChunkLoader` region ticket + 40-tick self-cleaning | Same as above | Same as above. The chunk-loader is body-side; not Phase 0 |
| `FakeConnection` SERVERBOUND + EmbeddedChannel | Same as above | Same as above. The connection is body-side; not Phase 0 |
| `NumenPlayer.tick()` manual `doTick()` + `doCheckFallDamage` | Same as above | Same as above. This is the body-side compensation for the network layer being no-op |
| `pausedReflexes` (pause by id, not by target) | `boundaries.md` decision 9 (reflex layer) | When a Process needs to suppress a reflex (e.g. attack holding back mob_defense). Stage 0 won't have any processes yet |
| `recheckStatus` death message capture | Deferred per "Fake-player carrier choice" | Same |
| `MojangSkins`, `showAllSkinLayers`, `CompanionSpeech`, `CompanionStateWatch`, `OwnerHurtWatch` | All body-side companion features | Same |

### OUT-OF-SCOPE: do not adopt

| Numen finding | Why out of scope |
|---|---|
| Multiloader (`api:common / fabric / neoforge` triplet) | We target 1.20.1 Forge single-loader (ADR-0001). Multiloader is a different commitment |
| `NumenActuator` (does not exist) | A non-existent concept from prior planning docs. Numen is command-dispatch; we are too |
| `core/tools/` LLM tool implementations | Out of project scope entirely |
| `ai/`, `ui/`, `agent/` packages | Out of project scope entirely |

## Phase 0 action items (revised 2026-08-21)

This list was first drafted before the boundaries + ADR-0004 + workplan
were written. Items are now annotated with status from the cross-check
table above. The Stage 0 workplan is the **authoritative** Phase 0
checklist; this list is a **reading** of Numen against that checklist.

Status legend: ✓ already in Stage 0 or FROZEN decision · 🔧 still
needs new decision / workplan item · ⏸ deferred to Stage 1+ (entity
binding) or Stage 2 (pathing) · 🚫 not in scope.

| Numen insight | Status | Where to read |
|---|---|---|
| `BotController.tick()` MUST catch `RuntimeException`, log with full context, latch one log per body | **🔧 OPEN — needs new decision** | Not in `boundaries.md` or `ADR-0004`. Propose ADR-0005 or add to workplan Stage 0 `BotController tick ordering` item |
| `WorldView` interface MUST include `isLoaded(pos)` | **🔧 OPEN — needs workplan note** | Decision 17 reserves primitive families but says nothing about load-state. Add to Stage 0 `WorldView interface + reserved primitives` item as a reserved method |
| `WorldView` is a family of views (planner snapshot vs executor live) | **🔧 OPEN — needs workplan note** | Same as above. The decision reserves primitives, not view modes |
| `EventQueue` 4 concerns (backpressure / locking / drain / wall clock) | **⏸ DEFERRED** | Phase 1 event system. No event bus in the project today |
| `JsonlJournal` snapshot-rewrite + fault-tolerant | **⏸ DEFERRED** | Same as above |
| `NumenEvents.emit` single entry point + auto-timestamp | **⏸ DEFERRED** | Same as above |
| Outbox for owner-offline events | **⏸ DEFERRED** | Same as above |
| `EventTypes` registry (table-driven type → toModel / chatPreview / clearedByInterrupt) | **⏸ DEFERRED** | Same as above |
| `BotController` lifecycle 4 states `ALIVE / DEAD / RESPAWNING / TERMINATED` | **⏸ DEFERRED** | Per `boundaries.md` "Fake-player carrier choice → spike at entity-binding start" (Stage 1, item L) |
| `CompanionChunkLoader` region ticket + 40-tick self-cleaning | **⏸ DEFERRED** | Same as above |
| `FakeConnection` SERVERBOUND + EmbeddedChannel + LOOPBACK | **⏸ DEFERRED** | Same as above |
| `NumenPlayer` tick override (manual `doTick()` + `doCheckFallDamage`) | **⏸ DEFERRED** | Same as above |
| `pausedReflexes` (pause-by-id) | **⏸ DEFERRED** | When first Process needs to suppress a reflex; Stage 1+ |
| `AbstractCompanionTask` frozen lifecycle + final methods | **✓ FROZEN — already in our model** | `boundaries.md` decision 6 + ADR-0002 §1 contract (`isActive / priority / onTick / onLostControl / resume / onContextInvalidated / displayName`) is the parallel. The "lifecycle methods final" principle applies; the specific 7 methods differ from Numen's 5 |
| `StopReason.PREEMPTED` keeps the plan | **✓ FROZEN** | `boundaries.md` Boundary C + ADR-0003 §3 |
| `TaskResult` 4-flag shape (success / message / timedOut / interrupted / data) | **✓ FROZEN — different shape, same principle** | `boundaries.md` decision 13 + `ADR-0004 D3`: `ExecutionReport { status: RUNNING \| SUCCESS \| FAILED(reason) \| STUCK, metrics }`. 4 states, not 5. Same principle: each terminal state is a distinct field, not a parsed message |
| `planningInFlight` deadline freeze | **⏸ DEFERRED** | Stage 2 async replan (per `boundaries.md` reopen trigger + workplan Stage 2 XL "async replan worker") |
| `PathExecutor` 7-segment failure escalation | **⏸ DEFERRED** | Stage 2 (per workplan). The shape is the reference; we write our own |
| `BlockHelper` 5 predicates + 3 helpers | **⏸ DEFERRED** | Stage 2. The shape (canWalkThrough / canWalkOn / isStandable / isHazard / avoidWalkingInto + isDoorwayPassable / canPlaceAgainst / canHarvest) is the reference |
| `PathPlannerPool` shape (fixed-size, daemon, MIN_PRIORITY) | **⏸ DEFERRED** | Stage 2 async replan worker |
| `NavSettings` data-driven behaviour parameters | **✓ FROZEN** | `boundaries.md` decision 4 (Registry + Codec + ReloadListener trio); workplan Stage 2 "rule-table JSON loading" |
| `maxNodesPerSearch` as safety net, not cap | **⏸ DEFERRED** | Stage 2; same principle, comment in code |
| Path debug goes through network payload | **✓ FROZEN** | ADR-0004 D1 + Boundary D; Phase 2 backlog |
| FakeConnection "no-op, never throw, never block" lesson | **✓ FROZEN** | `ADR-0004 D5` (adapter default); also applies to any user-visible I/O surface |
| `ServerPlayer extends` for free mod compat | **⏸ DEFERRED** | Stage 1 spike; not pre-decided |
| `NumenActuator` (does not exist) | **🚫 OUT-OF-SCOPE** | A non-existent concept from prior planning docs. Removed from the project vocabulary |
| Multiloader / 1.21.1 / `api:common / fabric / neoforge` | **🚫 OUT-OF-SCOPE** | We are 1.20.1 Forge single-loader (ADR-0001) |
| `core/tools/` LLM tool implementations | **🚫 OUT-OF-SCOPE** | Harness lives outside the JVM (Architecture Overview) |

## Open questions (revised 2026-08-21)

- **(🔧 NEW) Tick exception handling**: where to catch `RuntimeException`?
  Propose ADR-0005 or extend ADR-0004 D5. **Not covered** by any current
  decision. The "tick exception bubbles up → `ReportedException` →
  watchdog kills the server" failure
  mode is Numen's hardest-won lesson and our `boundaries.md` is silent on
  it. Without an explicit policy, a third-party mod's lifecycle callback
  crash on our bot will kill the server.
- **(🔧 NEW) `WorldView.isLoaded(pos)`**: reserved in the contract or not?
  decision 17 reserves `BlockSnapshot / EntitySnapshot / CollisionShape`
  primitive families but not chunk-load state. The boundary between
  "BlockSnapshot says AIR" and "we don't know" needs to be a first-class
  query for any planner that needs to know what it doesn't know.
- **(🔧 NEW) `WorldView` view modes (planner snapshot vs executor live)**:
  same reasoning. Decision 17 covers data shape, not thread-safety view.
  Either reserve a separate executor-view interface in the contract, or
  document explicitly that one interface serves both and the executor
  enforces "never block". Numen's lesson: the latter is fragile; the
  former pays off in tests.
- **(⏸ DEFERRED) Reflex event Kind table**: Numen has 8 hard-coded Kinds.
  We have no events yet. Stage 1+.
- **(⏸ DEFERRED) What does our `WorldView` return for unloaded chunks?**
  Same as above (covered by `isLoaded(pos)` reservation if it lands).
- **(⏸ DEFERRED) Where does our bot save state?** Per `boundaries.md`
  "Memory / waypoints / persistence → first cross-session task requirement".
  Not Stage 0.

## Re-checked (no change)

The 21 Numen findings (sections above) are observations about Numen's
code. They are stable as long as the commit hash
`8a94cfd6c4589d1247cda093016e7f5b221282e7` is the verified reference.
If Numen publishes a meaningful new release, re-read the cited files
and update findings inline.

The **decisions and action items above** are what changes. After any
change to `boundaries.md` / `ADR-0004` / `workplan.md`, re-run the
cross-check matrix in this section.

## Cited files (read from `8a94cfd6c4589d1247cda093016e7f5b221282e7`)

```
LICENSE
LICENSE-API
LICENSE-ASSETS
build.gradle

api/common/src/main/java/com/dwinovo/numen/entity/
  CompanionChunkLoader.java
  CompanionFactory.java
  CompanionLifecycle.java
  CompanionRegistry.java
  CompanionRoster.java
  Companions.java
  CompanionSpeech.java
  CompanionStateWatch.java
  EventOutbox.java
  FakeConnection.java
  InputDriver.java
  MojangSkins.java
  NumenCommands.java
  NumenPlayer.java
  OwnerHurtWatch.java
  SafeSpawn.java

api/common/src/main/java/com/dwinovo/numen/event/
  EventQueue.java
  EventTypes.java
  JsonlJournal.java
  NumenEvents.java

api/common/src/main/java/com/dwinovo/numen/task/
  Task.java
  TaskDispatch.java
  TaskResult.java
  TaskRecord.java
  CompanionTickDispatcher.java
  CompanionBrain.java

api/common/src/main/java/com/dwinovo/numen/agent/tool/
  ServerToolTransport.java
  NumenTool.java
  ToolCall.java

api/common/src/main/java/com/dwinovo/numen/network/payload/
  PathDebugPayload.java
  TaskResultPayload.java
  NumenEventPayload.java
  NumenDeathPayload.java
  NumenRespawnPayload.java
  SummonRequestPayload.java
  DismissRequestPayload.java
  ExecuteToolPayload.java
  CancelTasksPayload.java

core/common/src/main/java/com/dwinovo/numen/core/pathing/
  astar/AStarPathFinder.java
  astar/AbstractNodeCostSearch.java
  astar/BinaryHeapOpenSet.java
  astar/{CutoffPath,NavPath,Path,PathBase,PathCalcResult,PathNode,SplicedPath}.java
  calc/PathPlannerPool.java
  calc/NavGoal.java
  execute/PathExecutor.java
  execute/{ExecHarness,PathingCore,PlayerNav,AimProcessor,SprintPolicy}.java
  cache/{CachedNavView,LoadedChunks,LoadedOnlyView,PathCaches}.java
  moves/{Movement,MovementHelper,MovementState,MovementStatus,Moves,Input,CalculationContext,ToolSet,ChunkLoadedTest,ActionCosts,AimGeometry,MutableMoveResult}.java
  moves/movements/{MovementAscend,MovementDescend,MovementDiagonal,MovementDownward,MovementFall,MovementParkour,MovementPillar,MovementPlacement,MovementTraverse,BuildPlacementRegistry}.java
  settings/{NavSettings,ScaffoldMaterials}.java
  util/{BlockHelper,BlockEntityAware,NavProfiler}.java
  bridge/{ContextFactory,GoalAdapter,PoolSearchDispatcher,SearchDispatcher,SearchHandle}.java

core/common/src/main/java/com/dwinovo/numen/core/task/base/
  AbstractCompanionTask.java
  DropTracker.java
  GoToThenDoTask.java
  Precondition.java
  RecoveryLadder.java
  TargetSet.java

core/common/src/main/java/com/dwinovo/numen/core/task/{chain,reflex,survival}/
  {BreathChain,MLGChain,MobDefenseChain,UnstuckChain,CoreReflexes,SurvivalDecisions,UnstuckDetector}.java

core/common/src/main/java/com/dwinovo/numen/core/debug/
  PathDebug.java
  PathDebugRenderer.java
```

## Maintenance

After verifying the content against a newer Numen commit:

```bash
python tool/mcbot_tool.py doc touch numen-notes
```

After this doc's findings land in code or new ADRs, update the
"Mapping to mc-bot-server" section so the table reflects what we
actually adopted, not just what we read.
