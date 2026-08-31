---
title: World interaction layer - perception reads, dig task, place verb, health field
last_verified: 2026-08-31
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/adapter/WorldCommands.java
  - src/main/java/com/mcbot/mcbotserver/api/world/WorldView.java
  - src/main/java/com/mcbot/mcbotserver/api/state/BotState.java
  - src/main/java/com/mcbot/mcbotserver/core/state/BotStateJson.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotAssembly.java
  - src/main/java/com/mcbot/mcbotserver/core/process/DigProcess.java
  - src/main/java/com/mcbot/mcbotserver/core/command/DigCommandHandler.java
  - src/main/java/com/mcbot/mcbotserver/McBotServer.java
  - tool/harness/mc.py
status: open (filed 2026-08-26 with six evidence-pinned rulings; slice 1-4 sequenced; absorbs 0012's health/freeSlots pending rows and 0009's F3/F4 deferred demand)
related:
  - doc/architecture/issues/0007-player-parity-interaction.md
  - doc/architecture/issues/0009-block-capability-dig.md
  - doc/architecture/issues/0010-hungryprocess-food-acquisition-planner.md
  - doc/architecture/issues/archive/0012-work-block-usability-and-harness-menu-surface.md
---

# Issue 0013: World interaction layer

## 1. Problem

After 0012 the Unix surface covers self state, menu interaction,
task submission (goto), and recipe materialization - but the bot is
blind to the world (only `cat /player/pos`) and cannot act on it (no
dig / place / use / attack verbs). User direction 2026-08-26: the
surface needs a world interaction layer; every expansion is a new
PATH under the existing six verbs, never a new verb.

Scope ownership, verified against the neighbors: 0007 owns the
internal interaction axis (Intent kinds, executors, facade) and does
NOT claim boundary-D verbs (0007 "Intent kinds grow... this issue is
the backlog text until the review lifts it"); 0009 F3 (BLOCK_BROKEN
disclosure) and F4 (missions claiming INTERACT) are explicitly
unqueued demand waiting for a task vocabulary - this issue absorbs
both; 0010 owns foodData lifecycle (hunger stays there); 0012's
pending rows for `cat /player/health` and `freeSlots` on BotState are
absorbed here to avoid a second owner.

## 2. Verified ground (file:line evidence, gathered 2026-08-26)

- DigExecutor.dig is ONE tick of a held claim: progress accumulates
  via heldTicks, break fires at progress >= 1.0 (DigExecutor.java
  117-125); state persists across calls (target/heldTicks/lastStage,
  61-67); release() resets everything (155-167). Bare-hand gravel
  ~18 ticks (DigOnSuffocationRule.java 22-24).
- Claims expire at every flush (ChannelArbiter.java 42-48): a dig
  driver MUST re-submit Intent.Dig each tick or BindingActor calls
  dig.release() and progress is lost (BindingActor.java 167-174).
  Today only the reflex layer re-claims (BotController.preemptDigClaims
  597-613); no core/behavior class references Intent.Dig (grep empty).
- place is a one-shot rising-edge action inside one flush
  (BindingActor.java 154-166, InteractBlockExecutor.java 52-53) with
  silent no-op on every precondition failure and no return value
  (InteractBlockExecutor.java 116-121) - synchronous callers must
  verify by post-state read.
- MeleeResolver is package-private, cone/nearest self-targeting, no
  entity-id parameter (MeleeResolver.java 30, 39-64, 79-130).
- WorldView already exposes the read surface: getBlock(pos, mode)
  -> BlockSnapshot(pos, blockId) (WorldView.java 39),
  getEntities(center, radius, mode) -> List<EntitySnapshot(id=UUID
  stable across ticks, type, pos, health, maxHealth)>
  (WorldView.java 84; EntitySnapshot.java 21-26). BindingWorldView
  includes the bot itself, no cap, LivingEntity only - no item
  entities (BindingWorldView.java 160-175).
- BotState deliberately excludes continuous fields because
  ChangeDetectingStateChannel compares full records and would push
  every tick (BotState.java 12-16, boundaries.md 262-266). Regen is
  1 HP / 20 ticks (BotBodyEntity.java 257-262). FoodData is
  Player-only; BotBodyEntity has no food carrier (grep zero hits).
