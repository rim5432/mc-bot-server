---
title: 5 disabled world/collision tests - shape-predicate contract conflicts (walkableTop threshold, Box Y range, fence passability)
last_verified: 2026-08-23
covers:
  - src/main/java/com/mcbot/mcbotserver/api/world/CollisionShape.java
  - src/main/java/com/mcbot/mcbotserver/api/world/BlockTraitsRegistry.java
  - src/main/java/com/mcbot/mcbotserver/core/world/MapBlockTraitsRegistry.java
  - src/test/java/com/mcbot/mcbotserver/core/world/MockWorldView.java
  - src/main/java/com/mcbot/mcbotserver/core/pathing/BasicMoves.java
  - src/test/java/com/mcbot/mcbotserver/world/CollisionShapeGateTest.java
  - src/test/java/com/mcbot/mcbotserver/corepathing/BasicMovesShapeGateTest.java
status: archived (2026-08-23; settled semantics recorded as
  boundaries.md decision ledger entry 19b)
superseded_by: architecture/boundaries.md
---

# Issue 0002: 5 world/collision tests red - fence + slab default semantics missing

## 1. Trigger

5 tests in the world/collision area have been red for at
least one full test run (2026-08-22 workplan gate).
The function-map ships Locomotion as `[SHIPPED]`, but the
collision-shape / block-traits split that the gate relies on
has a default-value gap: fence and the two slab variants
return values that make the shape predicates false in the
direction the test expects.

This issue is NOT in the PR-1 / PR-2 path of issue 0001. It
exists separately so the 5 reds do not contaminate the
PR-2 baseline (per the PR-2 pre-flight checklist item 5).

## 2. The 5 red tests (verbatim from the test gate)

- `BasicMovesShapeGateTest > walkOnFenceFloorIsNotViable()`
  - Expected: walking onto a fence top is NOT viable
    (`passable()` returns false because the bot's feet would
    clip the fence's solid collision box).
  - Actual: returns true (viable), so the gate fails.
- `BasicMovesShapeGateTest > climbOntoUpperSlabFailsWhenDestinationHeadIsBlocked()`
  - Expected: climbing onto a top-slab is rejected when the
    head cell is blocked.
  - Actual: returns true (viable), so the gate fails.
- `BasicMovesShapeGateTest > walkAcrossLowerSlabIsViable()`
  - Expected: walking across a bottom slab is viable
    (`passable()` returns true because the bot can step over
    the half-height obstacle).
  - Actual: returns false, so the gate fails.
- `CollisionShapeGateTest > fenceIsNotStandableAndPassableAround()`
  - Expected: fence is not standable (no walkable top) and
    passable around (sweep path is clear).
  - Actual: at least one of the two predicates returns the
    wrong value.
- `CollisionShapeGateTest > lowerSlabIsStandable()`
  - Expected: bottom slab is standable (bot can stand on top
    within step-up reach).
  - Actual: returns false.

## 3. Verified findings (2026-08-23 closeout audit)

The original hypothesis (registry / adapter returning
conservative default shapes) was WRONG: all five cases build
their shapes directly via `CollisionShape.partial(...)` /
`MockWorldView.putShape(...)` - no registry or adapter is
involved. The gap is in the derived predicates' semantics
and in two of the test setups. Four findings, verified by
reading `CollisionShape`, `BasicMoves`, and both gate tests:

1. **`walkableTop()` contradicts its own Javadoc.** The code
   requires `box.maxY >= STEP_HEIGHT` (0.625); the Javadoc
   says "a half-slab top at y=0.5 is the threshold". A bottom
   slab (maxY=0.5) therefore answers false while
   `lowerSlabIsStandable` and `walkAcrossLowerSlabIsViable`
   require true (`BasicMoves.Walk` viability bottoms out at
   `supported -> walkableTop(cell below)`). The enabled
   pressure-plate cases pin top=0.1 -> false, so whatever
   threshold is chosen must land in (0.1, 0.5].
2. **The fence cases are unconstructible under the current
   Box contract.** Both build `Box(0.4,0,0.4,0.6,1.5,0.6)`;
   Box validation throws on maxY > 1, and the ENABLED
   `boxRejectsOutOfRangeBounds` case pins exactly that. The
   two fence cases contradict another test in the same gate.
   Either Box's Y range grows past 1 (a decision-19 vocabulary
   amendment, and the range test changes with it) or a fence
   gets a two-cell representation (cell above carries the
   upper post half as its own partial shape).
