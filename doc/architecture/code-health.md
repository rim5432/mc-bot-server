---
title: Code Health Ledger
last_verified: 2026-08-23
covers: []
---

# Code Health Ledger

The single place that manages code architecture optimization and
hygiene work. Feature and stage work lives in
[workplan.md](../guide/workplan.md); macro-convergence reviews live
in [issues/](issues/); this ledger owns everything that makes the
existing code *better* without making it do more.

## Scope and policy

An entry belongs here when the change is behavior-preserving by
design: naming, decomposition, stdlib adoption, dead-code removal,
test-harness consolidation, comment repair. If an item changes what
the bot does, it is a workplan item, not a health item.

Entry format: each ledger item carries a status (`OPEN` / `CLOSED`),
the date it closed, the commits that did the work, and the evidence
that nothing regressed. Open items state why they are deferred and
what reopens them.

Refactor discipline (applies to every round recorded here):

- Adjudicated code (anything pinned by a Ruling or a gate test) is
  moved, not rewritten. Verdicts, tick ordering, keepalive keys, and
  constructor overloads must survive byte-for-byte at the observable
  level.
- Every intermediate commit compiles and passes the offline suite;
  gametest files additionally require `build runGameTest` before
  commit (see rule H-R5 below - this one was learned the hard way).
- Contract markers and Ruling comments travel with the code they
  anchor; a decomposition that drops one is a defect, not a shortcut.

## Standing rules

Durable outcomes of past rounds. Each is a review-enforced rule
until a gate test automates it.

- **H-R1 Reflection goes through one door.** Test-side reflective
  access to behavior internals is centralized in
  `PathingTestAccess` (two-hop: mover, then collaborator field, then
  target). New tests must not inline `getDeclaredField` against
  behavior classes; when state moves between collaborators, only
  that one file changes.
- **H-R2 No inline fully-qualified names in sources.** A type used
  in code gets an import; FQNs appear only where Java demands them
  (javadoc `{@link}`, disambiguation against a same-named import).
- **H-R3 English-only everywhere, not just markdown.** The zero-CJK
  mandate covers Java comments and Javadoc too. The first scrub
  covered `.md` only and missed Javadoc prose; both trees are now
  regex-verified. Re-run the scan on any file touched after a
  non-English reference source.
- **H-R4 Wire keys are frozen even when fields rename.** Renaming a
  state field must preserve its serialized key (the `pose` ->
  `position` rename kept the wire key `"pose"`); boundary-D payloads
  are consumed outside the repo.
- **H-R5 Gametest edits are engine-verified before commit.** The
  offline suite cannot see gametest registration: a probe-cleanup
  regex once deleted `routesThroughFenceGap` silently and every
  offline check stayed green (restored verbatim in 5e733bd). Any
  commit touching the gametest package under
  `src/main/java/com/mcbot/mcbotserver/gametest/` runs `build
  runGameTest` first. Mechanical half:
  `GametestInventoryCheck` pins the exact registered-scenario set
  in the offline suite, so a silent deletion now fails before
  commit even when the engine run is skipped.
- **H-R6 Geometry constants carry their spectrum rationale.**
  `STANDABLE_THRESHOLD` and friends document why the value sits
  where it does across the MC partial-top spectrum (issue 0002
  Resolution mandate) so future readers do not mistake it for a
  slab-specific magic number.

## Closed rounds

### Deep-clean round (2026-08-23)

Repo-wide hygiene pass planned item-by-item and landed atomically.
Stdlib adoption folded hand-rolled code into JDK/collection
equivalents (A* closed set to `HashSet` in 498f7f9, `distance3D`
into `Vec3.distanceTo` in 926ae11); boundary-D submit plumbing
simplified to `Ok.fresh`/`replay` routing (0b3700d) and
`canonicalArgs` collapsed to `TreeMap.toString` (3c400d9);
`MockWorldView` relocated out of the shipped jar into the test
source set (e88aed6); inline FQNs became imports repo-wide
(2e01ed3); `pose` renamed to `position` ahead of the Stage 3 pose
vocabulary while keeping wire key `"pose"` (3193e84). Main source
set went from 8654 to 8396 lines with zero behavior change.

