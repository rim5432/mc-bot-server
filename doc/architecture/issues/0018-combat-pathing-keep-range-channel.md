---
title: Combat/Pathing coordination channel (maintained range)
last_verified: 2026-09-03
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/core/behavior/CombatBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/main/java/com/mcbot/mcbotserver/core/process/DefendProcess.java
status: resolved (ledger 54: GoalRange vocabulary growth)
---

# Problem

Bow combat has no mechanism to HOLD a range. `DefendProcess` orders
`GoalNear(targetCell, RANGED_STANDOFF)` and `PathingBehavior` closes
to that rim - but GoalNear is satisfied once inside the range and
never backs the body up, so any closing target walks straight through
the rim, and a retreating kiter is chased, not contained. The
behavior tier cannot fix this either: `CombatBehavior` deliberately
owns no locomotion, and the claim model gives the directive's goal
the MOVE channel - there is no path for "keep range >= 4 while
drawing" to reach the mover.

Ledger 51 made the round trip survivable (standoff opening at the
process tier, committed-draw hysteresis and point-blank fire at the
behavior tier), but those are opening-and-finish policies; a
genuinely maintained range under fire is still unimplementable
without cross-tier coordination.

# Ruling

Deferred with reopen. The general fix - CombatBehavior signaling a
movement constraint into PathingBehavior - crosses boundary B's
claim model (a behavior monopolizing channels via per-tick claims)
and is a boundary change, so it opens behind a Stage review, not a
hot patch. Interim doctrine (ledger 51): processes own the
engagement-range decision, the behavior tier owns point-blank fire,
and the standoff rim is documented as an opening posture.

# Contract

None yet. Cheapest candidate on the table: a Goal-vocabulary growth
(e.g. a range-band goal `GoalRange(cell, min, max)`) implemented
plan-time in PathingBehavior - grows the allowed vocabulary, no
claim-model change, and DefendProcess/AttackProcess swap
`GoalNear(cell, RANGED_STANDOFF)` for it directly. Claim-overlay
designs (combat decorating the mover's claims while drawing) stay
rejected until a second behavior needs locomotion constraints
(same claim-kind promotion rule as the seat dispatch).

# Deferred

Reopens when a verdict requires a maintained range under fire
(archer duel vs a closing melee mob, or kiting practice), or when a
second behavior independently needs to constrain locomotion. Until
then the point-blank fire path is the accepted answer to a closed
standoff.

# Resolution (2026-09-01, ledger 54)

Resolved via the GoalRange vocabulary growth — the cheapest candidate
named in the Contract section. `GoalRange(center, min, max)` extends
the sealed Goal algebra; `isInGoal` is a band predicate (a cell
inside min is NOT in the goal, so A* terminates at the nearest outward
edge); `Goals.heuristicOf` returns band-edge distance for GoalRange
and keeps euclideanTo for GoalBlock/GoalNear. `PlanLifecycle` swaps
its hardcoded `euclideanTo(cellOf(goal))` for `Goals.heuristicOf(goal)`.
`DefendProcess` and `AttackProcess` ranged standoff migrates from
`GoalNear(target, 10)` to `GoalRange(target, 8, 12)`.

No claim-model change, no behavior-coordination channel — the process
tier owns the range decision and the pathing tier owns the motion,
exactly as boundary B prescribes. The claim-overlay designs named in
the Contract section remain rejected per the same-kind promotion rule.

Gametest `rangedBotBacksAwayWhenTargetClosesInsideMin` pins the
behavior: a NoAi target at distance 10 is teleported to distance 7,
and the body backs away to restore >= 8 separation while landing
arrows.
