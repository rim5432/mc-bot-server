---
title: Sneak pose and edge walk - pose-aware collision, SNEAK channel, shield block
last_verified: 2026-08-29
covers:
  - src/main/java/com/mcbot/mcbotserver/api/actor/Channel.java
  - src/main/java/com/mcbot/mcbotserver/api/world/CollisionShape.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/CombatBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
status: parked (sketch only; not adopted - re-derive at pickup)
related:
  - doc/architecture/boundaries.md
  - doc/architecture/function-map.md
  - doc/architecture/issues/0002-world-collision-slab-fence.md
---

# Issue 0003: Sneak pose and edge walk

## 1. Scope

Three coupled capabilities, deliberately kept OUT of issue 0002
and out of v1:

- **SneakWalk**: 1-wide edge traversal with the 1.5-height
  sneaking AABB (sneak keeps the body on the block edge).
- **ShieldBlock**: damage mitigation via a SNEAK+USE channel combo
  (raising the shield requires sneaking in vanilla).
- **Pose-aware collision**: `passable` / `walkableTop`
  parameterized by body pose (STANDING / SNEAKING).

## 2. Why this is not part of issue 0002

Orthogonality. Issue 0002 answered "in the standing pose, how is
world geometry parsed" - a static world-to-shape mapping, settled
2026-08-23 (see its Resolution). This issue answers "how does the
bot switch pose, and how does the switch change geometry parsing"
- a dynamic bot-state-to-pose-to-AABB mapping. Landing them in
one review would let either concern block the other.

The v1 envelope does not need it: combat is melee-only (no shield,
no BLOCK channel), waypoints are never generated on cliff edges,
and with standability settled at 0.5 the half-slab top is already
a full routing surface - no sneak compensation required. The
10-minute survival acceptance runs without any of the three.

Progress 2026-08-28 (partial landing, still parked for the rest):
the sneak pose pair and the shield raise shipped through the USE
dispatch - `BotBodyEntity.setDrive(+sneak)` applies shift +
CROUCHING pose (1.5-height gap crawl and isCrouching visibility
reduction are live), a `/bot sneak` harness latch survives idle
ticks, and a held shield raises on the BODY via startUsingItem
(blocking is hurt-entity-level). Deliberately NOT claimed: the
SneakWalk edge traversal - vanilla's edge clip lives only in
`Player.maybeBackOffFromEdge` (base Entity is a stub), so porting
it stays this issue's open core, now with the pose half already in
place. No SNEAK channel was created, honoring this sketch's own
vocabulary-honesty rule - sneak rides MOVE as a modifier and the
latch as body state.

Debt avoidance: parameterizing `passable(Pose)` today would force
the whole `BasicMoves` chain to thread a Pose that every v1 caller
hardcodes to STANDING - a pretend-multi-pose API. Defining
`Channel.SNEAK` with no behavior-layer consumer would put a
SKELETON row on the function map for code nobody drives. Both are
vocabulary dishonesty; this issue is the honest placeholder.

## 3. Prerequisite

Issue 0002 (resolved): the geometry contract this issue
parameterizes - STEP_UP_REACH / STANDABLE_THRESHOLD /
BODY_WIDTH - is defined there. This issue only adds the pose
switch and its consumers.

## 4. Sketch (not adopted; re-derive at pickup)

1. `api.actor.Channel` gains SNEAK (or MOVE claims grow a sneak
   flag - decide at pickup against decision 14's four-channel
   freeze).
2. `CollisionShape` predicates take a pose parameter with
   STANDING defaults so existing callers stay source-compatible;
   sneaking lowers head clearance needs and enables edge-hold.
3. `CombatBehavior` claims SNEAK+USE when mitigating;
   `PathingBehavior` claims SNEAK on edge segments if edge paths
   ever get planned.
4. Gate tests: sneak-walk-on-slab-edge-does-not-fall,
   shield-block-reduces-damage-when-sneaking.

Function-map rows already point here: Locomotion [PLANNED]
SneakWalk, Combat [PLANNED] ShieldBlock, Perception [PLANNED]
Pose-aware collision.
