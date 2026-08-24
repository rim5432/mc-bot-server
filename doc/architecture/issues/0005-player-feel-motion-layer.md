---
title: Player-feel motion layer - eyes, start/stop, rhythm, cosmetic fidgets
last_verified: 2026-08-24
covers:
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/IdleLook.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PlanProgressFuse.java
  - src/main/java/com/mcbot/mcbotserver/core/pathing/PlanSmoother.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
status: open (P0-P3 shipped; live acceptance run pending)
related:
  - doc/architecture/boundaries.md
  - doc/architecture/function-map.md
  - doc/architecture/issues/0004-movement-primitive-vocabulary.md
  - doc/architecture/issues/0003-sneak-pose-and-edge-walk.md
---

# Issue 0005: Player-feel motion layer

## 1. Scope

After the live demos on the dedicated server (walk-to-player, water
crossing), the user judged the bot's motion technically correct but
lacking "player feel" - it reads as a device, not an actor. This
issue defines that layer: what makes observed motion read as
player-like, ranked by payoff, with one hard design rule.

Sister to issue 0004: 0004 governs whether the VERBS are correct
(primitives, propulsion); 0005 governs how the actions LOOK while
remaining fully testable.

## 2. Diagnosis - where player feel comes from

Three sources, and the current bot inverts all three:

| Source | A player | The bot today |
|---|---|---|
| Where the EYES go | looks along the path, up at steps, down at slopes, at people who come close | pitch hard-coded 0 in steering; ignores everyone |
| How motion STARTS and STOPS | accelerates, decelerates, turns around, glances about | zero-to-full instantly; brake-free stop; faces the last waypoint forever |
| RHYTHM | sub-second pauses between actions, ~0.2 s reactions | decisions every tick, zero gaps between phases |

Counter-intuitive but load-bearing: instant precision exposes the
machine. Human-latency responses read as alive; perfect ones read as
scripted.

## 3. Design rule

Every player-feel feature must be **configurable and deterministic**
(fixed seed where randomness appears), so gametests can pin exact
behavior. Player feel is presentation for spectators - it must never
become verification debt. Features failing this rule are rejected
regardless of payoff.

## 4. Tiered proposals

### P0 - Head tracking (cheapest, largest payoff)

All through the existing ROT channel - `Intent.Look` is already
submitted by steering with pitch hard-coded to 0:

1. Moving: pitch follows terrain - up onto steps, down slopes,
   toward the surface lane while swimming.
2. Idle: turn the head toward the nearest player within a configurable
   radius (default 6 blocks).
3. Arrival: turn to face the nearest player when one is close.

No signature changes; steering and a small idle policy only.

**Implementation status (2026-08-24, P0 shipped)**:

- P0.1 (moving) lives in `PathingBehavior.steerPitch` -
  feet-to-feet geometry toward the steer waypoint (eye heights
  cancel on both ends), engine sign (negative = up), clamped at
  `STEER_PITCH_LIMIT_DEG = 60`. Flat legs read exactly 0, so
  plains behavior is byte-identical to before. Pinned by
  `SteerPitchGateTest`: flat = 0, the JumpUp rig = exactly
  `-atan2(1, 2)`, steep legs clamp both signs.
- P0.2 + P0.3 collapsed into one fallback: arrival is just the
  first idle tick after the mission retires, so both are the same
  policy. Placement follows the P3 precedent below - NOT a
  Behavior (boundary B freezes claim-free null-directive ticks),
  but an adapter-local pass in `BindingActor.flush` that fires
  only when the ROT channel is unclaimed that tick. Pathing
  (priority 10) and combat (priority 20) ROT claims always
  out-rank it, so it never fights a mission; combat holds ROT
  every tick it is active, so mid-fight flicker is impossible by
  construction.
- The math and constants live in pure `core.behavior.IdleLook`
  (zero MC imports) so layer-1 tests pin them:
  `PLAYER_LOOK_RADIUS = 6.0` (inclusive, eye-to-eye, nearest wins,
  ties to the earlier list entry), `TURN_PER_TICK_DEG = 15`
  (wrap-aware shortest arc; the exact-180 tie resolves toward +
  via IEEEremainder - deterministic), `PITCH_LIMIT_DEG = 60`.
  Spectators and removed players are skipped. The head also tracks
  during reflex freezes and the crashed latch: presentation only,
  MinimalReflex still owns every motion channel. Pinned by
  `IdleLookGateTest`.

### P1 - Gait (half cosmetic, half functional)

1. Sprint-jump corridor policy: exactly the deferred candidate
   recorded in issue 0004 F6(3) - `setSprinting(true)` when N cells
   of straight clearance lie ahead. Hunger-free on the mob carrier,
   no Intent signature change.
2. Path smoothing: line-of-sight waypoint simplification before
   following. Today's grid waypoints produce right-angle zigzags;
   merging mutually visible waypoints removes both the look and
   excess turning. Functional bonus: fewer replan-triggering
   overshoots on corners.
3. Continuous small-hop cadence uphill instead of discrete JumpUp
   waits where steps are uniform.

### P2 - Rhythm (purely cosmetic, last)

1. Departure delay: 4-5 ticks between directive arrival and first
   drive claim.
2. Deceleration window: release drive 2-3 cells before the goal and
   let friction brake the body.
3. Idle look-around timer: periodic head sweeps when no player is
   near.

