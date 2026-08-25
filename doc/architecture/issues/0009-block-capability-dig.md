---
title: Block capability axis - dig first, pulled forward by the suffocation escape
last_verified: 2026-08-25
covers:
  - doc/architecture/function-map.md
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/api/actor/Channel.java
  - src/main/java/com/mcbot/mcbotserver/api/actor/Intent.java
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ReflexAction.java
  - src/main/java/com/mcbot/mcbotserver/core/reflex/DigOnSuffocationRule.java
  - src/main/java/com/mcbot/mcbotserver/core/actor/DigPacing.java
  - src/main/java/com/mcbot/mcbotserver/adapter/DigExecutor.java
status: open (slice 1 shipped 2026-08-25; the axis continues with 0007)
related:
  - doc/architecture/issues/0008-extreme-scenario-survival-framework.md
  - doc/architecture/issues/0007-player-parity-interaction.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/guide/workplan.md
---

# Issue 0009: Block capability axis - dig first

## 1. Scope and lineage

User ruling 2026-08-25: file this issue and start enriching the bot's
block capability. The motivating scenario is the gravel-collapse
suffocation escape (the 0008 F4 follow-through): a
`FallingBlockEntity` that lands becomes a solid block; if the body's
eye ends up inside it, `isInWall()` is true and suffocation deals
1.0F every tick with no interval. The only reliable escape is digging
the eye block out - and until this issue the carrier had zero dig
capability: `BotBodyEntity` exposes locomotion (`setDrive`), rotation
(`setTargetRotation`) and nothing that touches world blocks. That is
a capability gap, not something a Process can compose around.

Considered and superseded before landing: a deterministic pre-dig
interim (back up one cell against the facing, then jump, then FREEZE
and report). Dig shipped in the same change, so the interim ladder
never existed in a build; it is recorded here because it was the
honest fallback had the dig slice slipped.

Relationship to 0007: hold-to-mine was 0007 Phase 1 vocabulary (Stage
3, gated on the survival gate). This issue carves the DIG slice
forward, out of order, because the suffocation escape is
inventory-free - bare-hand mining needs no items, no menus, no
facade - while the rest of 0007 Phase 1 (InteractBlock use/place,
inventory, DropSelected) is not. 0007 keeps everything else and its
review agenda is unchanged; the INTERACT channel this issue adds is
exactly the channel 0007 section 6.2 hypothesized.

## 2. The vanilla dig mechanics (decompiled 1.20.1, verified)

- Survival digging is continuous progress, not a single action:
  `ServerPlayerGameMode` accumulates `perTickProgress * heldTicks`
  while the button holds on one block+face; break at `>= 1.0`.
