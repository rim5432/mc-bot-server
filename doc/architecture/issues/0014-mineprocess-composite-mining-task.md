---
title: MineProcess - composite mining task with search-mine-collect loop
last_verified: 2026-09-03
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/api/process/DigMission.java
  - src/main/java/com/mcbot/mcbotserver/core/process/DigProcess.java
  - src/main/java/com/mcbot/mcbotserver/core/process/MineProcess.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java
  - src/main/java/com/mcbot/mcbotserver/core/command/MineCommandHandler.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotAssembly.java
  - src/main/java/com/mcbot/mcbotserver/McBotServer.java
  - tool/harness/mc.py
status: open (filed 2026-08-27, contract corrected same day after the post-landing review; first composite task; absorbs 0013's deferred composite-tasks item; multi-phase pattern precedent is DefendProcess, 0007)
related:
  - doc/architecture/issues/0010-hungryprocess-food-acquisition-planner.md
  - doc/architecture/issues/0013-world-interaction-layer.md
  - doc/architecture/issues/0009-block-capability-dig.md
---

# Issue 0014: MineProcess - composite mining task

## 1. Scope and lineage

Moved from 0013 deferred: "Composite tasks (mine / chop / hunt): 0010
pattern first." 0010 remains in design phase; the landed multi-phase
Process precedent is DefendProcess (0007), which is the actual
pattern reference for this first composite task. MineProcess is the
v1 implementation; chop and hunt follow once the pattern is proven.

The task: given a block type and a count, find nearby instances of
that block, walk to each, mine it, walk over the drop, and repeat
until the requested number of blocks is broken. Termination counts
breaks, not inventory items: a block's drop id routinely differs
from its block id (stone drops cobblestone, ores drop raw items), so
an inventory count keyed by the block id can never rise. Drop pickup
is best-effort. This is the simplest composite task
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

(Count = blocks broken, incremented at the DIGGING air read.)

Phase transitions and their triggers:

| Transition | Trigger | Directive output |
|---|---|---|
| IDLE → SEARCH | first onTick after submit | none (search is synchronous) |
| SEARCH → MOVING | target found within radius | `GoalNear(target, 2)` |
| SEARCH → FAILED | no target in radius, or all targets skipped | hold goal (terminal) |
| MOVING → DIGGING | PathingBehavior SUCCESS (within stand-off range) | `GoalNear(target, 2)` (hold position) |
| MOVING → SEARCH | STUCK or timeout (per-target budget) | none (re-search) |
| DIGGING → COLLECTING | target block reads air (break counted and recorded) | `GoalNear(dropPos, 1)` |
| DIGGING → SEARCH | dig budget expired, or cell unloaded mid-dig (skip target) | none (re-search) |
| COLLECTING → SEARCH | pickup window elapsed, breaks < requested | none (re-search) |
| COLLECTING → DONE | breaks >= requested | hold goal (terminal) |

### 3.4 Search strategy

Search is a synchronous cube scan around the bot's live position,
using `WorldView.getBlock(pos, LIVE)`. The radius is fixed at 16 in
v1 ((2r+1)^3 ≈ 36k cell reads per search entry — acceptable at one
entry per target acquisition, not per tick). Unloaded cells read as
null and are skipped. Candidates are sorted by Chebyshev distance;
the nearest wins.

The bot's position comes from a `Supplier<CellPos>` injected by the
assembly (the body pose). WorldView deliberately exposes no
self-position read (boundary A: the view is the world, not the
actor), so the supplier is the only honest center. A null live read
fails the mission ("no bot position"); the position captured at
construction anchors failure-tick holds, because Directive rejects a
null goal and a failure tick must not throw (that would trip the
ADR-0005 crash latch over a mission-level failure).

Why a direct scan and not the `blocks` volume verb: the volume
command is a harness-facing wire verb; the Process reads the
WorldView directly (boundary A: core/ has zero MC imports, but
WorldView is the api-layer read surface).

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
3. After the pickup window (default 20 ticks = 1s), the mission
   returns to SEARCH, or DONE when the break count is met. The
   inventory is never consulted: termination counts blocks broken,
   because the drop id routinely differs from the block id and an
   inventory count keyed by the block id can never rise.

This is honest: the mission guarantees the blocks were broken and
discloses each break (one BLOCK_BROKEN per block); it does not
guarantee drops survived to be picked up. Lost drops are not tracked
in v1 (see §6).

### 3.6 Timeout budgets

Three nested budgets:

| Budget | Scope | Default | Source |
|---|---|---|---|
| Mission timeout | entire mine task | 2400 ticks (120s) | wire param; composite walks + digs per block, so the budget is per-mission — dig's 1200 does not fit N blocks |
| Per-target move budget | MOVING phase for one target | 200 ticks (10s) | internal constant |
| Per-target dig budget | DIGGING phase for one target | 400 ticks (20s) | internal constant; unbreakable or too-slow targets are skipped, not fatal |
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
  compares against `BlockSnapshot.blockId()`). The Brigadier type is
  string(), so the id MUST be quoted — a bare colon does not parse
  (same rule as the menu verbs' item ids; word() is wrong here)
- `count`: positive integer, how many blocks to break
- `timeoutTicks`: optional, default 2400

Returns `{ok: true, task: "<taskId>"}` on submit, or
`{ok: false, reason: "..."}` on rejection (no active bot,
count <= 0). An unknown-but-well-formed block id is not rejected at
submit; the mission fails honestly at its first search with
"no <id> within 16".

Completion is announced via the existing TerminalMission path:
TASK_COMPLETED or TASK_FAILED with the taskId in attrs (same shape as
goto/dig).

### 4.2 CLI

```
mc write /tasks/mine "minecraft:stone:10"
```

Value format: `<block_type>:<count>` (split on the LAST colon — the
registry id itself contains one). The CLI quotes the id on the wire
(`/bot mine "minecraft:stone" 10 2400`) and prints the taskId.
`mc wait <taskId>` blocks until completion (same as goto/dig).

### 4.3 Events

No new event kinds. MineProcess reuses:
- TASK_COMPLETED / TASK_FAILED (TerminalMission path, MissionReporter)
- BLOCK_BROKEN (DigMission claim path, same as DigProcess — emitted
  once per mined block, with the MineProcess taskId)

The harness can correlate BLOCK_BROKEN events to the mine task via the
taskId attr, giving per-block progress without a new event kind.

## 5. Implementation sequence

Executed 2026-08-27 in five steps as planned: DigMission interface +
DigProcess refactor (behavior-preserving), MineProcess state machine
with offline tests, MineCommandHandler + `/bot mine` wire verb, CLI
`write /tasks/mine`, then live verification on a dry pad. The
step-by-step plan text lives in git history.

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
- **Item-count semantics**: v1 terminates on blocks BROKEN, not
  items collected — the block→drop mapping (stone → cobblestone,
  ores → raw items) never enters termination. If a harness needs
  "N items", the block→drop table reopens; until then the harness
  maps ids itself (request the block that drops the wanted item).
- **Drop-loss tracking**: pickup is a walk-over best-effort; drops
  that roll or fall away (observed live on a floating pad) are
  silently uncollected. Reopens with drop-entity targeting
  (GoalNear on the item entity, not the block cell).
- **Event-ring capacity under motion**: a walking bot pushes
  STATE_PUSH per cell crossing; sustained motion evicts older events
  from the ring. EVENT_GAP / EVENT_DROPPED fire per the cursor
  contract (no silent drop), but task events can churn out mid-walk,
  so a harness polling at multi-second intervals during motion may
  need to re-anchor. Reopens with a capacity or push-throttle ruling
  on the disclosure channel.
- **Bot death mid-mission**: the body's death stops the tick
  pipeline, so an in-flight mission never reaches a terminal state
  and its task stays pending from the harness's view. Reopens with a
  respawn-cleanup ruling (goto/dig share the shape; observed during
  the live round, isolation untested).
- **Stale live suppliers after death**: `/bot status` answered from
  the dead body's last cached snapshot (hearts 0) while the root
  read verbs honestly answered "no active bot" — the two live
  suppliers disagree on liveness. Rides the respawn-cleanup ruling
  above.

## 7. Verification status

Offline: MineProcessTest pins state transitions, skip-set, per-target
timeout skip, mission timeout fail, break-count success,
pre-owned-stock non-termination, injected-position centering;
DigProcessTest proves the refactor behavior-preserving;
WireVocabularyGateTest lists MineCommandHandler in PRODUCER_FILES and
keeps the BLOCK_BROKEN attrs pinned.

Live (2026-08-27): `/bot mine "minecraft:dirt" 3` against three
placed targets returned TASK_COMPLETED with three BLOCK_BROKEN events
(pinned attrs, exact positions) and all three cells read air after;
`/bot mine "minecraft:diamond_block" 1` with none in range returned
TASK_FAILED with reason "no minecraft:diamond_block within 16
(0 skipped)".
