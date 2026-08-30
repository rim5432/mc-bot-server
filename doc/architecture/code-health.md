---
title: Code Health Ledger
last_verified: 2026-08-30
covers:
  - build.gradle
  - config/checkstyle/checkstyle.xml
  - config/checkstyle/suppressions.xml
  - config/pmd/ruleset.xml
  - .git-blame-ignore-revs
  - src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/MissionReporter.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/ReflexMissionSeat.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java
  - src/main/java/com/mcbot/mcbotserver/adapter/MeleeResolver.java
  - src/main/java/com/mcbot/mcbotserver/adapter/PresenceLayer.java
  - src/main/java/com/mcbot/mcbotserver/core/behavior/PathingBehavior.java
  - src/test/java/com/mcbot/mcbotserver/hygiene/EnglishOnlyScan.java
  - src/test/java/com/mcbot/mcbotserver/hygiene/GametestInventoryCheck.java
  - src/test/java/com/mcbot/mcbotserver/architecture/BytecodeArchitectureGateTest.java
  - src/test/java/com/mcbot/mcbotserver/architecture/LintPostureGateTest.java
  - src/test/java/com/mcbot/mcbotserver/architecture/DecisionIndexSyncGateTest.java
  - src/test/java/com/mcbot/mcbotserver/core/tick/PathingTestAccess.java
  - src/test/java/com/mcbot/mcbotserver/core/tick/TickGateFixtures.java
  - src/test/java/com/mcbot/mcbotserver/core/tick/TickPipelineGateTest.java
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

## Rule registry

Durable outcomes of past rounds, admitted one by one. Every rule
carries the invariant it guards, the gate that enforces it, and a
status:

- **gated** - a mechanical check in the offline suite fails on
  drift; the rule's manual obligations are retired.
- **pending** - rule-only today; the gate column names the planned
  check, and the row states what graduates it.
- **review-only** - not mechanically checkable by nature (prose
  quality, spectrum rationale); review-enforced forever.

Admission protocol - one atomic commit per admission: the registry
row, the gate test landing in the `architecture` (structure and
boundary rot) or `hygiene` (source hygiene) test package with a
Javadoc cross-reference back to the row, and the rewrite of
any rule prose the gate has made true. A candidate must name an
invariant it guards; a rule admitted with an unenforceable manual
trigger and no graduation path is the failure mode this registry
exists to prevent. Gate failure messages carry their own
remediation - a gate that gets disabled when noisy is worse than
no gate.

Non-goal: line-count thresholds. The deep-clean round recorded
-258 main-source lines as a win and the PathingBehavior
decomposition recorded +383 total lines as a win - LOC cannot
distinguish health from rot in either direction, and no gate
admitted here may encode a size cap.

