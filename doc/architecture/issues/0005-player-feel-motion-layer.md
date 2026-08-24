---
title: Player-feel motion layer - eyes, start/stop, rhythm, cosmetic fidgets
last_verified: 2026-08-24
covers:
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/IdleLook.java
  - src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
status: open (P0 shipped)
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
