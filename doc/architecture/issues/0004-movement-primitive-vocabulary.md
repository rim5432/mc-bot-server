---
title: Movement primitive vocabulary - misnomers, fluid propulsion, missing key semantics
last_verified: 2026-08-23
covers:
  - src/main/java/com/mcbot/mcbotserver/core/pathing/BasicMoves.java
  - src/main/java/com/mcbot/mcbotserver/api/actor/Intent.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
status: open
related:
  - doc/architecture/boundaries.md
  - doc/architecture/function-map.md
  - doc/architecture/issues/0003-sneak-pose-and-edge-walk.md
  - doc/reference/baritone-notes.md
---

# Issue 0004: Movement primitive vocabulary

## 1. Scope

The acceptance rehearsal stranded the body in a lake: the planner
produced a swim route but the executor could not propel the body
through deep fluid. Reviewing why surfaced a wider question the user
raised directly: is the movement primitive set itself designed
correctly for Minecraft, or does it import verbs the game does not
have while lacking the ones players actually use?

This issue inventories the current vocabulary against vanilla
mechanics (verified in the decompiled 1.20.1 tree), names the gaps,
and proposes fixes at three depths: rename, executor repair, and new
primitives. It deliberately decides nothing frozen - each proposal
needs an explicit ruling because two of them touch api signatures.

## 2. Current inventory vs vanilla

| Primitive | Planner edge | Executor input today | Vanilla counterpart |
|---|---|---|---|
| Walk | same-level standable | `Move(fwd)` | walking |
| Diagonal | corner-swept same-level | `Move(fwd+turn)` | walking |
| ClimbUp | +1 standable cell | `Move(jump)`, fires only when `onGround()` | **none by this name** - this is auto-jump / step-up |
| Drop (<=3) | lower landing cell | nothing (gravity) | falling |
| Swim | horizontal into liquid | `Move(fwd)` only | wading/swimming |
| SwimUp | +1 inside liquid column | `Move(jump)` - never fires in fluid | buoyancy via the `jumping` flag |

Two structural observations fall out of the table:

1. The set contains only *geometry-consuming* primitives. Every
   Minecraft traversal tool that modifies the world - pillar-up
   (place beneath self), bridge (place ahead), tunnel (dig through) -
   has no primitive, no intent kind, and no inventory to draw from.
   Baritone's core long-travel repertoire is exactly these; see
   baritone-notes for the movement list we distilled.
2. The set's vertical control is binary (jump yes/no). Players get
   four vertical keys: jump, sneak-to-descend (in fluids and on
   edges), sprint-jump (distance), and release-to-fall.

## 3. Findings

### F1 - ClimbUp is a misnomer and collides with a real trait

Vanilla "climbing" is a specific mechanic:
`LivingEntity.handleRelativeFrictionAndCalculateMovement` grants
`y = 0.2` climb motion only when `(horizontalCollision || jumping)
&& onClimbable()` - ladders, vines, powder snow. Our `ClimbUp`
instead models "jump-step onto one higher standable cell"; it never
reads any block property.

Meanwhile `BlockTraits.climbable()` already exists with zero
consumers. The collision is live: a future agent adding a ladder
movement must either duplicate the name (`LadderClimb`?) or be
misread into "fixing" `ClimbUp` to consult the trait it ignores.

**Proposal P1 (S, planner-only)**: rename `ClimbUp` -> `StepUp`
(describe strings, tests, docs travel with the rename). Reserve the
word *Climb* for a future trait-driven ladder/vine Movement per
decision 19a. No behavior change, pure vocabulary hygiene.

### F2 - Fluid propulsion is planned but physically unreachable

Verified chain:

- Vanilla buoyancy: `LivingEntity.aiStep` (~line 2615) reads the
  `jumping` flag and, when the body sits in fluid, calls
  `jumpInFluid(...)` - upward thrust that needs **no ground contact**.
- Our binding: `BotBodyEntity.customServerAiStep` fires
  `jumpFromGround()` only under `driveJump && onGround()`, and
  `setDrive(jump=false)` resets `setJumping(false)` every tick. In
  fluid `onGround()` is false, so the driveJump request dies
  silently; the vanilla buoyancy branch can never fire.
- Forward drift in water does exist: `travel()`'s water branch uses
  `moveRelative(0.02 * SWIM_SPEED attribute)` with 0.8 damping -
  roughly one fifth of land acceleration. Sinking dominates, the
  body settles on the lake floor, and the progress fuse declares
  STUCK on a route the planner honestly believed swimmable.

Consequence: `Swim`/`SwimUp` are currently *planner-only fiction*
beyond wading depth. The existing `crossesWaterTrench` gametest does
not catch this - its trench is one cell deep, so the bot wades on
the bottom; it verifies the search edge, not deep-fluid locomotion.