| Rule | Invariant it guards | Gate | Status |
|---|---|---|---|
| H-R1 Reflection is the exception | Collaborator unit tests live same-package with direct package-private access; reflection exists only in `PathingTestAccess`, only for integration-level mid-drive state | `hygiene.ReflectionDoorCheck` | gated 2026-08-24 |
| H-R2 No inline FQNs | A type used in code is imported; FQNs appear only where Java demands them | none scheduled (textually checkable, lowest value) | review-only |
| H-R3 English-only everywhere | Zero CJK codepoints in any `.md` file and any Java source | `hygiene.EnglishOnlyScan` | gated 2026-08-24 |
| H-R4 Wire keys frozen | Serialized boundary-D keys survive field renames | `boundaryd.WireVocabularyGateTest` (kind inventory, attr-key vocabulary, record component pins) | gated 2026-08-25 |
| H-R5 Gametest inventory | The registered `@GameTest` set cannot silently shrink; verification currency is receipt-backed (`qa-results/engine-runs/`, surfaced by `status`) | `hygiene.GametestInventoryCheck` | gated |
| H-R6 Geometry spectrum rationale | Threshold constants document their partial-top-spectrum reasoning | not mechanically checkable | review-only |
| H-R7 Package structure | Main modules single-level, module names never reused as subpackages; test packages mirror main or are sanctioned metas | `architecture.PackageStructureGateTest` | gated 2026-08-25 |
| H-R8 Contract markers present | Every src/main implementer of a boundary interface carries its `contract: see` pointer (AGENTS.md 1.4.3.1) | `architecture.BoundaryContractMarkerTest` | gated 2026-08-25 |
| H-R9 Review claims cite or are unverified | Every factual claim in a review/assessment carries a file:line or doc-anchor citation; quoted promises are checked against the anchor; "does this abstraction exist" checks the abstraction-status table first | not mechanically checkable | review-only since 2026-08-27 |
| H-R10 Style law is machine-owned | AGENTS section 1 semantic rules (naming, javadoc presence, import purity, width) fail the checkstyle tasks; layout is formatter-canonical (Spotless/palantir) and never hand-reformatted | `checkstyle*` + `spotlessCheck` on the default `test` flow (`build.gradle`; wired 11631bd, promoted same day) | gated (default test path) 2026-08-27 |
| H-R11 Lint postures are pinned | The split between hard gates (checkstyle + spotlessCheck on the default `test` flow) and `-Plint` dashboards (PMD/CPD/SpotBugs/EP) cannot silently shift: a posture change is a ruling landing gate + posture table + ruling together | `architecture.LintPostureGateTest` (block wiring pins, ignoreFailures budget) | gated 2026-08-27 |
| H-R12 Decision index parity | boundaries.md decision index max equals ledger.md body max; every verdict has a citation-resolution row (AGENTS.md 0.3) | `architecture.DecisionIndexSyncGateTest` (text scan, no classpath) | gated 2026-08-29 |

### Rule detail

- **H-R1 Reflection is the exception, not the door.** Collaborator
  level unit tests live in same-package directories under
  `src/test` (`core/behavior` hosts the pathing-collaborator tests)
  and call package-private members directly - a moved class is a
  compile error, never a runtime `ClassNotFoundException` from an
  FQN string. Routing through `PathingTestAccess` (two-hop: mover,
  then collaborator field, then target) is reserved for
  integration-level assertions that must inspect mid-drive state
  from outside the package. The `Class.forName` bypass that used to
  live in `chebyshevBoundaryCases` was the violation this rewrite
  retired. Mechanical half: `hygiene.ReflectionDoorCheck` scans the
  test sources for reflective access outside the door, so a new
  inline `getDeclaredField` or `Class.forName` fails the offline
  suite before commit.
- **H-R2 No inline fully-qualified names in sources.** A type used
  in code gets an import; FQNs appear only where Java demands them
  (javadoc `{@link}`, disambiguation against a same-named import).
- **H-R3 English-only everywhere, not just markdown.** The zero-CJK
  mandate covers Java comments and Javadoc too. The first scrub
  covered `.md` only and missed Javadoc prose; both trees are now
  gated by `hygiene.EnglishOnlyScan`, which fails the offline suite
  on any CJK codepoint in covered sources. A genuine data literal
  goes through the gate's explicit allowlist - a review decision,
  not an escape hatch.
- **H-R4 Wire keys are frozen even when fields rename.** Renaming a
  state field must preserve its serialized key (the `pose` ->
  `position` rename kept the wire key `"pose"`); boundary-D payloads
  are consumed outside the repo. Mechanical half:
  `boundaryd.WireVocabularyGateTest` pins three layers - the
  EventKind constant set against a registered attr contract per
  kind, every producer attr-key literal against that vocabulary
  (both directions, so stale pins fail too), and the wire-carrying
  record component names in declaration order. Mission-owned
  verdict attrs on TASK_COMPLETED / TASK_FAILED stay open; their
  freeze belongs to each process's own tests.
