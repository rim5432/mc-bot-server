---
title: MineProcess - composite mining task with search-mine-collect loop
last_verified: 2026-08-27
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/api/process/DigMission.java
  - src/main/java/com/mcbot/mcbotserver/core/process/DigProcess.java
  - src/main/java/com/mcbot/mcbotserver/core/process/MineProcess.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java
  - src/main/java/com/mcbot/mcbotserver/core/command/MineCommandHandler.java
  - src/main/java/com/mcbot/mcbotserver/McBotServer.java
  - tool/harness/mc.py
status: open (filed 2026-08-27; first composite task; absorbs 0013's deferred composite-tasks item; pattern reference is 0010 HungryProcess)
related:
  - doc/architecture/issues/0010-hungryprocess-food-acquisition-planner.md
  - doc/architecture/issues/0013-world-interaction-layer.md
  - doc/architecture/issues/0009-block-capability-dig.md
  - doc/architecture/function-map.md
---

# Issue 0014: MineProcess - composite mining task

## 1. Scope and lineage

Moved from 0013 deferred: "Composite tasks (mine / chop / hunt): 0010
pattern first." 0010 (HungryProcess) is still in design phase, but its
Process-layer state-machine pattern is well-defined enough to serve as the
reference for this first composite task. MineProcess is the v1
implementation; chop and hunt follow once the pattern is proven.

The task: given a block type and a count, find nearby instances of that
block, walk to each, mine it, collect the drop, and repeat until the
inventory holds the requested count. This is the simplest composite task
because: (a) the target is a static block (no pathing AI to chase), (b)
the mining primitive already exists (DigProcess), (c) collection is
vanilla auto-pickup (walk over the drop).

Non-goal: vein-mining optimization, tool selection, silk touch / fortune
enchantment handling, dimension-aware search, or any block that requires
a tool to drop (those need the equip verb + tool-in-inventory check,
deferred to a follow-up).

## 2. Why a Process, not a harness script

Two viable paths were considered (2026-08-27 ruling):

| Path | Shape | Verdict |
|---|---|---|
| A. harness script | `skills/mine.py` looping mc primitives | Rejected: per-call process spawn, state via disk cursors, no single task handle |
| B. bot-side Process | `MineProcess` in arbiter, same tier as GotoProcess | Selected: state-contained, one taskId, same completion-event shape |

The deciding factor: boundary D's task model (submit → taskId →
TASK_COMPLETED/FAILED event) is the harness's only async coordination
primitive. A harness script would have to synthesize taskIds and events
itself, duplicating the CommandBus. A Process gets all of that for free.

## 3. Architecture

### 3.1 Position in the tick pipeline

```
BotController.onTick():
  reflexLayer.tick()
  arbiter.executeProcesses()   -> MineProcess lives here
  [NEW] DigMission claim path  -> triggers Intent.Dig when isDigging()
  behaviors.tick()              -> PathingBehavior executes GoalNear
  actor.flush()
```

MineProcess is a Process (side-effect-free, outputs Directive{Goal,
Overrides}), same tier as GotoProcess and DigProcess. It is NOT a
behavior: the loop logic (search → move → mine → collect) is
process-class planning, not per-tick claim resolution.

### 3.2 DigMission interface (the extraction)

BotController's mission-dig path currently hardcodes
`instanceof DigProcess`. MineProcess needs the same per-tick
aim+dig claim injection. The fix is a narrow interface:

```java
public interface DigMission {
    CellPos digTarget();       // the block currently being mined
    boolean isDigging();       // true when in the DIGGING phase
    int priority();            // arbiter seat priority
    String missionTaskId();    // boundary-D task id
}
```

DigProcess is refactored to implement DigMission (zero behavior change).
BotController line 517 changes from `instanceof DigProcess` to
`instanceof DigMission dm && dm.isDigging()`. This is the only
BotController change; future ChopProcess implements the same interface
without touching the controller.

### 3.3 State machine