### P3 - Walk fidgets (user addition)

Occasional main-hand swing bursts while walking - one or two swings
at seeded-random intervals (roughly every 40-120 ticks of movement).
Pure animation: `LivingEntity.swing(MAIN_HAND)` plays the arm arc and
touches nothing.

**Semantic boundary (binding)**: the fidget MUST bypass the USE
channel entirely. `Intent.Use` means "act with the main hand" and its
handler deals melee damage to nearby hostiles; routing cosmetic
swings through it would convert idle flavor into combat input. The
swing call therefore lives in the adapter next to the body, driven by
a seeded timer, and is suppressed while a combat process owns the
arbiter (never swing idly mid-fight - that reads as attacking).

**Implementation status (2026-08-24, P1+P2+P3 shipped)**:

- P1.2 + P1.3 land as ONE post-pass, `core.pathing.PlanSmoother`,
  applied at plan adoption (both sync and worker paths): rule 1
  collapses collinear runs (constant step vector, no world read -
  every edge was A*-validated), rule 2 greedily merges same-Y
  waypoints over a standable corridor sampled every 0.5 blocks
  (`BasicMoves.standable`, now package-visible). The staircase
  collapse IS P1.3: one far-above waypoint for the whole climb keeps
  the jump thrust held, turning per-step JumpUp waits into a
  continuous hop cadence with zero extra mechanism. Pinned by
  `PlanSmootherTest` (straight/staircase collapse, zigzag merge,
  wall-keeps-corner, short-chain passthrough).
- Ledger-20 hard re-test trigger fired and resolved (see the
  amended entry in boundaries.md): drift is now XZ distance to the
  walked SEGMENT (`WaypointCursor.previous()` to `current()`), the
  criterion-3 latch resets on waypoint advance, and the arclength
  upgrade was judged unnecessary (merged segments are straight).
  Pinned by `PathingBehaviorFrameGateTest.replanDriftIsXZToSegment`
  and `PlanProgressFuseSegmentTest`; limbo/detour coverage re-run.
- P1.1 sprint is adapter-local per the 0004 D4 ruling:
  `BindingActor.sprintClearAhead` - forward >= 0.95, not in fluid
  (water sprint is the swim boost and would change crossing speed),
  and a 5-block eye-height clip ahead misses (`SPRINT_CLEARANCE_BLOCKS`).
  Single steps ahead do not read as walls at eye height, so
  sprint-jump onto steps stays the gait. No Intent signature change.
- P2.1 departure hold: `DEPARTURE_DELAY_TICKS = 4` in
  `PathingBehavior`, re-armed on goal change (a mid-mission re-target
  reads as stop-and-redecide); planning and cursor bookkeeping run
  during the hold, only the drive claims wait. Pinned by
  `DepartureBrakeGateTest`.
- P2.2 arrival brake: drive scales as `endDist / BRAKE_DISTANCE`
  (3.0 m) floored at `ARRIVE_MIN_DRIVE = 0.35`, measured XZ to the
  plan's terminal waypoint - never zero, so the body always creeps
  into the goal predicate. Pinned by `DepartureBrakeGateTest`.
- P2.3 idle sweep: `IdleLook.SWEEP_INTERVAL_TICKS = 100`,
  `SWEEP_AMPLITUDE_DEG = 60`; the adapter anchors the base yaw when
  idleness begins and flips glance direction on the counter -
  deterministic, no randomness. Pinned by
  `IdleLookGateTest.sweepDefaultsPinned`.
- P3 fidget: `BindingActor.tickWalkFidget`, one seeded
  `Random(FIDGET_SEED)` owns the whole sequence; a burst fires every
  40-120 WALKING ticks (the countdown only advances while a MOVE
  claim flows), 1-in-3 bursts echo a second swing 8 ticks later.
  Suppression is the USE-winner-or-combat-ROT-priority test
  (`COMBAT_PRIORITY_BAND = 20`). Direct `body.swing` only - never
  the USE channel (the P3 semantic boundary above).
- Known ceiling (ponytail-marked in code): the LOS corridor is
  point-sampled with no body-width inflation; the fuse criterion-3
  analysis assumes merged segments stay straight, so any future
  CURVED path vocabulary must do the arclength upgrade after all.

## 5. Rejected

Injecting random noise into movement itself (jittered speeds,
wandering offsets) to simulate human imperfection: it poisons
determinism and testability for a payoff far below P0 head tracking.
Players are predictable in goals and organic only in micro-motion;
this layer reproduces exactly that split.

## 6. Boundary tension notes

- P0/P2 use existing channels and claims - no frozen-signature
  contact.
- P1 sprint stays an adapter/behavior policy per the 0004 D4 ruling;
  smoothing is planner-internal.
- P3 intentionally lives OUTSIDE boundary B/D semantics as
  adapter-local cosmetics; this note is the marker that prevents a
  future refactor from promoting it into Intent.Use.
- P0.2/P0.3 idle look shipped adapter-local by the same rule (see
  implementation status above): the marker prevents a future
  refactor from promoting idle presence into a Behavior or an
  Intent variant.

## 7. Placement

Stage 3 backlog, first batch: P0 items, then P1, then P2/P3. No
dependency on 0004 outcomes beyond the sprint reference. Defaults
(radius, delays, intervals) become named constants at implementation
and are pinned by tests then.