3. **`passable()`'s pure-Y rule cannot answer fences.**
   `passable == EMPTY || box.maxY < STEP_HEIGHT` is false for
   any fence representation with a tall box, but the required
   answer is true (the body sweeps around the thin post).
   Answering it needs horizontal-extent reasoning - footprint
   vs body width - which neither predicate models today.
4. **`climbOntoUpperSlabFailsWhenDestinationHeadIsBlocked`
   has a setup bug independent of the contract.** It installs
   the upper slab at `(1, FLOOR_Y, 0)` and climbs to
   `(1, BODY_Y+1, 0)`, whose supporting cell `(1, BODY_Y, 0)`
   is air - unsupported by construction. Per its own comment
   ("the cell below the destination foot is the upper slab")
   the slab belongs at `(1, BODY_Y, 0)`. With that coordinate
   the case passes under today's predicates (upper slab:
   maxY=1.0 >= 0.625, minY=0.5 < 0.625). Re-derive the setup
   when re-enabling; do not carry the current coordinates.

## 4. Out of scope for issue 0001 / PR-2

Issue 0001 (path-progress fuse) does not depend on these
5 tests; the vertical-displacement fix in `PathingBehavior`
and `BotController` is independent of `CollisionShape` and
`BasicMoves` shape semantics. The 5 tests are disabled
in the PR-2 commit with `@Disabled("see issue 0002")` and
re-enabled in the issue 0002 fix.

## 5. Resolution (2026-08-23, adopted at Stage 2 closeout review)

The settled contract keeps decision 19 intact - both predicates
stay pure geometry derived from the box; no id table, no traits
read. Three changes:

1. **Constant split.** `STEP_HEIGHT` is gone. Two different
   physical quantities were sharing one constant:
   - `STEP_UP_REACH = 0.625` gates `passable()` (what a hop
     clears);
   - `STANDABLE_THRESHOLD = 0.5` gates `walkableTop()` (the
     floor of MC's partial-top spectrum: slab 0.5, soul sand
     0.875, dirt path 0.9375 above it; farmland 0.25 and beds
     0.375 below it are not routing surfaces). Not a half-slab
     magic number.
2. **Footprint rule on standability.** `walkableTop()` for a
   PARTIAL additionally requires the box to span >=
   `BODY_WIDTH` (0.6) on both horizontal axes. This is what
   makes a fence post answer false: top high enough, footprint
   nothing to balance on. Finding 3's "horizontal reasoning"
   lands here, on the standability side only.
3. **Fence = wall (finding 2 resolved by correction, not
   representation).** The squeeze-past expectation was
   physically impossible: a centered post leaves two 0.4-wide
   slots and a 0.6-wide body fits in neither; real fences fill
   their cell near-full width anyway. The fence cases now pin
   the vanilla-aligned answers - passable false (wall),
   walkableTop false (thin) - with the single-cell post box
   `(0.4,0,0.4)-(0.6,1,0.6)` inside `[0,1]`. The two-cell
   representation idea is dropped as unnecessary machinery:
   the way past a fence is the missing-post cell, which is
   EMPTY and already answers true.

Test outcomes: all five disabled cases re-enabled and green.
Finding 4's climb case was re-derived per its own comment
(slab at `(1, BODY_Y, 0)`, destination `(1, BODY_Y+1, 0)`) and
renamed `climbOntoUpperSlabPlatformIsViable`; the single-cell
fence case was rewritten as `fencePostCellIsWallAndNotStandable`.
Full suite: zero skips.

Remaining follow-up: the shape-bearing gauntlet gametest
(workplan Stage 2 closeout follow-up 2), now unblocked.