- **H-R5 Gametest edits are engine-verified before commit.** The
  offline suite cannot see gametest registration: a probe-cleanup
  regex once deleted `routesThroughFenceGap` silently and every
  offline check stayed green (restored verbatim in f60c3ea). Any
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
- **H-R9 Review claims cite or are unverified.** The 2026-08-27
  external architecture review misquoted boundary B's promise
  ("touching zero Behaviors" became "zero controller changes") and
  proposed abstracting ReflexSeat - which already existed with two
  implementations. Both would have self-caught under
  cite-or-unverified: the quote would have exposed its own drift
  against the anchor, and the proposal would have found the
  interface in the single lookup below. Factual claims carry
  file:line; promise quotes carry the doc anchor and are read back
  against it; abstraction proposals check the abstraction-status
  table before claiming a gap. Misjudgment is an environment
  property, not a reader property - the gated zones (H-R1, H-R4,
  H-R7, H-R8) have zero misunderstanding history; the prose zones
  accumulated all of it.
- **H-R10 Style law is machine-owned.** The 2026-08-27 static
  analysis stack retired the manual section-1 review obligations;
  what each tool encodes lives in the config comments
  (checkstyle.xml, ruleset.xml, build.gradle), the build-and-run.md
  posture table, and H-R11's posture gate - this row keeps only the
  ruling: layout is formatter-canonical (hand-reformatting is a
  defect), review-only by recorded decision are `final`-everywhere,
  trailing commas, record layout, javadoc tag order / voice /
  nullability wording. The gate entered the default `test` flow the
  day it was wired under `-Plint` (user ruling: hygiene outweighs
  ~10s; block-verified by an injected unused import failing
  `test`). PMD flips to failing only after the god-class paydown
  round lands.
- **H-R11 Lint postures are pinned.** The 2026-08-27 toolchain split
  put checkstyle and spotlessCheck on the default `test` flow and
  left PMD/CPD/SpotBugs/Error Prone behind `-Plint` as advisory
  dashboards. That split lives as wiring scattered across four
  build.gradle blocks, and any of them drifting - a dropped
  dependsOn, a quietly added ignoreFailures, an enabled flag
  flipped - disarms a gate with no error anywhere.
  LintPostureGateTest pins each block's wiring and budgets
  `ignoreFailures = true` to exactly the two dashboards; a
  deliberate flip (the PMD paydown graduation) updates the gate, the
  posture table in doc/guide/build-and-run.md, and the ruling in the
  same commit - the pinned-inventory admission pattern H-R7 and
  H-R8 already follow. The `lint` verb in tool/mcbot_tool.py rides
  the same ruling: it replays the canonical dashboard invocation so
  agents cannot misremember the task set.
- **H-R12 Decision index parity.** The boundaries.md decision index is
  the repository-wide resolution point for every `decision N` citation
  (AGENTS.md 0.3); the ledger body is where verdict text lives. A verdict
  landing in the ledger body without an index row silently breaks citation
  resolution — the 08-27 ledger-split created two indexes with no sync
  mechanism, and decisions 40-46 landed in the body only. The gate reads
  both files as text (no classpath needed), parses the max top-level
  number from each, and asserts equality. Parsing note: ledger entries 20+
  carry 4-space leading indent (a pre-gate formatting drift; the append-only
  protocol forbids reformatting published entries), so the entry pattern
  tolerates optional leading whitespace; sub-entries (19a, 23a) are
  excluded by the digit-before-period requirement. This gate complements
  `doc check`, which covers front-matter and covers-drift but does not
  parse decision numbers.

## Abstraction status (single lookup)

What exists, how many real implementations or callers it serves,
and what promotes it next. Check this BEFORE proposing a new
abstraction or a generalization - the second-consumer rule
(glossary: promotion trigger) is law, and most "missing"
abstractions already exist.

