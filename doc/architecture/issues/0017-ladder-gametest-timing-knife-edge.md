---
title: Ladder gametest sits on a timing knife edge
last_verified: 2026-09-01
covers:
  - src/main/java/com/mcbot/mcbotserver/gametest/BotLocomotionGameTests.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
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

# Ruling

The scenario stays `required = false` until the race is root-caused:
a required test that flips on compile-level noise would rot the
suite. The underlying ladder mechanics (plate passability via the
climbable trait, ClimbUp routing, wall-press yaw override, Y-aware
cursor advance) are covered by the offline gates
(`AStarLadderColumnGateTest`, `BasicMovesClimbTest`,
`WaypointCursorClimbGateTest`, `PathingVerticalClimbGateTest`,
`CollisionShapeGateTest`) and by the passing engine runs above.

# Contract

Hypotheses for the freeze, in test order (all unverified):

1. Periodic reflex preemption (boundary C: a non-null reflex skips
   the mission tick) - a reflex firing every N ticks would freeze
   the walk without tripping the fuse. Which reflex arms under a
   platform on flat ground is unknown.
2. Worker-plan adoption freshness window: the Chebyshev-1 staleness
   guard rejects a plan when the body crossed 2+ cells during the
   off-thread search; a rejected adoption re-requests, and the
   cycle could stall steering while the fuse keeps resetting on
   cursor churn.
3. Departure-hold re-arm loop: `PathingBehavior.tick` resets
   `departHoldTicks = 4` whenever `directive.goal()` differs from
   `lastGoal`; any control flapping that re-issues the goal each
   cycle would creep at 1 driving tick per 5.

Instrumentation note: any in-test observer (println, sequence,
event-queue poll) shifts the outcome - the observer effect is the
reproducer. Diagnosis needs either a production-side trace that
survives timing shifts (log at a cadence, not per-tick) or a
deterministic offline rig that reproduces the freeze (drive
`PathingBehavior` over `MockWorldView` with the exact scenario
geometry and tick-loop timing).

# Deferred with reopen

Reopens when the freeze reproduces deterministically offline or a
production-side trace captures the stall window.
