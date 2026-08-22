---
title: PathingBehavior has three vertical-displacement failure branches and a fuse measuring the wrong quantity
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
  - src/main/java/com/mcbot/mcbotserver/core/pathing/PlanWorker.java
  - src/main/java/com/mcbot/mcbotserver/core/world/SnapshotWorldView.java
  - src/main/java/com/mcbot/mcbotserver/api/world/WorldView.java
---

# Issue 0001: PathingBehavior has three vertical-displacement failure branches and a fuse measuring the wrong quantity

## 1. Trigger (3 lines)

The function-map ships Locomotion as `[SHIPPED]` with a replan
ladder (exhaustion / drift>3 / progress fuse). A review of the
"drift>3 in 3D" question found a larger gap: vertical displacement
(cliff, lava column, knockback) puts the body in branches where
two of the three triggers misfire and the third misclassifies the
body as "progressing" while it falls. The bug is structural, not
a missing constant.

## 2. Settled facts (do not re-litigate)

Verified by reading the source. Reviewers should cite this
section, not re-derive the conclusions.

- **`advanceReachedWaypoints` uses OR semantics.**
  `PathingBehavior.java:328-340`. The condition is
  `sameCell || horizontal <= WAYPOINT_REACH` (line 334). A pose
  whose XZ is within `WAYPOINT_REACH` of the waypoint consumes
  the waypoint regardless of Y.
- **Replan does not reset progress tracking.**
  `requestPlan` (line 236-254), `planSync` (line 301-316), and
  `adoptCompletedPlan` (line 262-295) only touch waypoints,
  `waypointIndex`, `pendingPlan`, `pendingGoal`, `pendingStart`.
  The `lastProgressPose` and `ticksSinceProgress` fields are
  read and written only at `PathingBehavior.java:169-176`. The
  "livelock lesson institutionalised" comment is accurate:
  replan does not clear progress.
- **Plan is async with cell-equality freshness check.**
  `requestPlan` (line 250-253) submits to the worker; the future
  is adopted in `adoptCompletedPlan` (line 274-275) by
  `cell.equals(pendingStart)`. A pending plan whose start cell
  no longer matches the body's current cell is dropped whole and
  the ladder is re-primed (line 284). There is no race where a
  stale plan is adopted.

## 3. Three failure branches (the core artifact)

For a body in motion toward a flat-ground `GoalBlock`, classify
the fall by XZ distance to the nearest *remaining* waypoint:

| XZ to nearest waypoint | waypoint consumed? | offPath (XZ>3)? | fuse (3D motion) | behaviour |
|---|---|---|---|---|
| < 0.8 | yes (OR, §2) | no | reset by fall | all waypoints consumed -> `exhausted` -> cooldown -> replan from landing cell. **Recovery is correct iff the body lands.** |
| 0.8 ~ 3.0 | no | no | reset by fall | **silent limbo** — none of the three triggers fires. Recovery only if the body lands on a solid block (fuse catches `motion < 0.01/tick` after landing) or XZ drifts out of the band. |
| >= 3.0 | n/a | yes | n/a | replan after cooldown, but the start cell is the body's airborne cell. A* runs over a snapshot whose geometry is solid; the result is either NO_PATH or a path back to ground. `adoptCompletedPlan` discards the plan as stale if the cell has moved during search, triggering another cycle. **Wasted work, not a hang.** |

The original review question — "does the cooldown cause replan
oscillation over lava" — is answered: in branch 3 the bot knows
it is replanning, but every plan is generated from a transient
airborne cell and gets discarded by the freshness check. The
mechanism is waste, not unawareness.

## 4. Root cause attribution

Three structural problems, ordered by leverage:

1. **Fuse measures motion, not plan-progress.** The
   `STUCK_EPSILON` check at `PathingBehavior.java:170` is
   `pose.distanceTo(lastProgressPose) > 0.01` — a per-tick
   motion detector. Free fall (~0.3 m/tick start, ~3.9 m/tick
   terminal) registers as "moving, not stuck". A body that is
   plummeting through 0.8~3.0 limbo forever never accumulates
   the 20-tick window. **This is the single largest lever**:
   switching the fuse to plan-progress semantics eliminates the
   limbo branch in one change.
2. **Four constants, three coordinate frames, no documented
   contract.** `STUCK_EPSILON` is full 3D (`Vec3.distanceTo`,
   `Vec3.java:25-30`). `REPLAN_DISTANCE` and `WAYPOINT_REACH`
   are horizontal XZ only (`PathingBehavior.java:342-345` and
   `332-333`, both `Math.hypot(dx, dz)`). `GoalBlock.isInGoal`
   is 3D cell equality (`GoalBlock.java:27-29`). The
   drift-trigger Javadoc at `PathingBehavior.java:64-65` calls
   `WAYPOINT_REACH` "horizontal" but does not say so for
   `REPLAN_DISTANCE` — code-comment drift made worse by
   absence of any document pinning the contract.