### Gametest harness consolidation (2026-08-23)

Shared rig extracted to `GametestRig` (c60540b): single construction
path for body/controller/events plus `driveUntil`/`driveOnly`
helpers and coordinate utilities, replacing per-test boilerplate.
Scenario files slimmed to scenarios; the knife-edge shove-direction
sanity case removed as redundant (6605068).

### PathingBehavior decomposition (2026-08-23)

738-line god class split into an orchestration shell plus four
package-private single-concern collaborators in `core.behavior`:
`WaypointCursor` (126), `PlanProgressFuse` (159, issue 0001 Ruling-a
invariants migrated verbatim), `ReplanGate` (101), `PlanLifecycle`
(235). Shell now 500 lines. Extraction sequence d17c871 -> eab5d66
-> 07c82db -> cede2d2, one collaborator per commit, offline suite
green at every step; probe cleanup accidentally deleted
`routesThroughFenceGap` and was restored with the incident recorded
(5e733bd); javadoc review round fixed all drift (fe61a8c). Evidence:
159 offline cases, 0 skips; 7/7 gametests. Blast radius stayed inside
the behavior package: the extraction sequence touched neither
`TaskArbiter` nor `BotController`, so the park/retirement-lap
semantics from 17ba7a2 were never in the modified set -
`ArbiterGateTest` and `ReflexChainGateTest` pin those semantics and
stayed green throughout.

## Ruling anchors in code

Where the live architectural rulings physically live, so "why is it
built this way" is one hop from this table instead of a
commit-archaeology dig. Rows carry verified anchors only; a ruling
without an anchor is a workplan item, not a row here.

| Ruling | Anchor | Settled in |
|---|---|---|
| Plan-progress fuse: three OR criteria; accumulator immune to external replan | `PlanProgressFuse` Javadoc (invariants migrated verbatim from issue 0001) | issue 0001 |
| Vertical trigger gate: airborne ticks skip trigger eval; landing edge bypasses cooldown | `ReplanGate` Javadoc + steering in `PathingBehavior` | 4c3f51f |
| Park semantics: explicit ParkResult, atomic retire sweep, resume revalidation | `TaskArbiter.forcePauseAll` + `ParkResult` Javadoc | 17ba7a2 |
| One-tick retirement lap: verdict announced on the reflex tick; no tail sweep inside arbiter tick | `TaskArbiter.tick` tail comment; function-map reflex-tick event semantics section | 17ba7a2 |
| Shape contract: STEP_UP_REACH / STANDABLE_THRESHOLD split, footprint rule, fence-as-wall | `CollisionShape` constant Javadoc + boundaries.md decision ledger 19b (issue 0002 archived) | e08c6bd |
| Melee LOS clip: eye-to-surface ray with lava-opaque cells | `BindingActor.sightBlocked` | function-map combat row |
| Executor jump actuation: direct `jumpFromGround()` under swapped MoveControl | `BotBodyEntity` deviation comment | f942b9b |

## Open items

- **OPEN H1 - api/ thick-interface trimming.** The api surface grew
  convenience methods during Stage 2; a pass should decide what a
  non-JVM harness actually needs versus what only core uses.
  Schedule: before Stage 3 vocabulary lands - pose parameterization
  touches the same collision predicates, and trimming afterwards
  would churn boundary-D consumers twice.
- **OPEN H2 - issue 0001 migration decision.** Issue 0001 is
  resolved but still open-as-file; promote its body to an ADR or
  archive it with `superseded_by:`. Decided at the Stage 2 review.
- **OPEN H3 - BoundaryContractMarkerTest.** AGENTS.md 1.4.9 names
  the candidate: a layer-1 test scanning every boundary-interface
  implementer for its contract marker. Optional for Stage 3; pair
  with the next boundary-touching change.
- **OPEN H4 - automated English-only comment scan.** The CJK scrub
  is manual regex today (H-R3). An automated per-package check is
  the AGENTS.md 1.4.9 analogue; preventive only - add it when drift
  reappears, not before.