**Proposal P2 (S, adapter-only)**: in `customServerAiStep`, branch
on fluid state: in fluid, express driveJump as
`setJumping(true)` and let vanilla's `jumpInFluid` do the work; keep
today's `jumpFromGround` path for land. Add a deep-pool gametest
(>=2 cells of water requiring a SwimUp chain, then shore exit).
This unblocks the 10-minute acceptance run without touching any
frozen signature.

### F3 - The drive triple under-expresses the body's actual keys

`Intent.Move(forward, strafe, jump, sneak)` already declares sneak,
but `BindingActor.flush()` reads only forward/strafe/jump - the
field is dropped on the floor. There is no sprint representation
anywhere, although vanilla sprinting changes water friction (0.8 ->
0.9) and our cost model silently assumes walk speed. And there is no
sink input: underwater descent is only expressible as "do nothing
and let gravity win", which is not a decision the bot made.

Player-parity answer would be: sneak doubles as sink-in-fluid (that
is what the sneak key does for a player), sprint becomes a Move
modifier or a speed scalar on the claim. Both reshape an api record.

**Proposal P3 (M, boundary-touching)**: wire sneak through the
binding as part of issue 0003 (it needs the sneaking AABB anyway),
and rule sink semantics there too - sneak-as-sink keeps the record
shape unchanged, which matters because `Intent` lives behind frozen
boundary A. Sprint stays out unless a scenario demands it; note the
cost-model assumption in `BasicMoves` Javadoc meanwhile.

### F4 - No world-modification primitives exist at all

Pillar-up, bridge, tunnel: none has a Movement, an Intent kind, or
an inventory to place from. `Intent.Use` currently resolves melee
only (`BindingActor.resolveMelee`); `selectedSlot` is recorded
state with no item semantics. The function-map Locomotion GAP row
already names parkour / pillar-up / door handling as expected
growth ("each lands as a Movement + traits data"), so the direction
is pre-agreed - what is missing is the dependency order:

1. Inventory model (workplan Stage 2 item) - placement needs to know
   what the hand holds.
2. Block-interaction intent kinds (Place / Break) distinct from
   melee Use - overloading `Use(pressing)` would conflate aim
   semantics the ROT channel cannot carry alone.
3. Traits for diggability / hardness-based cost, per decision 19a's
   Shape+Traits gate.
4. Then Movements: PillarUp, Bridge, Tunnel as planner edges whose
   viability includes "hand holds a placeable" - the first
   primitives whose viability depends on bot state, not just world.

**Proposal P4 (L, staged)**: confirm the sequencing above as the
backlog entry text; explicitly out of scope until the inventory item
lands. Do not let F2's fix grow into an early attempt at this.

### F5 - Cost model honesty after propulsion exists

`Swim` cost 1.5 and `SwimUp` cost 2.0 were chosen when neither was
executable. Once P2 lands, measured ticks-per-cell will differ from
walk (water base speed 0.02 vs ~0.1 land); the numbers should be
re-derived from the gametest rather than kept as guesses.

**Proposal P5 (S, doc-only now)**: add an owned deferred-work
comment in `BasicMoves` stating fluid costs await post-P2
measurement; revisit at the acceptance rerun.

## 4. Boundary tension to rule on before coding

`BasicMoves` records implement `api.pathing.Movement`; `Intent`
lives behind boundary A where "signatures + protocol frozen;
vocabulary may grow" applies. Concretely:

- P1 renames a core-pathing record - internal, no ruling needed
  beyond review.
- P2 touches only the adapter - no signature change.
- P3's sneak wiring uses an existing field - no change; any sprint
  or explicit-sink field WOULD amend `Intent.Move`'s signature and
  therefore belongs at the Stage review, not in a drive-by edit.
- P4 adds Intent kinds - same freeze rule; the proposal here is
  backlog text only.

Per AGENTS.md §0.4: if a change seems to require amending a frozen
boundary, stop and write the workplan follow-up instead. That is
what sections P3/P4 above are.

## 5. Decision requests

1. **D1**: Approve P1 rename (`ClimbUp` -> `StepUp`) now?
2. **D2**: Approve P2 fluid-jump executor fix + deep-pool gametest
   as the immediate follow-up (it gates the acceptance run)?
3. **D3**: Sink semantics - sneak-as-sink (player parity, no
   signature change) folded into issue 0003, or an explicit third
   vertical axis on Move (signature amendment, Stage review)?
4. **D4**: Sprint in v1 scope, or deferred with the cost-model note?
5. **D5**: Confirm P4 sequencing (inventory first, then Place /
   Break intents, then Movements) as the backlog entry wording?
