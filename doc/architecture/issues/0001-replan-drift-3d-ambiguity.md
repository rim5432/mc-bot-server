---
title: Replan drift threshold and waypoint reach are 2D-only while progress fuse and goal predicate are 3D
last_verified: 2026-08-23
covers:
  - doc/architecture/function-map.md
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/main/java/com/mcbot/mcbotserver/gametest/BotSliceGameTests.java
  - src/test/java/com/mcbot/mcbotserver/tickpipeline/StuckFuseTest.java
status: open
related:
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - src/main/java/com/mcbot/mcbotserver/api/types/Vec3.java
  - src/main/java/com/mcbot/mcbotserver/api/goal/GoalBlock.java
---

# Issue 0001: Replan drift / waypoint reach are horizontal-only; Y displacement is invisible to the planner

## Problem

`PathingBehavior` uses four distance / containment metrics with three
different coordinate frames. The mismatch makes Y-axis displacement
(cliff fall, vertical shove, lava column, vertical knockback) silently
absent from the drift trigger while the progress fuse treats the same
motion as "we are moving, not stuck". The disagreement is not a bug in
any single metric; it is an undocumented contract split.

## Evidence

Constants in `PathingBehavior.java:55-68`:

```java
STUCK_WINDOW   = 20;    // ticks
STUCK_EPSILON  = 0.01;  // per-tick displacement threshold
WAYPOINT_REACH = 0.8;   // waypoint "touched" radius
REPLAN_DISTANCE = 3.0;  // drift trigger
REPLAN_COOLDOWN = 10;   // ticks
```

The four triggers and their coordinate frames:

| Trigger | Frame | Source |
|---|---|---|
| `offPath` (drift) | Horizontal XZ only | `PathingBehavior.java:190-192` -> `distanceToWaypoint` line 342-345, `Math.hypot(dx, dz)` |
| `advanceReachedWaypoints` (reach) | Horizontal XZ only | `PathingBehavior.java:328-340`, same hypot, plus `sameCell` short-circuit |
| Progress reset (stuck fuse) | Full 3D | `PathingBehavior.java:170` -> `pose.distanceTo(...)`, sqrt of dx^2+dy^2+dz^2 in `Vec3.java:25-30` |
| Goal predicate | Full 3D cell | `GoalBlock.isInGoal` line 27-29, `pos.equals(target)` over `CellPos` |

Net effect: Y motion is visible to the progress fuse (as "moving")
and to the goal predicate (as "not at goal"), but invisible to the
drift trigger and the waypoint reach test.

The Javadoc on the constants is half-true:

```java
/** Horizontal reach that counts as "waypoint touched". */
public static final double WAYPOINT_REACH = 0.8;

/** Drift from the active waypoint that forces a fresh plan. */
public static final double REPLAN_DISTANCE = 3.0;
```

`WAYPOINT_REACH` says "horizontal"; `REPLAN_DISTANCE` does not. The
implementation of both is XZ-only. A reader who trusts the comments
in order and skips the body will infer that drift is 3D — comment
drift, see AGENTS.md §1.4.5.

## Per-scenario trace

Setup for each: directive is a flat-ground `GoalBlock` several
cells away, body is in motion along a valid path.

### A. Horizontal push (XZ 1..10 blocks)

- `offPath` fires once XZ drift exceeds 3; replan after cooldown.
- **Status: works. Covered by `recoversWhenShoved` gametest.**

### B. Horizontal knockback (XZ > 3 in one tick)

- `offPath` fires on the first tick.
- **Status: works. Same code path as A; no dedicated test.**

### C. Cliff fall / pure-vertical drop (Y changes, XZ stays)

- `offPath` is `false` (XZ drift < 3). Replan does not trigger from
  drift.
- Progress fuse is `false` because per-tick Y drop > 0.01 resets
  `ticksSinceProgress`. Stuck does not fire.
- The waypoint's cell Y differs from the body's cell Y, so
  `sameCell` is `false` in `advanceReachedWaypoints`. But
  `horizontal <= WAYPOINT_REACH` is `true` whenever XZ is within
  0.8 of a waypoint — waypoint is **consumed regardless of Y**.
- `exhausted` becomes `true` after the body walks past every
  waypoint's XZ column. Replan then runs from the new (fallen)
  cell.
- **Status: recovery rides on `exhausted` + post-landing cell,
  not on a dedicated Y-drift signal. If the body never lands
  (void fall), no replan ever fires.**

### D. Pure-vertical flow (water / lava column)

- Body Y drops each tick; XZ stays near zero.
- `offPath` never fires. Progress fuse never fires. The body is
  in range of every waypoint's XZ column immediately, so all
  waypoints are consumed in a handful of ticks.
- After `exhausted`, replan runs from the new (deeper) cell.
  The new plan is correct relative to the new cell.
- **Status: works, but wasteful — every plan is re-derived
  from scratch because the XZ reach was a false positive for
  every waypoint.**

### E. Vertical knockback (slime block launch, ender pearl,
   vertically-stacked pistons)

- Same as C: vertical-only displacement is invisible to `offPath`.
  Recovery rides on `exhausted`.

## Per-scenario summary

| Scenario | Triggers replan via | Tested |
|---|---|---|
| A. Horizontal push | `offPath` (XZ > 3) | yes (XZ-only) |
| B. Horizontal knockback | `offPath` | no |
| C. Cliff fall | `exhausted` + landing cell | no |
| D. Vertical flow | `exhausted` (immediate; XZ-reach false positive) | no |
| E. Vertical knockback | `exhausted` | no |

## Test coverage gap

- `gametest/BotSliceGameTests.java:105-134` — `recoversWhenShoved`:
  body is teleported to start with `deltaMovement = Vec3.ZERO`,
  same Y as the goal. The push is a teleport, not a physical shove,
  and Y is never modified.
- `tickpipeline/StuckFuseTest.java:176-198` —
  `shoveInsideWindowDoesNotTripFuse`: pose is set by
  `pose[0] = at(base + 0.5)`, where `at(x) = new Vec3(x, 64, 0.5)`.
  Y is held at 64 every tick.
- No test exercises Y displacement, water / lava flow, vertical
  knockback, or void fall.

The test name `recoversWhenShoved` is a review trap: it suggests
"shove in general" but only covers the XZ variant. A reviewer who
skims the gametest names will miss the gap, and the Stage 1 gate
text — "walks, recovers-from-shove, fails-cleanly" — sells coverage
the test does not provide.

## Open questions

1. Should `REPLAN_DISTANCE` become 3D (full Euclidean) or should a
   separate Y-drift threshold be introduced? Each option interacts
   with `MAX_DROP = 3` in `BasicMoves` and the Y cost of climb-up
   / drop Movements.
2. Should `WAYPOINT_REACH` remain horizontal-only (true reach is
   a 2D test in the move graph) or should the planner also gate
   waypoint consumption on Y match? `sameCell` is the existing
   Y-gate but is bypassed by the horizontal reach.
3. Should the gametest suite be extended with a Y-axis shove
   scenario, or is XZ-only acceptable as a minimum-viable test
   that later scenarios layer on top of?

Resolution is out of scope for this issue. A follow-up ADR
(0006 or later) is the right home for whichever answer the team
picks; this file records the finding and the evidence.
