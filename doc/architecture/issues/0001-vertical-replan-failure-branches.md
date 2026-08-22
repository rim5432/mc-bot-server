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
  AND add a test that fails if a future change silently flips a
  constant to a different frame.
- "missing vertical state machine" — proposed as a complete
  repair. The diagnosis ("Y axis is a control illusion") is
  correct for airborne states only, but a full state machine
  (grounded / falling / in-fluid / on-ladder) is over-repair
  for v1 (see §6 for unlock conditions). Plan-progress fuse
  AND vertical gate cover the same failure modes at a fraction
  of the cost.

## 5. Fix list (scope is "decision points", not lines of code)

| # | Fix | Touches | Decision points |
|---|---|---|---|
| 1 | Coordinate-frame contract in Javadoc + frame-pinning test | 4 constant Javadocs, 1 test method | none — pins current behaviour |
| 2 | Keepalive event | 1 `EventKind` value, 1 emission point in tick loop | N value; whether the event enters the formal replay contract (entry = S-F verifier must recognise it, schema change) |
| 3 | Physicalised shove test | rewrite `recoversWhenShoved`, add 3-4 new tests | whether the gametest harness can run vanilla physics ticks for free |
| 4 | Fuse quantity: motion -> plan-progress | rewrite progress-check block at `PathingBehavior.java:169-176`; new `planProgressScore(pose, waypoints, goal)`; redefine `STUCK_EPSILON` semantic (0.01 m cannot be reused — the new threshold is in score units) | (a) the three OR criteria for plan-progress (§Ruling a); (b) subsumption argument for deleting motion detection and the `lastProgressPose` field, included in PR description so reviewers do not re-litigate |
| 5 | Freshness ±1 cell tolerance | 1 equality to Chebyshev-distance + 1 test | Chebyshev vs Euclidean; tolerance value |
| 6 | Vertical gate (only evaluate offPath/fuse when `onGround`; landing edge fires one immediate evaluation) | trigger evaluation at `PathingBehavior.java:188-200`; needs `onGround` from `PoseSource` | whether `onGround` is reachable through the existing `PoseSource` interface — the largest hidden cost in the list, possibly a one-time interface change |

### Ruling (a) — plan-progress score; criterion 3 requires arclength (criterion 3 in earlier drafts is FALSE on detour paths)

A two-criterion `waypointIndex advanced OR goalDistance decreased`
fails switchback: an A* result that loops around a wall has
legitimate segments where the goal distance grows and the
waypoint gap is too coarse for `waypointIndex` to advance on
each tick. So far so good. But adding a third criterion of
`currentWaypointDistance3D` with the rationale "along any legal
path this strictly decreases" is **wrong**: a detour path may
first move the body sideways AWAY from the current waypoint
(door on the side, goal behind a wall) and the 3D distance
INCREASES during that stretch. If the waypoint sample is
sparse enough that the detour fits between two samples, then
criterion 1 (index not advancing) is also false and criterion
2 (goal distance increasing around the wall) is false. All
three false on a legal path -> fuse accumulates -> spurious
STUCK + wasted replan. Threshold for trouble: walk speed
0.215 b/tick x 20 ticks = 4.3 cells, which is short enough
that caves and mazes hit it routinely.

The plan-progress score therefore has two mandatory criteria
and one conditional:

1. **`waypointIndex advanced` (mandatory).** Discrete, cheap,
   exact. Plan adopted -> lastIndex remembered -> thisIndex
   increased -> progress = true.
2. **`goalDistance decreased` (mandatory).** The only progress
   signal when no active plan exists (between plan request and
   adoption, or after a plan is exhausted). Cannot be deleted
   even if the other two are stronger.
3. **`arclengthProgress3D` (mandatory; replaces the broken
   `currentWaypointDistance3D`).** Project the body's pose
   onto the remaining path polyline; track the LATCHED MINIMUM
   of "pose-to-polyline distance" — every step that decreases
   the minimum advances progress, regardless of detour shape.
   Sampling-density-independent: any legal path strictly
   advances the latched minimum for almost every step (the
   pose must be ON the polyline to have zero distance, and
   moving along the path drives the projection forward).

`STUCK_EPSILON` semantic changes from "per-tick motion in
metres" to **"new-min margin"** — a candidate new minimum must
beat the latched minimum by at least `STUCK_EPSILON` (in
metres, since arclength is in metres) to count as progress.
Without this,原地振荡 micro-noise would刷出 0.001 m new minima
and the fuse would never trip on a瀑布 column. The waterfall
characterization test in PR-1 exposes this immediately if the
margin is left at zero.

