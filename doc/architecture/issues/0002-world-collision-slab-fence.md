---
title: 5 disabled world/collision tests - shape-predicate contract conflicts (walkableTop threshold, Box Y range, fence passability)
last_verified: 2026-08-23
covers:
  - src/main/java/com/mcbot/mcbotserver/api/world/CollisionShape.java
  - src/main/java/com/mcbot/mcbotserver/api/world/BlockTraitsRegistry.java
  - src/main/java/com/mcbot/mcbotserver/core/world/MapBlockTraitsRegistry.java
  - src/main/java/com/mcbot/mcbotserver/core/world/MockWorldView.java
  - src/main/java/com/mcbot/mcbotserver/core/pathing/BasicMoves.java
  - src/test/java/com/mcbot/mcbotserver/world/CollisionShapeGateTest.java
  - src/test/java/com/mcbot/mcbotserver/corepathing/BasicMovesShapeGateTest.java
status: open
related:
  - doc/architecture/function-map.md
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

## 5. Resolution direction (updated by the closeout audit)

The fix is a contract amendment, not a default-value patch,
so it waits for stage review per the AGENTS.md stop rule
(§0.4): it changes boundary vocabulary covered by decision
19. The workplan's "Stage 2 closeout follow-ups" item 1
carries the scheduling. Scope when picked up:

- Pick the walkableTop threshold from (0.1, 0.5] and make
  code and Javadoc agree; re-derive `STEP_HEIGHT`'s role
  (step-up reach vs standability floor are different
  quantities and today share one constant).
- Decide fence representation: Box Y range past 1, or
  two-cell post. Whichever wins, `passable()` needs a
  footprint-vs-body-width rule to answer fences true - pure-Y
  cannot.
- Fix the climb case's coordinates per finding 4, then
  re-enable cases one finding at a time; each re-enable is
  its own commit.