| Abstraction | Implementations / callers | Next promotion trigger |
|---|---|---|
| `ReflexMissionSeat` (core/tick) | one parameterized seat serving the fight / rescue / forage handoffs (ledger 35); the one-impl `ReflexSeat` interface folded 2026-08-29 | a second seat IMPLEMENTATION (a fourth seat kind with distinct policy) re-raises an interface |
| `DigMission` (api/process) | DigProcess + MineProcess; a new dig-family mission costs zero controller change | a second CLAIM KIND (attack for hunt, use for eat) lifts process-driven claim injection to a declared BotProcess capability |
| `submitAimAndDig` (BotController) | mission-dig path + preemptDigClaims | a second non-dig per-tick interaction widens the pair |
| `MenuTransactions` (api.menu) | BindingActor; ruled form (ledger 29, not-claims-not-second-controller) | eat / use-item decides claim-vs-transaction; design agenda belongs to 0010 |
| mc-skill pattern (skills/) | patrol.py | a second skill extracts the conventions (wait-chain, per-leg events drain) |
| recipes materialization (harness) | dump-recipes + `~/.mc/recipes` + grep | a second heavy-read class earns the next dump verb |

## Closed rounds

Each round's narrative lives in its commits - `git log` carries the
what, how, and evidence chain. This index keeps only retrievable
anchors; the rulings those rounds produced are absorbed into the
Rule registry rows above.

| Round | Date | Commits | Closes / affected | Evidence |
|---|---|---|---|---|
| Deep-clean | 08-23 | 183c8e0, b954651, 030b49c, dbc3bd1, f233b3a, b33cb6a, a1a1f89 | stdlib adoption, FQNs became imports, `pose`->`position` wire-key-safe rename | offline suite 0 skips; main source set 8654->8396 |
| Gametest harness consolidation | 08-23 | 450bf48, 160ca4c | `GametestRig` extracted, redundant scenario removed | offline suite green |
| PathingBehavior decomposition | 08-23 | 1a68dd9..cee9a2c, 81897b8, f60c3ea | shell + WaypointCursor/PlanProgressFuse/ReplanGate/PlanLifecycle; routesThroughFenceGap incident recorded (H-R5 lesson) | 159 offline cases 0 skips; 7/7 gametests; blast radius stayed in core.behavior |
| Controller and actor decomposition | 08-25 | 29c6a3b, 1623dab, 7f87136, 72a476b, 281a147 | MissionReporter, ReflexEngageSeat, MeleeResolver, PresenceLayer, triggerVerdict | zero test edits across all five commits (258 cases) |
| Inline-FQN second sweep | 08-25 | c23cf35, 1c6d331 | H7 closed (H10 opened) | compile + full suite green |
| Test-package mirroring | 08-25 | 888fc97 | H8 closed alongside the H-R7 admission | five top-level test packages, every one a mirror or sanctioned meta |
| Sensor-interface ruling | 08-25 | 0e290ad | H9 closed (`BodyPositionSource` dedup) | three-way contract collision retired; local single-consumer sources stay nested |
| preemptDigClaims extraction | 08-25 | 5e26c15 | H10 closed | park semantics pinned by ArbiterGateTest / ReflexChainGateTest |
| Static analysis stack | 08-27 | 11631bd, 8b18e64, 7bd7804, b3d1e80, 2529455, b3cc623, 95e1a64, 8ea8554 | H-R10 admitted, then promoted to the default `test` flow same day | baselines zeroed; Error Prone 24->20->0; suite 407/407 through the landing chain |

The repo-wide reformat (7bd7804, 198 files) is blame-excluded via
`.git-blame-ignore-revs`; the WorldCommandsWireShapeGateTest regexes
gained `\s*` tolerance in that same commit for the rewrap.

## Ruling anchors in code

Where the live architectural rulings physically live, so "why is it
built this way" is one hop from this table instead of a
commit-archaeology dig. Rows carry verified anchors only; a ruling
without an anchor is a workplan item, not a row here.