**Path A vs Path B (deferred to step 2 of the PR plan).**
The choice between "criterion 3 = arclength progress with
polyline projection" (Path B, always correct) and "criterion
3 = 3D distance to current waypoint + caveat on sample density"
(Path A, only correct if `PlanWorker` emits per-cell) is
resolved by reading `PlanWorker`. The decision does not
affect this ruling's text; both paths share the new-min
margin and the subsumption argument.

**Subsumption argument** (required in the PR description so
reviewers do not re-litigate). Motion detection is implied by
plan-progress in the legal "moving" case: any motion that
moves the pose along the polyline projection either advances
the index, decreases goal distance, or decreases arclength
progress. The motion detector is strictly weaker for the
"moving but not progressing" case (limbo body in free fall).
Therefore `lastProgressPose` and the 3D motion check are safe
to delete; the PR must confirm no other readers before
removing the field.

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
5 ticks or more, which is the wide-search case — not a
vertical scenario at all. The fix should be justified and
reviewed on its general merits ("the equality check is wrong;
a tolerance is the contract"), not as a workaround for
vertical drift.

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
| Vertical shove (slime block launch, ender pearl) | none | new test; assert STUCK within 20 ticks AND a replan is requested (post-fix 4). Replan adoption is async, so the assertion targets the request, not the adoption. |
| 0.8 ~ 3.0 limbo (cliff fall with XZ held at 1.5 from last waypoint) | none | new test; assert **STUCK event fires within N ticks** (assertion form: event occurrence, not recovery success — see review note) |
| Fall fuse (drop on solid, body goes static) | covered by `recoversWhenShoved` indirectly | explicit test: fuse fires within 20 ticks of landing |
| Freshness ±1 cell (A* takes 5 ticks while body walks 1 cell) | none | new test: plan adopted with start cell offset by Chebyshev 1 |
| keepalive emission | none | new test: keepalive emitted every N ticks with pose/waypointIndex/ticksSinceProgress/ticksSincePlan/planAge/goalCell |

The limbo acceptance assertion is **form 1: `STUCK` event
fires within K ticks of the last plan-progress tick**, with
the following rationale and test-design constraints.

**Why form 1, not form 2 or form 3.** The hard test is
regression-test validity: a useful assertion must fail when
the fix is reverted. Form 2 mixes an external condition
(landing or XZ-drift-out-of-band) into the SUT boundary —
when fix 4 is reverted, the waterfall body eventually falls
into the same offPath or motion-fuse兜底 path that a
fall-and-land body takes, so the "or leaves band" branch
still passes and the test stays green. Form 2 cannot detect
the disappearance of fix 4. Form 3 couples to downstream
products (NO_PATH vs. path-back) that are non-deterministic
across geometry snapshots. Form 1 is the only one whose
revert detection is sound: a waterfall body in pre-fix-4
never fires STUCK; the same body in post-fix-4 fires STUCK
within 20 ticks of entry. Assertion form 1 also keeps the
SUT boundary clean: the assertion talks about event
occurrence, not recovery.

**Constraint 1: scenario must be continuous vertical motion
(waterfall column or scripted pose driver), not fall-and-land.**
In a fall-and-land scenario, pre-fix-4 ALSO fires STUCK —
the body stops moving on landing, motion < 0.01/tick for 20
ticks, motion fuse trips. Post-fix-4 fires at limbo entry +
20 ticks instead of landing + 20. The two versions differ
only in timing (= fall duration), which is too fragile to
distinguish. The waterfall scenario is the only one where
pre-fix-4 has no STUCK trigger at all and post-fix-4 does.

**Constraint 2: K is anchored to "last plan-progress tick",
not "limbo entry".** The fuse counts `ticksSinceProgress`,
which is not necessarily 0 at limbo entry — if the body was
not progressing for 5 ticks before entering limbo (legal
case: a slow approach), STUCK may fire at entry+15, not
entry+20. The assertion must compare against the last
progress tick. K is the window length: 20 in the scripted
unit test (exact), 25-30 in the gametest (physics-tick
padding). The measurement instrument is the keepalive
event's `ticksSinceProgress` field — fix 2 is the prerequisite
for fix 4 verification.

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
  the body dies in seconds. Severity: the *task* channel
  goes silent, but the *threat* channel is alive — S-D's
  damage rule fires a `THREAT_*` event (HP drop) every tick
  the body is in lava, so the harness still has an event
  stream to reconcile. The pure black hole is the waterfall
  column (no damage, no event, no progress), not lava.
  Pre-fix-4 the harness sees "in progress" + threat events
  until death; post-fix-4 the harness sees a STUCK event
  before death.
- **Falling into water on flat ground**: fuse catches within
  20 ticks of landing. Severity: 1 second silent window.
  Acceptable.

Severity is therefore a continuous quantity (silent window
duration), not a binary. Reporting it as a single number hides
the worst case; the right reporting is "max silent window in
the task scenario envelope, with the scenario stated".
