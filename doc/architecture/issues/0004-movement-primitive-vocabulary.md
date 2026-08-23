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

Revision 2 folds in two inputs: a review that challenged the fluid
fix's inheritance assumptions (all resolved against the decompiled
tree, see R2 below), and a realism pass asking whether each verb
matches how an actual player drives the body. That pass changed one
ruling (D3), added finding F6, and reshaped the cost work.

## 2. Current inventory vs vanilla

| Primitive | Planner edge | Executor input today | Vanilla counterpart |
|---|---|---|---|
| Walk | same-level standable | `Move(fwd)` | walking |
| Diagonal | corner-swept same-level | `Move(fwd+turn)` | walking |
| ClimbUp | +1 standable cell | `Move(jump)`, fires only when `onGround()` | **none by this name** - this is a deliberate jump onto one higher cell |
| Drop (<=3) | lower landing cell | nothing (gravity) | falling |
| Swim | horizontal into liquid | `Move(fwd)` only | wading/swimming |
| SwimUp | +1 inside liquid column | `Move(jump)` - never fires in fluid | buoyancy via the `jumping` flag |

Two structural observations:

1. The set contains only *geometry-consuming* primitives. Every
   Minecraft traversal tool that modifies the world - pillar-up,
   bridge, tunnel - has no primitive, no intent kind, and no
   inventory to draw from. Baritone's long-travel repertoire is
   exactly these; see baritone-notes.
2. The set's vertical control is binary (jump yes/no). Players get
   four vertical verbs: jump, release-to-sink, sprint-jump
   (distance), and look-steering while swimming. Which of those a
   *mob carrier* can express is settled per-finding below.

## 3. Findings

### F1 - ClimbUp is a misnomer and collides with a real trait

Vanilla "climbing" is ladder/vine/powder-snow only
(`LivingEntity.handleRelativeFrictionAndCalculateMovement`: y=0.2
when `(horizontalCollision || jumping) && onClimbable()`). Our
`ClimbUp` models "jump-step onto one higher standable cell" and
reads no block property, while `BlockTraits.climbable()` sits there
with zero consumers. A future ladder movement must not inherit the
collision.

**Ruling D1 (approved, rev 2)**: rename to **`JumpUp`**, not
`StepUp` - StepUp implies passive auto-step, but this edge demands a
jump input; JumpUp says what the executor must do. (Baritone's
corresponding movement is named Ascend, so there is no convention
debt either way.) Reserve *Climb* for a future trait-driven
ladder/vine Movement per decision 19a.

### F2 - Fluid propulsion is planned but physically unreachable

Verified chain (rev 2, all citations decompiled 1.20.1):

- Vanilla buoyancy: `LivingEntity.aiStep` reads the `jumping` flag
  and calls `jumpInFluid(...)` when the body sits in fluid - the
  deep-water branch needs **no ground contact and honors no
  noJumpDelay**.
- Inheritance concern raised in review, now closed:
  `Mob.aiStep()` opens with `super.aiStep()`
  (Mob.java line 538), so the LivingEntity fluid branch runs for
  `BotBodyEntity`. `PathfinderMob` adds no override.
- Magnitude: `Mob.jumpInFluid` routes through
  `jumpInLiquidInternal` (Mob.java 1450): if
  `getNavigation().canFloat()` it delegates up (gentle
  +0.04 x SWIM_SPEED per tick, LivingEntity.jumpInLiquid);
  otherwise it adds **+0.3 vertical velocity per tick** directly.
  Our body's stock `GroundPathNavigation` has `canFloat() == false`,
  so holding the flag in deep water produces the fast
  bottom-to-surface ascent players know from holding space. At the
  surface the shallow-water branch takes over, which is throttled by
  `noJumpDelay = 10` - bobbing stays tame without our help.
- Why the body still sank: `BotBodyEntity.customServerAiStep` fires
  `jumpFromGround()` only under `driveJump && onGround()`, and
  `setDrive(jump=false)` resets `setJumping(false)` every tick. In
  fluid `onGround()` is false, so the request died before any
  vanilla consumer saw it.
- Forward drift exists (`travel()` water branch:
  `moveRelative(0.02 x SWIM_SPEED)`, ~one fifth of land
  acceleration) but sinking dominates; the progress fuse then
  declares STUCK on a route the planner honestly believed
  swimmable.

Consequence: `Swim`/`SwimUp` are planner-only fiction beyond wading
depth. The existing `crossesWaterTrench` gametest cannot catch this -
its trench is one cell deep; the bot wades on the bottom.

**Ruling D2 (approved with pre-verification; verification done)**:
the review's fallback ("call jumpInFluid manually if Mob breaks the
chain") is unnecessary - the chain holds. Fix shape: in
`customServerAiStep`, when the body is in fluid express driveJump as
`setJumping(true)` and let vanilla consume it; keep today's
`jumpFromGround` path for land. The `setJumping(false)` reset in
`setDrive` stays keyed to the same request bit, so request-off
ticks clear the flag exactly once per tick with no same-tick
clobber. Add a deep-pool gametest: >=2 cells of water requiring a
SwimUp chain, then shore exit. This gates the acceptance run and
touches no frozen signature.