- GotoProcess is the task precedent: TerminalMission + tick budget +
  sticky terminal states, process never reads the world
  (GotoProcess.java 36-110). CommandBus.Handler: validate (sync
  rejection) + execute (reports via bus.finishTask)
  (CommandBus.java 34-53).
- Wire table growth is issue-governed: decision 28 "the table lives
  in the issue and grows by one row per new process kind, decided in
  the shipping issue, never as an ad-hoc brigadier branch"
  (boundaries.md 661-668). Decision 31(b) re-evaluation fires when
  the menu-family exceeds ~12 verbs (boundaries.md 731-738).
- RCON response chunks at 4096 bytes (RconClient.sendCmdResponse,
  verified in decompiled 1.20.1) - volume reads must cap under it.

## 3. Design

### Rulings (2026-08-26, self-ruled on the evidence above)

1. **dig is a task (submit + wait), not a synchronous action.**
   Multi-tick executor + per-tick claim expiry + the GotoProcess
   precedent. The wire verb `/bot dig x y z [timeoutTicks]` rides
   CommandBus exactly like goto.
2. **place is a synchronous verb (MenuCommands pattern) with
   post-state verification.** One-shot executor suits sync RPC; the
   silent-no-op gap is closed by reading the placement cell after
   the call and answering placed:true/false with a reason.
3. **attack is deferred.** MeleeResolver needs widening (id
   targeting, visibility) - the most expensive primitive, not in
   this issue's slices.
4. **Perception reads are synchronous read-only commands over
   WorldView.** `block`, `blocks` (volume, capped 4x4x4 = 64 cells
   ~2.5KB), `entities [radius] [limit]` mirroring scan's shape
   (distance sort, truncated flag, self included and flagged).
5. **health joins BotState as bucketed hearts (vanilla scale, max 10 = 20 HP); hunger
   does not.** Bucketing preserves the no-push-every-tick contract
   (evidence: change-detect compares full records); hunger has no
   carrier and 0010 owns foodData. freeSlots rides the same change
   (same snapshot loop as itemCounts).
6. **`mc events --follow` is pure CLI (zero wire).** Poll loop with
   peek semantics (never advances the operator bookmark), --idle
   termination, GAP/DROPPED reconcile signals already on the wire.

### Decision 31(b) re-evaluation (recorded)

Slice 3 pushes the synchronous-family verb count past ~12 (menu 9 +
scan + recipes + recipes list + block + blocks + entities + place).
Re-evaluation outcome, recorded here: the dual-protocol shape STAYS
- reads and instant actions answer synchronous JSON; duration verbs
 ride CommandBus + event stream; a unified error model is not yet
 earned (no second consumer, no new payload mechanism). Next
 re-evaluation triggers unchanged.

### Wire translation (D1-style rows)

Namespace finding (live-verified): the sync family lives at the
command ROOT (block / blocks / entities / place, same as menu /
scan / recipes from 0012), while the task family rides /bot (status /
goto / dig / cancel / stop / events / reset). The CLI sends the real
spelling.