```
                 ┌─────────┐
                 │  IDLE   │  submitted
                 └────┬────┘
                      ▼
                 ┌─────────┐
                 │ SEARCH  │  scan radius for target block; pick nearest
                 └────┬────┘
              ┌───────┴───────┐
              │               │
        found │          none │
              ▼               ▼
        ┌─────────┐     ┌─────────┐
        │ MOVING  │     │ FAILED  │  "no <block> within <radius>"
        │ GoalNear│     └─────────┘
        └────┬────┘
        ┌────┴────┐
   reached│    STUCK/timeout
        ▼         ▼
  ┌─────────┐  (skip this target, back to SEARCH)
  │ DIGGING │
  │isDigging│
  │ = true  │
  └────┬────┘
   block air?
        ▼
  ┌─────────────┐
  │ COLLECTING  │  GoalNear(dropPos, 1); wait for pickup
  └──────┬──────┘
    count met?
    ┌─────┴─────┐
    yes         no
    ▼           ▼
┌──────┐   (back to SEARCH)
│ DONE │
└──────┘
```

Phase transitions and their triggers:

| Transition | Trigger | Directive output |
|---|---|---|
| IDLE → SEARCH | first onTick after submit | none (search is synchronous) |
| SEARCH → MOVING | target found within radius | `GoalNear(target, 2)` |
| SEARCH → FAILED | no target in radius, or all targets skipped | none (terminal) |
| MOVING → DIGGING | PathingBehavior SUCCESS (within stand-off range) | `GoalNear(target, 2)` (hold position) |
| MOVING → SEARCH | STUCK or timeout (per-target budget) | none (re-search) |
| DIGGING → COLLECTING | target block reads air | `GoalNear(dropPos, 1)` |
| COLLECTING → SEARCH | inventory count increased (pickup confirmed) | none (re-search) |
| COLLECTING → DONE | inventory count >= requested | none (terminal) |

### 3.4 Search strategy

Search is a synchronous spiral scan around the bot's current position,
using `WorldView.getBlock(pos, LIVE)`. The scan radius is configurable
(default 16, matching the scan command default). Blocks are collected
into a list, sorted by Chebyshev distance, and the nearest is selected.

Why spiral and not volume-read: the `blocks` volume command is a
harness-facing wire verb; the Process reads the WorldView directly
(boundary A: core/ has zero MC imports, but WorldView is the api-layer
read surface). The spiral is simple and terminates fast when the target
is common.

Skipped targets (STUCK or timeout) are marked in a per-mission
skip-set so the next SEARCH pass doesn't re-pick them. The skip-set
clears when the mission completes or is cancelled.

### 3.5 Collection (the auto-pickup assumption)

Vanilla auto-pickup: when a player (or bot body with an inventory
binding) walks within 1 block of a dropped item entity, the item is
absorbed into the inventory. MineProcess relies on this:

1. After the block turns to air, the drop position is the block's
   position (drops spawn at the block center).
2. COLLECTING phase outputs `GoalNear(dropPos, 1)` — stand on or
   adjacent to the drop.
3. After a short pickup window (configurable, default 20 ticks = 1s),
   check `WorldView.getInventory()` for the target item count.
4. If count increased → pickup confirmed, proceed.
5. If count did not increase after the window → the drop may have
   fallen into lava / despawned / been picked up by another entity.
   Mark this target as collected-but-no-drop, proceed to SEARCH.

This is honest: the mission does not guarantee every mined block yields
a drop, only that it tried. The final count may be less than requested
if drops were lost; in that case the mission ends FAILED with reason
"collected N of M (drops lost)".

### 3.6 Timeout budgets

Three nested budgets:

| Budget | Scope | Default | Source |
|---|---|---|---|
| Mission timeout | entire mine task | 1200 ticks (60s) | wire param, mirrored from DigCommandHandler |
| Per-target move budget | MOVING phase for one target | 200 ticks (10s) | internal constant |
| Pickup window | COLLECTING phase wait | 20 ticks (1s) | internal constant |

Per-target budget exhaustion does NOT fail the mission; it skips the
current target and returns to SEARCH. Only the mission timeout or an
empty search result fails the mission.

## 4. Wire and CLI surface

### 4.1 Wire command

```
/bot mine <block_type> <count> [timeoutTicks]
```