### F3 - Vertical verbs for a mob carrier: sink is free, sneak is not the answer

The original proposal adopted sneak-as-sink for player parity.
Verification kills it for our carrier:

- `moveRelative` rotates input by **yaw only**
  (`Entity.getInputVector` consults `getYRot()` alone), so
  pitch steering never moves the body vertically - the player verb
  "look down to dive" does not exist for a mob driven through
  xxa/zza.
- The water branch of `travel()` reads no shift/sneak state;
  `isShiftKeyDown()` has no effect on mob buoyancy.

But the player operation this was meant to replicate - descending in
water - costs the player nothing either: release space, gravity
sinks you (~slow, damped by the 0.8 vertical factor). For the bot,
neutral input IS the sink verb, already available. Depth-hover
precision is out of scope until something demands it.

The `Intent.Move.sneak` field stays dropped by `BindingActor.flush()`
for now - its real consumer is issue 0003's SneakWalk (sneaking AABB
and edge safety), where wiring it belongs.

**Ruling D3 (amended by evidence)**: no sink mechanism ships. Sink =
neutral input; sneak wiring defers entirely to issue 0003.

### F4 - No world-modification primitives exist at all

Pillar-up, bridge, tunnel: none has a Movement, an Intent kind, or
an inventory to place from. `Intent.Use` resolves melee only;
`selectedSlot` is recorded state. The function-map Locomotion GAP row
already names parkour / pillar-up / door handling as expected growth.

Review added an interface constraint that must ride the backlog
entry: `Movement.isViable(WorldView)` cannot answer "does the hand
hold a placeable block" - that is bot state, not world view. Options
considered: extend Movement (touches boundary A purity), inject an
InventoryView into MoveGraph construction, or split viability into a
world half checked during search and a state half checked when
PathingBehavior adopts the plan. The latter two need no frozen-signature
change.

**Ruling D5 (approved with constraint)**: sequencing stands -
inventory model first, then Place/Break intent kinds, then
Movements - and the backlog entry must state: the Movement interface
does not grow; bot-state viability arrives via MoveGraph construction
injection or adoption-time checks in PathingBehavior.

### F5 - Cost model honesty after propulsion exists

Fluid costs were chosen unexecutable: Swim 1.5 / SwimUp 2.0 against
water acceleration roughly one fifth of land. Review's estimate puts
surface swimming near 4-5x walk cost and column ascent 6-8x; both
are placeholders until measured.

Rev 2 adds a player-shape correction the flat numbers miss: players
cross water **on the surface layer** and treat depth as terrain to
avoid, and they wade ankle-deep water nearly at walk speed. Cost
should therefore be depth-tiered (shallow ~= walk, surface lane
cheap-ish, sub-surface expensive) once P2 makes the numbers
measurable, instead of one flat Swim cost.

**Ruling D4/D5 (approved)**: measure post-fix via gametest
(ticks-per-cell by depth tier), then re-derive; record the
walk-speed assumption in BasicMoves Javadoc meanwhile.

### F6 - Traversal shape: what players do that the plan shape hides

Beyond individual verbs, three player habits are invisible to the
current primitive set:

1. **Surface-lane preference**: crossing a lake happens at the top
   water layer, not through the volume. With uniform Swim costs the
   search has no reason to prefer it. Depth-tiered costs (F5) give
   the preference a mechanical home.
2. **Air budget**: sustained submersion risks drowning damage.
   Nothing senses air supply today. The natural home is a reflex
   rule (boundary C: low bubbles -> preempt toward surface), which
   per decision 10 is rule-table DATA, not new code.
3. **Sprint-jump as the default long-haul gait**: players cover
   open ground sprinting, and sprint-jumps preserve momentum. A mob
   carrier pays no hunger for sprinting (`setSprinting(true)` is
   mechanically free), so the only honest reasons to defer are
   validation cost and the jump-distance change (gap viability).
   Deferred per ruling D4, but recorded here as the first speed
   upgrade candidate - implementable as an adapter/behavior policy
   (sprint when N cells of straight clearance ahead) with **no
   Intent signature change**; revisit when a long-haul scenario
   demands pace.

## 4. Boundary tension notes

- F1 renames a core-pathing record - internal, review-gated.
- F2 touches only the adapter - no signature change.
- F3 ships nothing; future sneak wiring rides issue 0003.
- F4 adds Intent kinds eventually - frozen-boundary territory, so
  backlog text only until the Stage review lifts it.

## 5. Decision log

| # | Question | Ruling |
|---|---|---|
| D1 | Rename ClimbUp? | Approved -> **JumpUp** (not StepUp; jump input is load-bearing) |
| D2 | Fluid-jump fix now? | Approved; inheritance verified in tree, `setJumping` path suffices, deep-pool gametest required |
| D3 | Sink semantics | **Amended**: no mechanism ships; sink = neutral input (mob carrier has neither look-dive nor shift-sink); sneak defers to issue 0003 |
| D4 | Sprint in v1? | Deferred; recorded as F6(3) no-signature policy candidate |
| D5 | World-modification order? | Approved: inventory -> Place/Break intents -> Movements; Movement interface must not grow (construction injection or adoption-time state checks) |
