---
title: Ladder gametest sits on a timing knife edge
last_verified: 2026-09-03
covers:
  - src/main/java/com/mcbot/mcbotserver/gametest/BotLocomotionGameTests.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/WaypointCursor.java
status: open
---

# Problem

`climbsLadderToPlatform` (BotLocomotionGameTests) fails with
`TIMEOUT` on a clean build and passes on a build that differs by one
no-op `helper.startSequence()` line - a ~100 ns perturbation flips
the outcome. The scenario itself is sound: six engine runs with any
trivial timing perturbation pass (two with full event telemetry
showing the correct 5-waypoint plan, the climb, and the platform
exit), and the offline twin `AStarLadderColumnGateTest` proves the
planner route deterministically.

Evidence table (2026-08-31, all runs 79 scenarios, ladder scenario
only):

| Build                                   | Outcome        |
|-----------------------------------------|----------------|
| clean (5 runs)                          | TIMEOUT x5     |
| + println probes (2 runs)               | PASS x2        |
| + telemetry poller sequences (2 runs)   | PASS x2        |
| + one no-op sequence (1 run)            | PASS x1        |

World-wipe + 30 s cooldown before one clean run did NOT change the
clean-build failure, so `run/world` entity leakage is excluded.

Failure signature: the bot walks 2-3 cells east from spawn (x=104 to
x=105..107 local 5..7, under the platform), then freezes for the
rest of the mission budget (~370 ticks) with `mission.active=true`,
`failure=TIMEOUT` from budget exhaustion. No STUCK latch, no
NO_PATH, no replan verdict - the behavior layer reports RUNNING
while the body does not move.

# Root cause (resolved 2026-09-01)

The smoothed ladder plan is `[(4,1,8), (9,1,8), (9,6,8), (6,6,8)]`:
`PlanSmoother.collapseRuns` merges the five-cell ClimbUp run
`(9,1,8)..(9,6,8)` into a single waypoint at the top.

When the bot walks east and reaches x ~ 8.7 (floor cell `(8,1,8)`,
one cell west of the ladder), it is within `WAYPOINT_REACH = 0.8`
of the `(9,6,8)` waypoint's XZ centre but five blocks below it.
`PathingBehavior.isVerticalClimb` returns false because
`floor.x != wp.x` (the bot has not stepped into the ladder column
yet), so `WaypointCursor.advance` — the XZ-only variant — fires and
**consumes the entire five-block climb leg in one tick** on
horizontal distance alone, ignoring the Y gap.

The cursor then jumps to `(6,6,8)` on the platform. The bot, still
at y=1, walks west under the platform ceiling, exhausts the cursor,
triggers a replan, and repeats the same cycle — walk east,
prematurely consume the climb, walk west, replan — until the mission
budget dies. The fuse never latches because each replan adoption
resets the observation latches and the eastward leg closes the goal
distance, so `ticksSincePlanProgress` stays below `STUCK_WINDOW`.
No STUCK, no NO_PATH, just RUNNING forever.

The timing knife edge: whether the bot lands at x = 8.69 vs 8.71 on
the critical tick depends on when movement started, which depends on
when the off-thread A* search completed, which depends on ~100 ns of
setup latency. A perturbation that shifts search completion by one
tick shifts the bot's position by ~0.215 m, flipping the
`horizontal <= 0.8` predicate.

Of the three original hypotheses, hypothesis 2 (worker-plan adoption
freshness) was the closest in spirit — the failure lives in the
plan-adoption / cursor-consumption boundary — but the actual defect
is in `WaypointCursor.advance`, not the staleness guard. Hypotheses
1 (reflex preemption) and 3 (depart-hold re-arm) are excluded: no
reflex arms under the platform on flat ground, and `GotoProcess`
holds a single final `Goal` reference so the directive never
flaps.

# Fix

`WaypointCursor.advance` now carries a vertical guard: a waypoint
more than one block above the body's floor (`wp.y > floor.y + 1`)
is **not** consumed on XZ reach alone. Those are merged climb or
staircase segments; `advanceClimb` owns exact-Y consumption once
the body enters the climbable column. JumpUp legs (y+1) stay
consumable — the body is mid-jump and reaches that Y within a
tick.

```
// in WaypointCursor.advance(Vec3 position):
CellPos floor = PathingBehavior.floorOf(position);
while (waypointIndex < waypoints.size()) {
    CellPos wp = waypoints.get(waypointIndex);
    if (wp.y() > floor.y() + 1) {
        break;  // vertical segment: let the body gain Y
    }
    ... // existing sameCell / horizontal-reach consumption
}
```

Offline coverage:
- `WaypointCursorClimbGateTest.smoothedLadderTopWaypointNotConsumedOneCellWest`
  — the exact 0017 geometry: smoothed plan `[(4,1,8),(9,1,8),(9,6,8),(6,6,8)]`,
  bot at `(8.8,1,8.5)` (one cell west, within XZ reach of the top).
  Asserts `ladderBase` is consumed but `ladderTop` is held, and
  `advanceClimb` only releases it at floor `(9,6,8)`.
- `WaypointCursorClimbGateTest.xzReachConsumesJumpUpButStopsAtVerticalSegment`
  — updated from the old "XZ reach exhausts overhead" assertion:
  y+1 (JumpUp) is still consumed, y+2 is held.
- Existing `AStarLadderColumnGateTest`, `PathingVerticalClimbGateTest`,
  `PlanProgressFuseSegmentTest`, `NoPathEscalatorTest` all green.

# Ruling

The scenario stays `required = false` until an engine run confirms
the fix on a clean build. The root cause is identified and the
offline regression test pins the mechanism; flipping to
`required = true` requires a green `climbsLadderToPlatform` on a
clean `runGameTest` (no probes, no extra sequences).

# Contract (superseded)

The three hypotheses listed in the original contract are resolved:
1. Reflex preemption — excluded (no reflex arms under the platform).
2. Worker-plan adoption freshness — closest but wrong locus; the
   actual defect is cursor consumption, not the staleness guard.
3. Depart-hold re-arm — excluded (`GotoProcess` holds a final Goal).

# Deferred with reopen

Reopens only if a clean-build engine run still produces TIMEOUT after
this fix — that would indicate a second, independent freeze
mechanism not captured by the offline reproduction.