| Ruling | Anchor | Settled in |
|---|---|---|
| Plan-progress fuse: three OR criteria; accumulator immune to external replan | `PlanProgressFuse` Javadoc (invariants migrated verbatim from issue 0001) | boundaries.md ledger 20 (issue 0001 archived 2026-08-24) |
| Vertical trigger gate: airborne ticks skip trigger eval; landing edge bypasses cooldown | `ReplanGate` Javadoc + steering in `PathingBehavior` | cba8dd3 |
| Park semantics: explicit ParkResult, atomic retire sweep, resume revalidation | `TaskArbiter.forcePauseAll` + `ParkResult` Javadoc | d3d440b |
| One-tick retirement lap: verdict announced on the reflex tick; no tail sweep inside arbiter tick | `TaskArbiter.tick` tail comment; harness-interaction.md fidelity rules | d3d440b |
| Shape contract: STEP_UP_REACH / STANDABLE_THRESHOLD split, footprint rule, fence-as-wall | `CollisionShape` constant Javadoc + boundaries.md decision ledger 19b (issue 0002 archived) | 4edc609 |
| Melee LOS clip: eye-to-surface ray with lava-opaque cells | `MeleeResolver.sightBlocked` | player-behavior-RE combat record |
| Executor jump actuation: direct `jumpFromGround()` under swapped MoveControl | `BotBodyEntity` deviation comment | ed54e4d |

## Open items

- **CLOSED 2026-08-29 - H1 api/ thick-interface trimming.** A full
  consumer census (60 api files, ~130 public members) measured the
  thickness at ~8%: ten dead or single-tier members, zero on any
  doc-pinned boundary surface. The trims landed in 88568bd
  (Behavior.name, Movement.source/describe,
  ThreatBlackboard.saturationLevel, BlockSnapshot.isUnknown,
  PriorityBands band constants to private,
  PlanLifecycle.chebyshev deduped onto CellPos.chebyshevTo); the
  census' kept-with-ruling rows (InventoryView/Direction accessor
  families, BlockTraits factories) are natural carrier reads or
  decision-25 reserved axes. The scheduling worry (trim before
  Stage 3 or churn boundary-D twice) dissolved: nothing trimmed was
  boundary-D surface.
- **CLOSED 2026-08-28 - god-class paydown round with PMD as the
  yardstick.** The install-time 18 findings cleared across the
  paydown rounds (MenuVerbs/MenuSlotLayouts/ReflexRuleJsonReader/
  PlayerRegion/CraftingPlanner/TransferPlanner splits; the 08-28 wave
  extracted ReflexPreemption, CrashLatch, ActorMenuTransactions,
  VerbTaskHandler, RangedLoadouts, TargetTracker, Goals.cellOf,
  BotStateSnapshots and dropped BotController's suppression with the
  wall passing clean). -Plint is fully green: PMD 0, CPD 0. The
  remaining inline exemptions (MenuVerbs, WorldCommands, BotBodyEntity,
  HungryProcess, MenuFixtures, MenuPlannerCountedRoleTest) carry
  sanctioned rulings - flat tables and engine-shaped entities, not
  debt.
- **OPEN - SpotBugs first-triage queue.** api/core-scope first
  pass flagged ThreatBlackboard's six suspected dead fields
  (UrF/UwF), GotoCommandHandler's internal-representation exposure
  (EI2), and DigCommandHandler's NPE-catch pattern (DCN). Triage
  assigns each a severity or a suppression-with-ruling; dashboard
  posture stays until then.
- **OPEN - CPD dedup round.** ~18 duplicate blocks >=100 tokens at
  install time, dominated by known mock-vs-server implementation
  pairs; the pre-sorted list is `python tool/mcbot_tool.py gradle
  cpdCheck -Plint` away, so the round starts from evidence, not
  memory.
- **OPEN - JavadocMethod tag-completeness graduation.** 75 live
  @param/@return tag gaps across public API; presence is gated
  (H-R10), completeness returns when the tag debt is paid down or
  the rule is consciously re-scoped (config comment in
  checkstyle.xml carries the deferral).
