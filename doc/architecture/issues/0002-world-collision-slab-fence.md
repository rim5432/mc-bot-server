---
title: 5 world/collision tests red - CollisionShape / BasicMoves fence + slab defaults missing
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

## 3. Hypothesis (not yet verified)

The most likely cause is `MapBlockTraitsRegistry` / the
collision-shape adapter returning conservative default
shapes for unknown blocks, so fence and the slab variants
fall through to the "blocking" default rather than the
shape-correct values the gate expects. Per the function-map
caption on `BlockTraits`, the registry is the source of
truth for non-geometric block properties (climbable,
liquid, damaging), but `CollisionShape` (geometric) is
populated by the adapter per AGENTS.md §2.1. The shape
defaults for fence / slab in the adapter are the
suspected gap.

Verification path:
1. Read `BlockTraitsRegistry` + `MapBlockTraitsRegistry` and
   the shape-emission site in the adapter.
2. Read the 5 tests' setups to see what block IDs they use
   and what shape the registry returns for them.
3. Trace the discrepancy.

## 4. Out of scope for issue 0001 / PR-2

Issue 0001 (path-progress fuse) does not depend on these
5 tests; the vertical-displacement fix in `PathingBehavior`
and `BotController` is independent of `CollisionShape` and
`BasicMoves` shape semantics. The 5 tests are disabled
in the PR-2 commit with `@Disabled("see issue 0002")` and
re-enabled in the issue 0002 fix.

## 5. Resolution direction (proposed, not adopted)

Add fence + upper-slab + lower-slab to the shape-emission
table in the adapter (or the MapBlockTraitsRegistry for
the non-geometric half). Each entry sets:

- `passable()` (cell body volume clear),
- `walkableTop()` (cell above has a top within step-up reach),
- `stepHeight()` (the y of the top, for standability),
- `bounds` (the AABB used by sweep / clip).

The three test pairs collapse to two contract points:

- Fence: `passable == true` for the body sweep (the body
  walks through the fence's open top half when stepping),
  `walkableTop == false` (the post at cell.y is too thin to
  step on).
- Lower slab: `passable == true` (body walks over the half-
  height obstacle), `walkableTop == true` (top sits at y=0.5
  within step reach).
- Upper slab: same as full block — `passable == false`,
  `walkableTop == true`.

A follow-up ADR (or a follow-up issue) is the right home
for the exact shape contract; this issue records the
finding.
