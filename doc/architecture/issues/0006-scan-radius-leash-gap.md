---
title: Scan radius / leash gap in DefendProcess
last_verified: 2026-08-24
covers:
  - src/main/java/com/mcbot/mcbotserver/core/process/DefendProcess.java
  - doc/architecture/boundaries.md
status: open
---

## Problem

DefendProcess uses an engaged-state scan radius of `max(ENGAGE_RADIUS,
LEASH_RADIUS + 2)` = 14, while the leash radius is 12. A target that
moves past the leash but stays within the scan (13) fails with
`LOST_TARGET`; a target that moves past the scan (15+) is absent from
`findTarget`, enters grace, and completes as `SUCCESS`.

The same "target left the area" event therefore produces two different
verdicts depending on whether the target stopped in the 12–14 band or
beyond 14. `SUCCESS` semantically reads as "neutralized" to a harness,
which may stop re-submitting defend — a false "threat cleared" signal.

This gap was fully masked before the target-stickiness fix (commit
f700894): any target that was not the nearest hostile entered the grace
path regardless of distance, so false SUCCESS was the universal behavior
for multi-hostile scenes. The fix narrowed it to the 14–12 band.

## Established facts (do not re-derive)

- `BindingWorldView.getEntities` builds its AABB from the caller-passed
  radius; there is no hardcoded collection cap. Raising scanRadius is
  purely a `getEntitiesOfClass` traversal-cost tradeoff.
- Zombie walk speed: ~4.3 blocks/sec ≈ 0.215 blocks/tick. Over a
  10-tick grace window a fleeing target moves ~2.15 blocks. A target
  at the leash edge (12) moving away reaches ~14.15 after grace.
- `EntitySnapshot` carries no dying/dead flag; `health=0` is the only
  death signal and the sensor does not filter it (verified in
  `BindingWorldView` lines 136–149).
- `CellPos.distanceTo` is 3D Euclidean; in caves with large Y delta the
  effective engage/leash distance is shorter than horizontal intuition.
- Non-engaged scan radius is `ENGAGE_RADIUS` = 8 (the ternary's false
  branch, preserved verbatim from old `nearestHostile`). An empty scan
  in non-engaged state fires immediate `SUCCESS` — so a ranged hostile
  approaching at 9–13 blocks (outside engage radius, inside the would-be
  tactical range) yields "area clear, mission complete" rather than
  REFUSED or engagement. This is the pre-refusal sister of the engaged
  gap; same root cause (scan radius vs. semantic radius), same three-option
  resolution below.

## Options (decision deferred to Stage 1 review)

1. **Raise scanRadius** to `LEASH_RADIUS + 4` ≈ 16: covers normal
   zombie movement through grace. Faster mobs (sprinting, flying) may
   still exceed it. Cost: larger `getEntitiesOfClass` AABB every tick.
2. **Keep 14, document only**: `SUCCESS` means "target absent from
   scan" (death OR escape), harness must poll / re-scan to confirm.
   Zero code change, relies on harness discipline.
3. **Add `TARGET_ESCAPED` terminal state**: distinguishes death-SUCCESS
   from escape-SUCCESS. Changes the frozen `BotProcess` terminal
   signature — requires an ADR and Stage 1 review.

## Tests pinning current semantics

- `DefendProcessTest.targetBeyondScanRadiusFiresSuccess` — A at 16 →
  grace → SUCCESS. Must flip if option 1 or 3 is chosen.
- `DefendProcessTest.targetBeyondLeashFailsLost` — A at 13 →
  LOST_TARGET. Stable across all three options.
- `DefendProcessTest.rangedHostileWithinEngageRadiusIsRefused` —
  skeleton at 6 → REFUSED; pins the non-engaged scan=8 boundary.