3. **Freshness check is cell-equality, which is too strict.**
   `adoptCompletedPlan` drops any plan whose start cell is not
   the body's current cell. A* search time x bot speed > 1 cell
   means the plan is dropped on flat ground too, not just
   vertically: a 4-5 tick A* run lets a walking bot
   (0.215 b/tick) cross one cell during search, and the search
   result is thrown away. With a 10-tick cooldown, a slow
   systematic drop-and-retry livelock is possible in wide
   search spaces. The fix (Chebyshev ±1 cell tolerance) is a
   general correctness change, not a vertical patch.

**Explicitly rejected attributions.** Two diagnoses have been
proposed in earlier review iterations and are wrong:

- "doc drift" — the coordinate-frame mismatch is not a doc
  drift but an **architecture contract gap**. A convention that
  is undocumented, unasserted, and untested is not a convention
  but a coincidence. The fix is to pin the contract in Javadoc
  + add a test that fails if a future change silently flips a
  constant to a different frame.
- "missing vertical state machine" — proposed as a complete
  repair. The diagnosis ("Y axis is a control illusion") is
  correct for airborne states only, but a full state machine
  (grounded / falling / in-fluid / on-ladder) is over-repair
  for v1 (see §6 for unlock conditions). Plan-progress fuse +
  vertical gate cover the same failure modes at a fraction of
  the cost.

## 5. Fix list (scope is "decision points", not lines of code)

| # | Fix | Touches | Decision points |
|---|---|---|---|
| 1 | Coordinate-frame contract in Javadoc + frame-pinning test | 4 constant Javadocs, 1 test method | none — pins current behaviour |
| 2 | Keepalive event | 1 `EventKind` value, 1 emission point in tick loop | N value; whether the event enters the formal replay contract (entry = S-F verifier must recognise it, schema change) |
| 3 | Physicalised shove test | rewrite `recoversWhenShoved`, add 3-4 new tests | whether the gametest harness can run vanilla physics ticks for free |
| 4 | Fuse quantity: motion -> plan-progress | rewrite progress-check block at `PathingBehavior.java:169-176`; new `planProgressScore(pose, waypoints, goal)`; redefine `STUCK_EPSILON` semantic (0.01 m cannot be reused — the new threshold is in score units) | (a) the three OR criteria for plan-progress (§Ruling a); (b) subsumption argument for deleting motion detection and the `lastProgressPose` field, included in PR description so reviewers do not re-litigate |
| 5 | Freshness ±1 cell tolerance | 1 equality to Chebyshev-distance + 1 test | Chebyshev vs Euclidean; tolerance value |
| 6 | Vertical gate (only evaluate offPath/fuse when `onGround`; landing edge fires one immediate evaluation) | trigger evaluation at `PathingBehavior.java:188-200`; needs `onGround` from `PoseSource` | whether `onGround` is reachable through the existing `PoseSource` interface — the largest hidden cost in the list, possibly a one-time interface change |

### Ruling (a) — three OR criteria for plan-progress

A two-criterion `waypointIndex advanced OR goalDistance decreased`
fails switchback: an A* result that loops around a wall has
legitimate segments where the goal distance grows and the
waypoint gap is too coarse for `waypointIndex` to advance on
each tick. The third criterion is `currentWaypointDistance3D`:
along any legal path this strictly decreases for almost every
step; on a limbo body all three are simultaneously false (no
index advance, no goal closure, no waypoint closure) and the
fuse accumulates cleanly. The PR must include a subsumption
argument: motion detection is implied by plan-progress for the
legal "moving" case and strictly weaker for the "moving but
not progressing" case. Confirm `lastProgressPose` has no other
readers before deleting the field together with the check.

### Ruling (b) — fuse and gate are complementary, not redundant; order is 4 then 6

Fix 4 alone eliminates branch 2 (limbo). Fix 6 alone does not.
Branch 3 (waste) is unaffected by 4: each STUCK event triggers
another replan, which `adoptCompletedPlan` discards as stale
because the cell has moved again, which yields another replan.
Without 6, the system spends ~50 ms of A* per cooldown producing
a result that lives for one tick. The two fixes have orthogonal
failure modes and must be implemented in order.

### Ruling (c) — freshness ±1 cell is a general correctness fix, not a vertical patch

