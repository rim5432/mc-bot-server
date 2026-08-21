---
title: Baritone Architecture Notes (Design Reference)
last_verified: 2026-08-21
covers: []
---

# Baritone Architecture Notes (Design Reference)

Study notes on [cabaletta/baritone](https://github.com/cabaletta/baritone)
(9.1k stars, LGPL-3.0) as a design reference for this mod's device layer.
Facts below were read from the `master` tree and release metadata on
2026-08-21.

## Version mapping

| Baritone release | Minecraft | Forge |
|---|---|---|
| v1.10.1 / v1.10.3 | **1.20.1** | yes |

v1.10.3 is the closest runnable reference for our exact target stack.
Newer releases (v1.11+) moved to 1.21.x.

**License caution**: LGPL-3.0. Learning the architecture is free; copying
code verbatim would impose LGPL obligations on this project. Treat all
notes below as clean-room concept references only.

## Package layout (master)

```
src/api/java/baritone/api     <- public API ONLY (interfaces, DTOs, goals,
                                 process contracts, command abstractions)
src/main/java/baritone/
  behavior/                   <- always-on subsystems (6 files)
  process/                    <- exclusive high-level tasks (17 files)
  pathing/                    <- A*, movements, PathExecutor (25 files)
  command/                    <- chat command system (45 files)
  cache/                      <- persistent world/waypoint cache
  event/, selection/, utils/, Baritone.java (provider/registry root)
```

The api/main source-set split mirrors our own separation principle:
the public contract of a bot lives apart from its machinery.

## Takeaway 1: Process = exclusive task with arbitration

`baritone.api.process.IBaritoneProcess` is the single most relevant
contract for our device layer:

- **Only one process controls the bot at a time**; each advertises
  `isActive()` and `priority()`; highest priority wins arbitration.
- Tick-cooperative control: `PathingCommand onTick(calcFailed,
  isSafeToCancel)` — the executor asks, the process answers.
- `isTemporary()`: reflex-like processes (e.g. pause-for-auto-eat) may
  seize control briefly **without** cancelling the main task, and hand
  it back automatically.
- `onLostControl()`: explicit cancellation contract when another
  non-temporary process takes over.

Mapping to mc-bot-server: LLM-issued intents become processes. Conflicting
intents degrade into a priority problem instead of a race condition.
Safety reflexes (low health, hazard proximity) are temporary processes
that preempt but restore the original job.

## Takeaway 2: small closed verb vocabulary between layers

`PathingCommandType` is a tiny enum: `SET_GOAL_AND_PATH`,
`REQUEST_PAUSE`, `CANCEL_AND_SET_GOAL`, `REVALIDATE_GOAL_AND_PATH`,
`FORCE_REVALIDATE_GOAL_AND_PATH`, `DEFER`, `SET_GOAL_AND_PAUSE`.

That is the ENTIRE language the task layer uses to drive locomotion.
Our harness-to-device JSON protocol should be equally closed: a handful
of verbs plus structured payloads. No free-form imperative chatter.

## Takeaway 3: composable goal algebra

`baritone.api.pathing.goals`: `GoalBlock`, `GoalNear`, `GoalXZ`,
`GoalYLevel`, `GoalGetToBlock`, `GoalComposite`, `GoalInverted`,
`GoalRunAway`, `GoalStrictDirection`, ... The LLM-facing API of our bot
should accept structured, composable goals rather than bare coordinates,
so plans stay inspectable and re-validatable (REVALIDATE semantics).

## Takeaway 4: two-tier runtime

- **Behaviors** are always-on services (LookBehavior, InventoryBehavior,
  PathingBehavior, WaypointBehavior) - they never hold an exclusive lock.
- **Processes** are exclusive jobs built on top of behaviors.

Device-layer equivalent: perception sync / inventory state / network
endpoint run as always-on services; anything an LLM commands runs as a
process that can be arbitrated, paused, cancelled, and reported.

## Takeaway 5: movement primitives with explicit costs

`pathing/movement/movements/*` defines discrete primitives (Ascend,
Descend, Traverse, Pillar, Diagonal, Fall, Parkour) each with a cost
model (`ActionCosts`) and a per-movement state machine (`MovementState`);
`PathExecutor` walks paths segment-by-segment and re-plans on failure.

Lesson: expose bot capabilities as granular, costed primitives with
machine-checkable status, so the harness can reason about economics and
receive precise failure reports instead of opaque timeouts.

## Takeaway 6: persistent world cache

The `cache/` package plus WaypointBehavior persist discovered world data
across sessions. Our bot will need the same: observation logs must
outlive a single server session for the external harness to build memory.

## Takeaway 7: hang-proof background computation

Pathfinding runs on worker threads with hard wall-clock timeouts
(`AbstractNodeCostSearch` primary/failure timeouts). Same philosophy we
applied to the build tooling: nothing on the main loop is allowed to
block indefinitely.

## Takeaway 8: capability extension architecture

How Baritone lets new capabilities slot in without touching the core.

### Unit of capability = Process with a fixed contract

New capability = one interface in `api/process/` + one impl class. The
contract is always the same seven methods (`isActive`, `onTick`,
`isTemporary`, `onLostControl`, `priority`, `displayName`, plus
`DEFAULT_PRIORITY`). Capabilities never reference each other; all
coordination goes through the central arbiter.

### Registration and arbitration are centralized

`utils/PathingControlManager` owns everything:

- `registerProcess(proc)` defensively calls `proc.onLostControl()` first
  so every capability enters the registry reset.
- Each tick, `executeProcesses()` collects processes reporting
  `isActive()`, moves newly-active ones to the front of the queue (fresh
  intent wins ties), sorts by descending `priority()`, then polls
  `onTick(...)` down the list:
  - returning `DEFER` = step aside, next candidate gets asked;
  - returning any other command = this process is in control;
  - winner-take-all: when a non-temporary process wins, all remaining
    active processes get `onLostControl()`.
- Tick split: `preTick` executes the current path command; `postTick`
  (between ticks) does goal re-validation and recalculation - expensive
  computation lives outside the game tick.
- `cancelEverything()` force-clears and asserts only temporary processes
  kept control - an invariant check, not just cleanup.

### Access = typed service locator

`IBaritone` exposes fixed accessors (`getMineProcess()`,
`getPathingBehavior()`, ...) plus the open registration point
`getPathingControlManager().registerProcess(...)`. Consumers (including
third-party mods via BaritoneAPI) see interfaces only.

### User-facing surface decoupled via registries

`ICommandManager` holds a generic `Registry<ICommand>` with lookup,
execute, and tab-completion. Commands are skin over capabilities; the
capability layer does not know commands exist. Similarly `Settings.java`
auto-exposes any new `Setting<T>` through the existing set/get commands.

### Open interfaces need no registry at all

`Goal` is just `heuristic(x,y,z)` + `isInGoal(pos)` - anyone can define
a new goal type and hand it to the pathfinder. Extension points come in
two flavors: registry-based (processes, commands) and interface-only
(goals, event listeners).

### Mapping to mc-bot-server

1. Device capability contract: `id`, closed verb set, goal schema,
   `onCommand(payload) -> TaskHandle`, `cancel()`, `statusSnapshot()`.
2. One central TaskArbiter per bot: priority lanes for normal tasks vs
   safety reflexes; winner-take-all with temporary preemption and
   automatic restore (copy the `isTemporary` semantics verbatim).
3. Harness discovery: self-describing endpoint listing capabilities +
   schemas, so adding a device capability requires zero harness changes.
4. Keep our api package pure (POJOs + JSON schema), mirroring their
   api source-set discipline.

## Verified failure evidence (read 2026-08-21)

Baritone's issue tracker confirms the process-only model cannot host
survival reactions:

- **#786** "Mobs killing baritone while mining" - exactly the
  creeper-from-behind scenario; threat response needs second-scale
  reaction, replanning cycles cannot provide it.
- **#162** - deaths while path recalculation runs; the proposed fix was
  attaching Impact's killaura module, i.e. an out-of-architecture patch.
- **#3565** "Nasty parts of Baritone" - two extra structural lessons we
  adopt: (a) input authority must be centralized or processes "step on
  each other's paws" (InputOverrideHandler is their regret here); (b)
  cancellation is inherently graded - some movements are physically not
  interruptible mid-fall/parkour, hence the safeToCancel ladder.

Consequence for us: survival lives in a separate reflex mechanism, not
in more processes. See [ADR-0003](../decisions/0003-reflex-layer-preemption.md);
capability/arbiter contracts are in
[ADR-0002](../decisions/0002-capability-model-task-arbiter.md).

## What NOT to copy

- The 45-file chat command subsystem: our control plane is an external
  protocol, not in-game chat parsing.
- Elytra/nether pathfinding octree complexity: out of scope for now.
- Monolithic Settings.java registry: fine pattern at their scale, but a
  typed subset exposed over our endpoint is enough initially.