- Per-tick progress (`Block.getDestroyProgress`):
  `digSpeed / destroySpeed / (correctTool ? 30 : 100)`; negative
  destroySpeed means unbreakable (progress 0), zero destroySpeed
  yields +Infinity (torch-class insta-pop, vanilla's own division).
- Bare hand: digSpeed 1.0, and the correct tool reduces to "the block
  drops without one" (`!requiresCorrectToolForDrops()`) because a
  hand is never the correct tool. Gravel/dirt/sand class (hardness
  0.5-0.6, no tool required): 15-18 ticks. Stone class (1.5, pickaxe
  required): 150 ticks - five times slower, still finite.
- `Player.getDigSpeed` situational modifiers: airborne /5, eyes in
  water without aqua affinity /5, haste/mining-fatigue effects.
- Crack animation: `level.destroyBlockProgress(breakerId, pos, stage)`
  with `stage = (int)(f * 10)`, broadcast only on change, -1 clears.
- The break itself: `level.destroyBlock(pos, drop=true)` - fires the
  2001 level event and drops inside that call.
- Server-authoritative facing: the server's destroy machinery never
  checks where the player looks; the client is trusted to keep the
  crosshair on the block. A bot's executor may do the same honestly.
- Stop-destroy shortcut: releasing the button at `f >= 0.7` still
  completes the break (client-lag compensation).

## 3. Design (as shipped, ledger 25)

### D1 - Fifth channel INTERACT, Intent.Dig rides it

USE stays entity-melee (decision 14 untouched; CombatBehavior zero
change). INTERACT carries held block interaction: a claim this tick
IS the button state - holding a dig means re-asserting `Dig(cell)`
every tick, which is exactly the per-tick claim discipline; nothing
persistent exists to clean up beyond the executor's progress state.
`Dig` carries only the target cell; face/hitPos belong to the
0007 right-click shapes (placement depends on the face), not to
breaking. A `Claim` mismatch (Dig on any other channel, or anything
but Dig on INTERACT) fails construction - the sealed vocabulary puts
that failure where it belongs.

### D2 - Adapter mining machine, core arithmetic

`DigExecutor` (adapter) owns the per-site state machine: retarget
resets progress, air at the site stops silently (someone else broke
it), out-of-reach claims never progress (4.5-block eye-to-center
gate, vanilla block reach), crack stages broadcast on change, the
break is `level.destroyBlock(pos, true)`, the body swings every
progressing tick (the client player's visible cadence).
`DigPacing` (core, zero MC imports) owns the arithmetic so layer-1
tests pin the numbers: gravel 18 ticks, stone 150, insta-pop, never,
airborne x0.2. Two deliberate deviations from vanilla, both born
from having no client: no stop-destroy 0.7 shortcut (claims are
ground truth; the break lands at exactly 1.0), and the Forge
`LeftClickBlock` / `onBlockBreakEvent` hooks are skipped (they are
Player-typed; no carrier Player exists until the 0007 facade
ruling - protection-mod interop defers with it). Water-eye and
effect modifiers are deferred the same way (v1 suffocation escape is
on dry land, no effects).

### D3 - The suffocation rule upgrades from FREEZE to DIG

`FREEZE_ON_SUFFOCATION` is renamed `DIG_ON_SUFFOCATION`: with dig
capability the rescue direction is KNOWN - the eye block - so the
deterministic self-rescue supersedes the halt whose entire argument
was "a guessed direction can push deeper". Priority 115 and the
10-tick hold stay (the 0008 F9 triage ladder is unchanged). The
board gains `suffocationBlock` (the eye cell, sensor-stamped,
null-safe in `beginTick`); a board that stamps the vital without the
position yields a targetless DIG decision, which the controller
degrades to the freeze hold - missing data must not mint a
dig-at-null. The controller maps DIG to three claims (MOVE hold,
ROT aim at the target - presentation only, the executor never reads
facing - and the INTERACT dig itself). During the post-rescue
hysteresis hold the eye is clear and the target is null, so those
ticks are the freeze hold. The rename is a hard parse error under
the old type string: a datapack still naming the stopgap must be
updated loudly (RuleTableGateTest pins both the rename and the
reload-parity lockstep).

Survival math, stated so nobody re-derives it: dirt-class escape
costs ~15-16 of the 20 health points (15 dig ticks + latency). That
is authentic vanilla - a real player hand-digging out of a burial
pays the same. An unbreakable or stone-class eye block outlasts the
20-tick window and the body dies holding the claim; the preemption's
TASK_PAUSED (which names the rule) is the siren either way.

## 4. Open questions

| # | Question | Status |
|---|---|---|
| F1 | Crashed-state dig: should MinimalReflex dig when the latch is set and the eye is buried? | Deferred with the 0008 F8 asymmetry argument: rare x rare, and dig needs executor wiring + an eye-cell feed - more than ADR-0005 D3's "a few ifs". Revisit if a crashed body ever dies buried. |
| F2 | Tool-accelerated digging: `getDestroySpeed` from a held item. | Blocked on 0007 Phase 1 (inventory/tool loadout). The pacing function already takes its inputs as parameters; the executor grows a main-hand supplier then. |
| F3 | BLOCK_BROKEN disclosure event (harness observability of digs). | Not queued. The suffocation preemption already discloses through TASK_PAUSED naming the rule; a dig-specific event earns its place when a mission digs (function-map section 5 harvest loops). |
| F4 | Missions claiming INTERACT (a DigProcess, harvest-and-place). | The channel is general and behaviors may claim it; nothing is queued until a task vocabulary needs it (function-map section 5 GAP row owns that demand). |

## 5. Sequencing

1. **Shipped 2026-08-25 (this issue's slice)**: D1-D3 as one change -
   channel + intent + claim pairing, DigPacing, DigExecutor, the
   rule rename, the controller mapping, the sensor eye-cell feed,
   the datapack row rename, and the in-engine proof
   (`digsFreeWhenSuffocating`: dirt-sealed eye, the body digs itself
   free with no scenario-side removal, resumes and completes the
   mission while health proves the escape raced real damage).
   Found-and-fixed en route: the 0008 suffocation scenario this one
   replaces fed world coordinates into `helper.setBlock` (which takes
   structure-local ones), so its seal never landed inside the box -
   a latent red that had never been run in-engine. The rig gained
   `GametestRig.toLocal` (origin subtraction; vanilla `relativePos`
   is a 180-degree transform, not the inverse of `absolutePos` for
   rotation-NONE tests) and the scenario now converts through it.
2. **Stays with 0007**: InteractBlock (use/place, face + hitPos),
   inventory, DropSelected - the rest of Phase 1, unchanged.