Branch 3 makes the freshness bug visible because airborne
motion crosses a cell every 1-2 ticks. On flat ground at
walking speed (0.215 b/tick) the same bug fires when A* takes
>=5 ticks, which is the wide-search case — not a vertical
scenario at all. The fix should be justified and reviewed on
its general merits ("the equality check is wrong; a tolerance
is the contract"), not as a workaround for vertical drift.

## 6. Explicit non-goals + boundaries

- **Vertical state machine deferred.** A complete state
  machine (grounded / falling / in-fluid / on-ladder) is the
  textbook-correct fix for "Y axis is a control illusion" but
  is over-repair for the current task domain. **Unlock
  condition:** the task vocabulary gains a Movement that depends
  on Y state (climb, swim, elytra), or S-D needs the body's
  motion state to disambiguate threats (e.g. distinguishing
  "falling" from "jumping" matters to a fall-damage rule).
- **`REPLAN_DISTANCE` does not couple to hazard thresholds.**
  The drift trigger is a navigation-internal signal, not a
  threat-awareness signal. Letting it know about lava or
  void-below-Y-64 couples the navigation layer to the danger
  model that S-D owns; once coupled the two layers start
  assuming each other and become harder to separate than the
  current single-axis coordinate confusion.
- **Void death is not this issue's scope.** A body that has
  fallen past `Y = -64` is in a terminal state. Replanning
  from inside the void is wasted work; the correct response is
  S-D recognising "unrecoverable" and either alerting the
  harness or letting the bot die and respawn. This issue
  covers only recoverable displacement (bot is in air but
  solid ground is reachable in concept).

## 7. Test matrix

| Scenario | Existing | Required |
|---|---|---|
| XZ shove (current `recoversWhenShoved`) | covers teleport-not-shove | rewrite: replace `setPos + ZERO` with `setDeltaMovement(knockback)` + 5 vanilla physics ticks |
| Vertical shove (slime block launch, ender pearl) | none | new test; assert replan within 20 ticks (post-fix 4) |
| 0.8 ~ 3.0 limbo (cliff fall with XZ held at 1.5 from last waypoint) | none | new test; assert **STUCK event fires within N ticks** (assertion form: event occurrence, not recovery success — see review note) |
| Fall fuse (drop on solid, body goes static) | covered by `recoversWhenShoved` indirectly | explicit test: fuse fires within 20 ticks of landing |
| Freshness ±1 cell (A* takes 5 ticks while body walks 1 cell) | none | new test: plan adopted with start cell offset by Chebyshev 1 |
| keepalive emission | none | new test: keepalive emitted every N ticks with pose/waypointIndex/ticksSinceProgress/ticksSincePlan/planAge/goalCell |

The limbo acceptance assertion needs user review. Candidate
formulations:

1. `STUCK` event fires within K ticks of limbo entry (K=20
   with fix 4; before fix 4, K=infinity).
2. `STUCK` event fires within K ticks OR the body has left
   the 0.8~3.0 band (lands or drifts out).
3. `STUCK` event is followed by either NO_PATH (current cell
   has no path) or a fresh plan from a non-airborne cell
   (recovered after landing).

The reviewer's intuition is that the assertion should be
"event fires" (form 1) rather than "recovery succeeds" (form
3), because recovery from limbo inherently involves the body
leaving the limbo state, which is the test's *exit condition*
not its *assertion*. Form 2 mixes an external condition
(landing) with the system-under-test, which obscures the
boundary. **Pending user confirmation.**

## 8. Severity calibration

The limbo "infinity" requires continuous vertical motion. A
body that lands in water eventually stops moving (vertical
velocity decays to zero) and the 3D motion detector fires
after 20 ticks. The worst case is therefore bounded by the
duration of continuous vertical motion:

- **Waterfall column** (continuous Y motion, XZ stationary):
  the body oscillates in place, never leaves the 0.8~3.0
  band against any single waypoint, never accumulates fuse
  time. Severity: undefined (until fix 4).
- **Lava column** (Y fall + damage): same oscillation plus
  the body dies in seconds. Severity: bot dies with no
  task-failure event. Pre-fix-4 the harness sees "in
  progress" until death; post-fix-4 the harness sees a STUCK
  event before death.
- **Falling into water on flat ground**: fuse catches within
  20 ticks of landing. Severity: 1 second silent window.
  Acceptable.

Severity is therefore a continuous quantity (silent window
duration), not a binary. Reporting it as a single number hides
the worst case; the right reporting is "max silent window in
the task scenario envelope, with the scenario stated".
