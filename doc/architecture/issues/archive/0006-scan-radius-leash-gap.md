---
title: Scan radius / leash gap in DefendProcess
last_verified: 2026-08-25
covers:
  - src/main/java/com/mcbot/mcbotserver/core/process/DefendProcess.java
  - doc/architecture/boundaries.md
status: resolved (archived 2026-08-25)
superseded_by: architecture/boundaries.md

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
   **Rejected**: band-aid that pushes the gap outward without fixing
   the semantic ambiguity; a target at 17+ still produces false SUCCESS.
2. **Keep 14, document only**: `SUCCESS` means "target absent from
   scan" (death OR escape), harness must poll / re-scan to confirm.
   Zero code change, relies on harness discipline. **Rejected**: the
   class Javadoc already documents this ambiguity; pushing interpretation
   to the harness violates boundary D's spirit (bot emits truthful,
   unambiguous events). A false SUCCESS costs a missed threat; a false
   ESCAPED costs one re-scan.
3. **Add `TARGET_ESCAPED` terminal state**: distinguishes death-SUCCESS
   from escape-SUCCESS. **Implemented as a failure reason**
   (`REASON_ESCAPED = "TARGET_ESCAPED"`), not a third terminal state.
   The original framing claimed this "changes the frozen `BotProcess`
   terminal signature — requires an ADR", but that was inaccurate:
   terminal semantics live on `TerminalMission` (core layer), whose
   `failureReasonOrNull()` returns an open vocabulary string. Adding a
   fifth failure reason (alongside `LOST_TARGET`, `TIMEOUT`,
   `REFUSED`, `PREEMPTED`) requires zero signature change and zero ADR.
   A genuine tri-state (`missionVerdict()` returning an enum) would
   require an ADR, but was unnecessary — "target escaped" models
   cleanly as a failure, consistent with how `LOST_TARGET` already
   models "target left the leash".

## Resolution (2026-08-24)

Option 3 implemented as a failure reason. Changes:

- `DefendProcess.REASON_ESCAPED = "TARGET_ESCAPED"` constant added.
- Engaged absent-after-grace path: `succeed()` → `fail(REASON_ESCAPED)`.
- Class Javadoc terminal semantics updated; `resume()` comment updated
  ("TARGET_DOWN success" → "TARGET_ESCAPED failure").
- Five tests flipped (the issue's "Tests pinning current semantics"
  section listed only three; two more in `DefendProcessTest` and two
  in `CombatSkeletonGateTest` also asserted the old SUCCESS behavior
  and were updated):
  - `DefendProcessTest.targetBeyondScanRadiusFiresSuccess` →
    `targetBeyondScanRadiusFiresEscaped`
  - `DefendProcessTest.genuineAbsenceFiresSuccessAfterGrace` →
    `genuineAbsenceFiresEscapedAfterGrace`
  - `DefendProcessTest.dyingEntityRemainsEngagedUntilRemoved` — final
    assertion changed from SUCCESS to TARGET_ESCAPED (the health=0
    engagement pin is unchanged).
  - `CombatSkeletonGateTest.vanishedTargetCountsAsNeutralizedAfterGrace` →
    `vanishedTargetCountsAsEscapedAfterGrace`
  - `CombatSkeletonGateTest.resumeSpendsGraceCreditForImmediateAdjudication`
    — assertion changed from SUCCESS to TARGET_ESCAPED.
- `DefendProcessTest.targetBeyondLeashFailsLost` (13 → LOST_TARGET)
  and `rangedHostileWithinEngageRadiusIsRefused` (skeleton at 6 →
  REFUSED) are unchanged.
- Non-engaged empty-scan immediate SUCCESS semantics unchanged: no
  hostile within the detection envelope = nothing to defend against.
  The detection envelope itself was widened (see below).

### Non-engaged detection radius (sister problem, same day)

The "non-engaged ranged-threat sister problem" called out in the
Established Facts section was resolved in the same change session.
Root cause: `scanHostiles()` used `ENGAGE_RADIUS (8)` for the
non-engaged branch, so a ranged hostile kiting at 9–15 blocks was
invisible to the scan and produced false SUCCESS ("area clear")
instead of REFUSED. The REFUSED logic itself already existed; only
the scan radius was wrong.

Fix:
- `DefendProcess.DETECTION_RADIUS = 16.0` constant added. Matches
  `LevelThreatSensor.THREAT_RANGE` (16.0) — the reflex layer and
  the combat planner share the same perception envelope. Kept as a
  local constant rather than referencing the adapter-layer value
  because core/ must not import adapter/ (layer direction is
  adapter → core, never reverse).
- `scanHostiles()` non-engaged branch: `ENGAGE_RADIUS` →
  `DETECTION_RADIUS`.
- New test `rangedHostileBeyondEngageWithinDetectionIsRefused`:
  skeleton at 10 (beyond ENGAGE_RADIUS, inside DETECTION_RADIUS) →
  REFUSED with `threatType=skeleton`.
- Existing test `rangedHostileWithinEngageRadiusIsRefused` comment
  updated (removed the stale claim that ranged types beyond 8 blocks
  yield SUCCESS).

No architecture change, no ADR, no interface change. The non-engaged
branch exits in one tick (engage / REFUSED / SUCCESS), so the wider
scan adds at most one tick of overlapping work with
`LevelThreatSensor` — negligible cost.

## Tests pinning resolved semantics

- `DefendProcessTest.targetBeyondScanRadiusFiresEscaped` — A at 16 →
  grace → TARGET_ESCAPED failure.
- `DefendProcessTest.genuineAbsenceFiresEscapedAfterGrace` — A removed
  → grace → TARGET_ESCAPED failure.
- `DefendProcessTest.dyingEntityRemainsEngagedUntilRemoved` — health=0
  entity stays engaged 15 ticks; after removal → grace → TARGET_ESCAPED.
- `DefendProcessTest.targetBeyondLeashFailsLost` — A at 13 →
  LOST_TARGET. Unchanged.
- `DefendProcessTest.rangedHostileWithinEngageRadiusIsRefused` —
  skeleton at 6 → REFUSED. Unchanged.
- `DefendProcessTest.rangedHostileBeyondEngageWithinDetectionIsRefused`
  — skeleton at 10 (beyond ENGAGE_RADIUS, inside DETECTION_RADIUS)
  → REFUSED with threatType=skeleton. New test pinning the sister
  fix.
- `CombatSkeletonGateTest.vanishedTargetCountsAsEscapedAfterGrace` —
  z1 removed → grace → TARGET_ESCAPED.
- `CombatSkeletonGateTest.resumeSpendsGraceCreditForImmediateAdjudication`
  — z1 removed during preemption → resume → first tick → TARGET_ESCAPED.
