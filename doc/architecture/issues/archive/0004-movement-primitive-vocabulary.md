---
title: Movement primitive vocabulary - misnomers, fluid propulsion, missing key semantics
last_verified: 2026-08-27
covers:
  - src/main/java/com/mcbot/mcbotserver/core/pathing/BasicMoves.java
  - src/main/java/com/mcbot/mcbotserver/api/actor/Intent.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
status: resolved (archived 2026-08-27 - D1/D2 shipped and pinned; open reserves relocated to the workplan movement-vocabulary seeds)
superseded_by: guide/workplan.md
related:
  - doc/architecture/boundaries.md
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

Revision history: rev 1 proposed the original five findings; rev 2
folded in the review's inheritance challenge (resolved against the
decompiled tree) and a first realism pass; rev 3 retracts rev 2's
"sink ships as nothing" ruling after the user challenged it with
underwater exploration - deliberate depth control is a real future
requirement, and the decompiled tree holds a ready-made vertical
swim axis for it (`yya`, see F3).

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
ladder/vine Movement per decision 19a. Review addition: the rename
must cover the execution side too - `PathingBehavior`'s local
`climbing` variable and its "execution half of a ClimbUp edge"
comment, plus the comment-only mentions in PlanLifecycle,
PlanProgressFuse, and four test classes - so global search keeps
returning one name.

**Implementation status (2026-08-24)**: D1 is fully implemented in
code. `BasicMoves.JumpUp` (line 235) replaces the old `ClimbUp`;
`PathingBehavior` uses `jumpForWaypoint` (renamed from `climbing`)
and its comment reads "execution half of a JumpUp edge".
PlanLifecycle / PlanProgressFuse have zero `climb` references.
Test-class comment-only mentions cleaned up 2026-08-24. The
finding and ruling stay as the historical record; `BlockTraits.climbable()`
still has zero consumers, reserved for a future ladder/vine Movement.

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

Review scope note (recorded so nobody over-credits the fix): D2
repairs VERTICAL fluid motion only. Sustained horizontal swimming at
depth stays weak (forward drift ~one fifth of land acceleration);
v1 lake crossings work by sinking, rising to the surface lane, and
bobbing across - the surface-first shape F6(1) describes. True
sub-surface cruising waits for the F3 yya wiring and the F5
depth-tiered costs.

**Ruling D2 (approved with pre-verification; verification done,
then corrected by runtime)**: the review's fallback ("call
jumpInFluid manually if Mob breaks the chain") is what shipped.
The static chain holds - Mob.aiStep calls super, the deep-water
branch honors the flag unthrottled - but a gametest position probe
showed `setJumping(true)` mid-fluid produced ZERO vertical velocity
on this carrier, the same inert-flag anomaly already recorded for
land in the gauntlet jump probe. Static reading lost to runtime
twice on this field; the binding now calls `Mob.jumpInFluid`
directly (public) when driveJump arrives while airborne in water -
the exact call that branch makes, same deviation class as the land
`jumpFromGround` path. Grounded ticks (dry or wading) keep direct
`jumpFromGround`: routing them through the flag stalls on aiStep's
noJumpDelay=10 throttle, which regressed the one-deep trench before
the split. The deep-pool gametest (three layers of water - two was
exitable by a single grounded hop off the flooded floor and masked
the gap) runs red without the fix and green with it; its crossing
trace shows the body riding the surface lane across, matching F6(1).

**Lava extension (2026-08-24, commit 981abaf)**: the same
direct-call deviation was applied to lava. `BotBodyEntity.customServerAiStep`
now checks `isInLava() && !onGround()` before the water branch and
calls `jumpInFluid(ForgeMod.LAVA_TYPE.get())`. In lava, `isInWater()`
is false and `onGround()` is false, so without this branch `driveJump`
was silently consumed every tick. The branch also closes the crashed-state
MinimalReflex lava path: `BotController.inLethalFluid` (previously a
dead field with zero call sites) is now wired from `McBotServer.onServerTick`
reading `activeBody.isInLava()`, so `MinimalReflex.tick(inLethalFluid=true,
actor)` submits `jump=true` and the lava branch fires. Lava gained its
in-engine scenario 2026-08-25: `crossesLavaTrench` (BotSliceGameTests)
swims the trench under a fire-resistance harness and pins the ascent
branch, the crossing and the exit climb end to end - the harness isolates
locomotion because the device has no lava-survival story yet.

### F3 - Vertical control: the yya axis gives real swim steering, not just rocket-up plus freefall

Rev 2 ruled "sink = neutral input, nothing ships". The user's
challenge is correct: crossing a lake needs nothing more, but
deliberate underwater activity (exploring ruins, mining, hovering
at a depth) requires depth to be an actively controlled axis, not
a passive drift. Verification of the carrier's options:

- **Fine vertical axis exists and is unused by us**:
  `LivingEntity.yya` is a public field (line 224) flowing straight
  into `travel(new Vec3(xxa, yya, zza))` (line 2654). The water
  branch feeds the whole vector through
  `moveRelative(0.02 x SWIM_SPEED, ...)`, and `getInputVector`
  passes the Y component through unrotated. Writing yya therefore
  yields continuous bidirectional vertical thrust consumed entirely
  by vanilla physics: positive swims up, negative dives down, zero
  hands the body to gravity. This is the same input slot flying
  mobs use; it is the mob-carrier equivalent of the player's
  swim-along-input mechanic.
- **Strong vertical burst**: the jumping flag path from F2
  (+0.3/tick via Mob.jumpInFluid while canFloat is false) remains
  the "shoot for the surface / pop out at the shore" verb.
  Two speeds, two verbs, exactly like a player feathering space
  versus holding it.
- **Sneak-as-dive is implementable after all** - rev 2 wrongly
  closed this: the engine ignores shift for mobs (the water branch
  reads no sneak state), but that only means the ADAPTER must
  translate, which is its job. Mapping Shift-in-fluid to negative
  yya in `customServerAiStep` reproduces authentic Java player key
  semantics with no new Intent field.
- Hovering: no engine hover exists for mobs or players; both manage
  depth by feathering inputs. Behavior-level duty cycling of yya
  (pulse toward target depth band) is the honest equivalent.

Key mapping stays player-shaped because a keyboard works exactly
this way - same keys, meaning depends on medium:

| Keys | On land | In fluid |
|---|---|---|
| Space | jumpFromGround (F2 land path) | ascend (yya+ fine; flag burst when surfacing) |
| Shift | sneak (issue 0003) | dive (yya-) |
| neither | stand | sink slowly by gravity |

`Intent.Move(forward, strafe, jump, sneak)` needs no signature
change - the executor interprets contextually.

**Ruling D3 (rev 3)**: v1 ships only the F2 ascent fix (acceptance
gate). The yya dive/ascend wiring lands as one adapter translation
when an underwater scenario first demands it; reserved now so
nobody concludes gravity-drift is the final answer. Sneak on LAND
remains issue 0003's consumer exclusively.

Review implementation notes for that future wiring, recorded where
the work will find them: (a) `BotBodyEntity.setDrive` carries only
(forward, strafe, jump) today - Shift=dive needs the sneak bit
threaded through to the body (a `setDrive` parameter or a
`setSneak` setter; adapter-internal either way, no Intent change);
(b) the claim that `travel()`'s water branch consumes the yya
component unrotated was read from `getInputVector`, but the
end-to-end behaviour must be proven by a gametest at implementation
time, not trusted from reading.

One more pose fact reserved for exploration work: the swimming
POSE (`Pose.SWIMMING`, the 0.6-tall hitbox that lets players crawl
through 1-block flooded gaps) does not shrink our carrier -
mob entity types do not map pose to dimensions. Crawling gaps need
a `getDimensions(Pose)` override plus issue 0003's pose-aware
collision predicates. Recorded here so underwater scope estimates
include it.

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
   **Implemented 2026-08-25** (boundaries.md decision ledger 22:
   `ThreatBlackboard.airSupply` + `SURFACE_ON_LOW_AIR` 80/200/110
   ASCEND; in-engine green via `surfacesWhenAirRunsLow`). The
   discussion this finding opened — every extreme scenario, not
   just drowning — continues in issue
   [0008](0008-extreme-scenario-survival-framework.md).
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

Underwater exploration additionally pulls in the F3 yya wiring, the
swimming-pose dimension override, and F6(2)'s air-budget reflex -
all reserved, none blocking v1.

## 4. Boundary tension notes

- F1 renames a core-pathing record - internal, review-gated.
- F2 touches only the adapter - no signature change.
- F3 ships only the F2 ascent in v1; the yya dive/ascend
  translation reuses existing Intent fields, so it is adapter work,
  not a signature change - but it lands only with an underwater
  scenario to validate against. Sneak on LAND rides issue 0003.
- F4 adds Intent kinds eventually - frozen-boundary territory, so
  backlog text only until the Stage review lifts it.

## 5. Decision log

| # | Question | Ruling |
|---|---|---|
| D1 | Rename ClimbUp? | Approved -> **JumpUp** (not StepUp; jump input is load-bearing) |
| D2 | Fluid-jump fix now? | Approved; inheritance verified in tree, `setJumping` path suffices, deep-pool gametest required |
| D3 | Sink semantics | **Rev 3**: v1 ships the F2 ascent only; deliberate depth control reserved on the `yya` axis (Space=ascend / Shift=dive translation in fluid, no Intent change); gravity-drift is NOT the final answer; swimming-pose dimensions noted for exploration scope |
| D4 | Sprint in v1? | Deferred; recorded as F6(3) no-signature policy candidate |
| D5 | World-modification order? | Approved: inventory -> Place/Break intents -> Movements; Movement interface must not grow (construction injection or adoption-time state checks) |