| CLI | Wire | Family | Status |
|---|---|---|---|
| `cat /blocks/<x,y,z>` | `block x y z` | sync read | live 2026-08-27 |
| `cat /nearby` | `entities 8 32` (client aggregation; the proposal's view verb collapsed into cat) | sync read | live 2026-08-27 |
| `mc events --follow` | poll `/bot events` (peek) | CLI-only | live 2026-08-27 |
| `cat /player/health` | `/bot status` (healthHearts field) | state | live 2026-08-27 (10 hearts = 20 HP full) |
| `cat /player/inventory/free` | `/bot status` (freeSlots field) | state | live 2026-08-27 |
| `ls /entities/` | `entities [radius] [limit]` | sync read | live 2026-08-27; lines carry the id= column since ledger 39 |
| `write /entities/<id>/attack` | `/bot attack <id> [timeoutTicks]` | task | code 2026-08-28 (ledger 39: directed engagement - the harness hands the target, verdict attrs carry targetId, NO_SUCH_ENTITY fails fast, a health-zero sighting completes as SUCCESS (ledger 40), absence past grace is still the conservative TARGET_ESCAPED). Live pass rides the staged gametest batch |
| `write /stations/crafting@<pos>/recipe "a,b,c"` | `menu craft a,b,c` | sync action | code 2026-08-28 (ledger 39: batch chain in the GIVEN order, one menu session, first failure stops with partial results; reply shape `crafted[]`). Live pass rides the staged gametest batch |
| `write /blocks/<x,y,z> "air"` | `/bot dig x y z [timeoutTicks]` | task | live 2026-08-27 (TASK_COMPLETED + BLOCK_BROKEN, block gone); CLI path folded from `/tasks/dig` 2026-08-28 (doc 10) |
| `write /blocks/<x,y,z> "<blockid>[@face]"` | `place x y z face` | sync action | live 2026-08-27 (rejection paths + success path via equip); folded from `/actions/place` 2026-08-28 - the value is a post-state contract (receipt block must match) |
| `write /player/hotbar <slot>` | `equip <slot>` (0..8) | sync action | live 2026-08-27 (unlocks place success path; updates body.selectedSlot + inventory mirror); folded from `/actions/equip` 2026-08-28 |
| `write /blocks/<x,y,z>/use "<face>"` | `use x y z face` | sync action | code 2026-08-27 (vanilla right-click chain via the facade, issue 0007 Phase 2; the consumed verdict is the receipt - a pressed button springs back, so no post-state read exists). Live pass rides the staged gametest batch; folded from `/actions/use` 2026-08-28 |
| `write /player/sneak "on/off"` | `sneak on/off` | sync action (persistent latch, equip precedent) | code 2026-08-28 (crouch pose + shift courtesies on the body; edge-guard is Player-only in vanilla and deliberately NOT claimed - porting it is its own item). Live pass rides the staged gametest batch; folded from `/actions/sneak` 2026-08-28 |
| `write /player/held/use` | `use-item` | sync action | code 2026-08-28 (vanilla Item.use against the POV raycast - the bucket fill/empty point is the look target, so ROT-aim first; hold-type items and food answer used:false with a reason). Live pass rides the staged gametest batch; folded from `/actions/use-item` 2026-08-28 |
| `write /blocks/<x,y,z>/sleep` | `sleep x y z` | sync action | code 2026-08-28 (verify-and-skip: real bed + reach + nighttime, then the device completes the vanilla all-sleepers night-skip the engine cannot see - the body is a PathfinderMob outside the player list. Recorded v1 gaps: no monster-proximity refusal, no spawn binding). Live pass rides the staged gametest batch; folded from `/actions/sleep` 2026-08-28 |

Live findings recorded: (1) GoalNear(target, 3) admits chebyshev-3
stops that are ~5.2 euclidean eye-to-block - beyond DigExecutor's 4.5
reach; fixed to range 2 (3864b28). (2) The proposal's `view` verb
collapsed into `cat /nearby` per the no-new-verbs principle.

BLOCK_BROKEN event (absorbs 0009 F3): emitted by the dig completion
path with attrs taskId / posX / posY / posZ / blockId - KIND_ATTRS
pin updated in the same change.

## 4. Sequencing

1. **Slice 1** - perception reads + follow (near-zero cost).
2. **Slice 2** - healthHearts + freeSlots into BotState; pin +
   boundaries.md state section + BotStateJson + CLI in one change.
3. **Slice 3** - dig task: seam verification first (Directive shape
   + PathingBehavior claim flow -> DigBehavior vs controller-path,
   criterion: mirror the closest precedent, prefer a behavior so the
   controller stays orchestration-only; if Directive cannot carry a
   dig target, grow the api in the same change and record it here),
   then DigProcess + DigCommandHandler + `/bot dig` + BLOCK_BROKEN.
4. **Slice 4** - place sync verb with post-state verification.
5. Live verification per slice (mc verbs only on the agent path).

## 5. Deferred with reopen

- attack-by-id: reopens when MeleeResolver gains a targeted variant
  or the harness presents a combat scenario.
- use-item / eat: owned by 0010 (foodData lifecycle).
- hunger field: no carrier today; reopens with 0010's food work.
- Terrain view beyond the capped 4x4x4 volume (ASCII maps, height
  maps): reopens when a skill demonstrates the token need.
- Unified error model across sync/task families: decision 31(b)
  triggers unchanged.
- Composite tasks (mine / chop / hunt): 0010 pattern first.