- `block_type`: registry id, e.g. `minecraft:stone` (the Process
  compares against `BlockSnapshot.blockId()`)
- `count`: positive integer, how many to collect
- `timeoutTicks`: optional, default 1200 (mirrors DigCommandHandler)

Returns `{ok: true, task: "<taskId>"}` on submit, or
`{ok: false, reason: "..."}` on rejection (no active bot, invalid
block id, count <= 0).

Completion is announced via the existing TerminalMission path:
TASK_COMPLETED or TASK_FAILED with the taskId in attrs (same shape as
goto/dig).

### 4.2 CLI

```
mc write /tasks/mine "minecraft:stone:10"
```

Value format: `<block_type>:<count>`. The CLI parses this, calls
`/bot mine <block_type> <count> [timeout]`, and prints the taskId.
`mc wait <taskId>` blocks until completion (same as goto/dig).

### 4.3 Events

No new event kinds. MineProcess reuses:
- TASK_COMPLETED / TASK_FAILED (TerminalMission path, MissionReporter)
- BLOCK_BROKEN (DigMission claim path, same as DigProcess — emitted
  once per mined block, with the MineProcess taskId)

The harness can correlate BLOCK_BROKEN events to the mine task via the
taskId attr, giving per-block progress without a new event kind.

## 5. Implementation sequence

1. **DigMission interface** + DigProcess refactor (zero behavior change)
   + BotController line 517 change. Compile + existing tests green.
2. **MineProcess** with the full state machine. Offline unit tests:
   state transitions, skip-set, count tracking, timeout behavior.
3. **MineCommandHandler** (mirrors DigCommandHandler: register/cancel/
   sweep/BLOCK_BROKEN listener) + McBotServer wiring + `/bot mine`
   branch in BotCommands.
4. **CLI** `write /tasks/mine` + mock tests.
5. **Live verification**: spawn bot on a stone platform, `/bot mine
   minecraft:stone 3`, confirm TASK_COMPLETED + 3 BLOCK_BROKEN events
   + inventory has 3 cobblestone (note: stone drops cobblestone, not
   stone — the mission tracks the drop item, not the mined block; this
   is a v1 limitation documented in §6).

## 6. Deferred with reopen

- **Tool requirement**: blocks that require a tool (diamond ore needs
  iron pickaxe, etc.) currently mine but drop nothing. The mission
  should check tool-in-inventory via equip before starting, or fail
  fast with "requires <tool>". Reopens when a tool-aware block table
  exists.
- **Silk touch / fortune**: enchantment-aware drops need NBT inspection
  of the held item. Reopens with the tool-requirement work.
- **Vein mining**: optimize by following connected same-type blocks
  instead of re-searching each time. Reopens when a user demonstrates
  the per-target search overhead is measurable.
- **ChopProcess / HuntProcess**: same DigMission pattern, different
  search (entities for hunt, log blocks for chop) and collection
  (hunt needs attack, which is 0013 deferred). Reopens after MineProcess
  is proven and attack lands.
- **Drop item vs mined block mismatch**: stone → cobblestone, grass
  block → dirt (with shears → grass), etc. v1 tracks the mined block
  id for search but the drop item for count. A proper block→drop table
  is deferred; v1 documents the mismatch and lets the harness request
  the drop item directly (e.g. `/bot mine minecraft:cobblestone 10`
  searches for stone but counts cobblestone — the search predicate and
  count predicate are the same in v1, which is a known simplification).

## 7. Verification criteria

- Offline: MineProcessTest covers all state transitions, skip-set
  behavior, per-target timeout skip, mission timeout fail, count-met
  success.
- Offline: DigProcessTest still passes (refactor is behavior-preserving).
- Offline: WireVocabularyGateTest pin updated for the new `/bot mine`
  verb and any new attrs (none expected — reuses existing kinds).
- Live: `/bot mine minecraft:stone 3` on a stone platform yields
  TASK_COMPLETED, 3 BLOCK_BROKEN events, inventory count >= 3.
- Live: `/bot mine minecraft:diamond_block 1` with no diamond blocks
  in range yields TASK_FAILED with reason "no minecraft:diamond_block
  within 16".
