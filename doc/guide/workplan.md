---
title: Work Plan (effort-sized checklist)
last_verified: 2026-08-26
covers:
  - doc/architecture/boundaries.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/decisions/0005-tick-pipeline-exception-policy.md
---

# Work Plan

Ordered by dependency, sized by relative effort: S < M < L < XL.
No item depends on anything below it. Check items off in place;
do not start an item before its blockers are checked.

## Stage 0 - contracts skeleton (all offline, mocks only) — DONE 2026-08-21

- [x] S  core/api package layout + build wiring (zero MC imports enforced)
- [x] S  CommandChannel interface (boundary D command side):
         `submit(BotCommand) -> SubmitResult` (sealed `Ok(taskId) | Rejected(reason)`)
         + `cancel(taskId) -> boolean`. Sync error path for structural
         failures; well-formed commands get a taskId, result arrives
         via the event stream. See boundaries.md Boundary D protocol.    [dep: pkg]
- [x] M  EventQueue + EventBatch + BotEvent (boundary D event side):
         polled `statusSnapshot(sinceEventId) -> EventBatch`,
         DEFAULT_CAP=200 backpressure, `droppedCount` + synthetic
         `EVENT_DROPPED` on overflow, `sinceEventId` cursor, `resetAt`
         for bot-restart detection, multi-holder lock reserved
         (single-holder reference impl in Stage 0).                    [dep: pkg]
- [x] S  BotState interface + STATE_PUSH event (boundary D state side):
         model-relevant categorical fields only (pose, inventory
         counts, selected slot, effect types + amplifiers, current
         task summary). NO durability, NO enchantment, NO effect
         remaining ticks. The "what's in / what's out" rule is
         documented in the interface Javadoc, not in an ADR.            [dep: pkg]
- [x] M  WorldView interface + reserved primitives (BlockSnapshot,
         EntitySnapshot, CollisionShape stubs) + MockWorldView
         + `isLoaded(pos): boolean` + `ViewMode{SNAPSHOT, LIVE}`
         parameter (per decision 17 / 17a / 17b)                          [dep: pkg]
- [x] S  Actor interface: Channel enum, Claim record, conflict resolver [dep: pkg]
- [x] S  Goal algebra minimal: Goal predicate + GoalBlock + GoalNear    [dep: pkg]
- [x] S  BotProcess contract + TaskArbiter (bands, DEFER,
         winner-take-all, fresh-intent-first)                           [dep: goal]
- [x] S  ReflexRule dynamic priority fn + ThreatBlackboard +
         SurvivalReflexLayer shell                                      [dep: arbiter]
- [x] S  Minimal reflex rule FREEZE_ON_LOW_HEALTH + offline test proving
         full chain: sensor -> blackboard -> rule -> preempt ->
         resume -> world-assumption check                               [dep: reflex]
- [x] M  ExecutionReport + PathingBehavior shell emitting STUCK from a
         displacement window (pure function over mock poses)            [dep: actor]
- [x] M  BotController tick ordering (reflex -> arbiter -> behaviors ->
         flush) + server-tick END binding thin adapter
         + exception latch + crashed state machine + MinimalReflex +
         dual-channel crash report (per ADR-0005)                         [dep: all above]
         NOTE: pipeline, latch and reporting are done and gated; the
         Forge-side subscription wiring itself is deferred to the
         Stage 1 entity-binding spike (ADR-0004 D5 keeps the adapter
         as the only MC-aware seam; tests drive onTick directly).

**Stage gate**:
- ordering test proves a non-null reflex skips mission for that tick
- resume-validation test proves an invalidated context drops the task
  instead of resuming
- **exception test** (per ADR-0005): a `RuntimeException` thrown from
  any of the four tick stages results in (a) the crash latch set,
  (b) the next tick's `MinimalReflex` only, (c) both reporting
  channels having received the event, (d) no `ReportedException`
  reaching the MC main thread
- **disclosure test** (per boundary D protocol): a structurally
  broken command returns `Rejected(reason)` synchronously from
  `submit()`; a well-formed command's execution result arrives
  via the event stream; queue overflow produces `droppedCount > 0`
  and a synthetic `EVENT_DROPPED` in the next `EventBatch`;
  `getState()` returns only model-relevant fields (no durability,
  no enchantment, no effect remaining ticks)

## Stage 1 - vertical slice: walk to a block

- [x] L  Entity-binding spike: choose fake-player carrier; implement
         WorldView+Actor adapters; physics stays engine-owned           [gate0]
         OUTCOME: custom PathfinderMob carrier (BotBodyEntity), AI step
         skipped so MoveControl cannot fight the binding for zza;
         ServerPlayer parity deferred. Ratification at Stage 1 review.
- [x] M  GotoProcess state machine (IDLE/APPROACH/SUCCEEDED/FAILED)
         consuming reports only                                         [spike]
- [x] S  GotoCommand + CommandQueue seam verbs (submit/cancel/status)
         + JSON codec                                                   [goto]
- [x] L  PathingBehavior straight-line mover: MOVE+ROT claims toward
         target, arrival via goal predicate                             [spike]
- [x] M  stuck fuse (displacement < eps over window -> STUCK) and
         timeoutTicks enforcement (mission-side hard cutoff per
         Stage 1 review: TIMEOUT is the mission giving up on its own
         budget; STUCK stays behavior-side)                        [mission]
- [x] S  push-off recovery: external displacement triggers re-path      [mover]
- [x] M  gametest suite on flat ground:
         walks-to-block / recovers-when-shoved / fails-cleanly-unwalkable [fuse]
         RESOLVED: 1.20.1 ships no empty template; StructureUtils
         falls back to `gameteststructures/<name>.snbt` relative to
         the gameTestServer working dir (`run/`). Template lives at
         repo-root `gameteststructures/empty16x8x16.snbt` and must be
         copied to `run/gameteststructures/`. Suite:
         gametest/BotSliceGameTests via `build runGameTest`.
- [x] S  real ThreatSensor (entity scan, bearing sector incl. behind,
         creeper fuse accessor) writing blackboard in-game              [binding]
         NOTE: fuse accessor deferred - the sensor records type and
         distance; swell-state refinement lands with the first rule
         that needs it.

**Stage gate** (the milestone): bot walks to the target block on flat
ground; when shoved mid-path it re-walks; when the target is unreachable
it reports FAILED with a reason - no hang, no silent stall.
STATUS: PASSED 2026-08-22 - all three scenarios green in-engine via
`python tool/mcbot_tool.py build runGameTest` (exit code 3 = failed
test count when red).

### Stage 1 review follow-ups

1. Ratify the entity-binding carrier choice (PathfinderMob vs
   ServerPlayer subclass) against real slice behavior.
2. Ratify boundary-B vocabulary growth: BotProcess.onExecutionReport
   default method + TerminalMission marker interface.
3. RESOLVED: gametest template question - see the Stage 1 item above.

## Stage 2 backlog - unlocked only by the stage-1 gate

- [x] XL A* over the Movement five-tuple model
         ENGINE+INTEGRATION SHIPPED 2026-08-22: offline finder
         (BasicMoves walk/diag/climb/drop<=3, node-budget safety net,
         best-partial on budget cut only) plus waypoint-follower
         integration in PathingBehavior with replan ladder
         (exhaustion / drift>3 / progress fuse) and cooldown guard;
         verified in-engine by all three stage-1 gametests now
         route-planning instead of straight-thrusting. Vocabulary
         growth (parkour, pillar, swim) follows demand.
- [x] M  async replan worker + blackboard staleness guard
         SHIPPED 2026-08-22: PlanWorker (single daemon thread, wall
         clock required) + SnapshotWorldView (decision 17b SNAPSHOT
         half; hull capture, outside-box reads unknown) + adoption
         freshness guard in PathingBehavior (goal unchanged AND start
         cell still ours; stale results discarded whole with an
         immediate re-request). AStarPathFinder grew the wall-clock
         overload checked every 64 expansions. McBotServer and all
         gametests run this path.
- [x] L  DefendProcess planner + CombatDirective + CombatBehavior
         micro-execution skeleton
         SHIPPED 2026-08-22: combat rides the frozen four channels -
         Overrides.combat carries sealed CombatOrder(Attack(targetId));
         DefendProcess scans, engages the nearest hostile at standoff
         range (GoalNear r=2 so plans never route INTO the target),
         and owns terminal verdicts via scans/leash/timeout ONLY
         (locomotion reports are execution weather, never the fight's
         verdict). CombatBehavior aims and paces swings; BindingActor
         resolves USE presses as reach+cone melee. Reflex supremacy
         kept per decision 9. Verified by CombatSkeletonGateTest and
         gametest defendsbyKillingZombie (4/4 in-engine).
- [x] S  rule-table JSON loading through Registry + Codec + ReloadListener
         SHIPPED 2026-08-22 (gson instead of Codec - pure-Java parser
         stays offline-testable per boundaries.md decision 5):
         ReflexRuleJson parse/write, SurvivalReflexLayer.replaceRules,
         ReflexRuleReloader on the vanilla reload pipeline, default
         table shipped as a built-in datapack entry. Review NITs 1/2
         landed alongside (attrs.reason structured events;
         BlockSnapshot id-style Javadoc).

### Stage 2 review-driven hardening (post Stage 2 first-batch)

- [x] S  Reflex anti-oscillation: double threshold + hold window
         SHIPPED 2026-08-23 (build/test green; ReflexHysteresisGateTest
         6 cases pass): new api.reflex.ReflexHysteresis
         interface (trigger / release / signalValue / minHoldTicks)
         with default minHoldTicks=0 so non-hysteretic rules opt in
         by choice. FreezeOnLowHealthRule implements it with
         trigger 10, release 15 (5-point deadband), hold 20 ticks.
         SurvivalReflexLayer gained a per-rule state machine
         (active / ticksSinceChange / lastPriority) and a decideHysteresis
         scheduler. The 5-point deadband absorbs signal jitter
         (9, 11, 9, 11, ... stays firing); the hold window gates
         every flip so a clean threshold-crossing still waits 20
         ticks before the decision changes. Held ticks re-use the
         last positive priority so the arbiter sees a stable number
         across the hold. replaceRules() clears the state map so a
         same-name reload starts clean. New gate test
         ReflexHysteresisGateTest (6 cases) + four pre-existing tests
         drained the hold window where they previously expected
         immediate silence after heal.
- [x] S  Tick harness last-ditch seam: emergencyLatch + outer catch
         SHIPPED 2026-08-22: BotController.emergencyLatch(cause)
         public entry point that calls the same handleCrash path the
         pipeline uses. McBotServer.onServerTick now wraps its body
         in try-catch and calls emergencyLatch on RuntimeException -
         ADR-0005 D2's contract is now closed at the listener
         boundary. New ExceptionPolicyGateTest case proves the
         outside-the-pipeline path mirrors the in-pipeline one.
- [x] S  A* confidence gate: PARTIAL -> FAILED on low progress
         SHIPPED 2026-08-22: PathResult gained reachabilityConfidence
         in [0,1] (1 - bestH / startH, clamped). New constant
         MIN_PARTIAL_CONFIDENCE = 0.10 collapses PARTIAL to FAILED
         when the best-frontier ratio is below the floor - a
         fractional-tick advance would otherwise hand the follower
         a doomed prefix and loop it. Reached-goal still scores 1.0;
         FAILED still 0.0; constructor rejects NaN and out-of-range
         values. AStarGateTest grew two new cases
         (lowConfidencePartialCollapsesToFailed, pathResultConfidenceIsValidated).
- [x] S  Idempotency key on submit (boundary D command channel)
         SHIPPED 2026-08-23 (build/test green; new gate test
         IdempotencyKeyGateTest, 7 cases): C-scheme
         dedupe (explicit idempotencyKey OR verb+args fallback hash)
         added on CommandChannel.submit(BotCommand, String). Old
         submit(BotCommand) preserved as a default delegate so all
         existing callers keep working. SubmitResult.Ok gained an
         idempotencyReplay boolean; fresh() and replay() factories
         name the two states. CommandBus owns the dedupe cache
         (effective key -> taskId) and a reverse index (taskId ->
         effective key) for O(1) cancel/finishTask eviction;
         canonicalArgs sorts the args TreeMap to neutralise the
         caller's Map.copyOf insertion order. boundaries.md
         Boundary D protocol carries the full contract including
         the dedupe-window semantics (cache evicted on terminal
         state, retry after terminate is a fresh acceptance not
         an error). Gate coverage: explicit-key collapse to the
         first acceptance without re-execution; verb+args
         fallback dedupe (identical collapses, different args
         independent); insertion-order-indifferent canonical
         args; distinct explicit keys stay independent;
         terminal-state and cancel both close the window into a
         fresh acceptance; rejected submissions leave no cache
         entry.
- [x] S  Behind-consumer recovery: EVENT_GAP + state resync contract
         SHIPPED 2026-08-23 (build/test green; 6 gap cases +
         6 disclosure cases all pass): events answer "what
         happened", state answers "what is". A consumer whose
         sinceEventId is older than the queue's oldest retained
         entry needs the second one first - the events between
         its cursor and the queue head are gone, by design
         (queue rotation), and no amount of re-polling recovers
         them. New EventKind.EVENT_GAP constant;
         InMemoryEventQueue.statusSnapshot detects a stale
         cursor and prepends a synthetic EVENT_GAP at the head
         of the page carrying attrs {count, since, oldest} and
         urgent=true. Distinct from EVENT_DROPPED (push-time
         overflow): that one fires at push time when the queue
         overflows; this one fires at poll time when the caller
         finally notices the loss. Two non-firing cases (since=0
         "first poll, no gap"; empty queue after reset() "use
         resetAt, not gap") are explicit per boundaries.md
         Behind-consumer recovery contract. The recovery
         sequence is fixed and lives in boundaries.md: on
         EVENT_GAP the harness calls getState() first, resets
         the cursor to the gap's oldest, and resumes the normal
         poll loop. New gate tests GapEventGateTest (6 cases)
         and DisclosureGateTest (6 cases, including the
         post-reset polling invariant).
- [x] M  Shape-vs-traits split: geometry derived, traits registered
         SHIPPED 2026-08-23 (full suite green, zero skips after the
         issue 0002 contract settled - CollisionShapeGateTest 8,
         BlockTraitsRegistryGateTest 10, MockWorldViewShapeGateTest
         9, BasicMovesShapeGateTest 6): new
         api.world.CollisionShape now carries a cell-local Box
         (0..1) and the derived predicates passable() and
         walkableTop(). passable = EMPTY or PARTIAL with top below
         STEP_UP_REACH (0.625); walkableTop = FULL_CUBE or PARTIAL
         with top >= STANDABLE_THRESHOLD (0.5, the floor of MC's
         partial-top spectrum) spanning >= BODY_WIDTH (0.6) on both
         horizontal axes. A fence cell is therefore a wall with an
         unstandable top - vanilla-aligned; the way past a fence is
         the missing-post EMPTY cell. BasicMoves
         .passable / supported / standable now read the shape,
         never the block id. New api.world.BlockTraits record
         (climbable / liquid / damaging) plus BlockTraitsRegistry
         interface and core.world.MapBlockTraitsRegistry
         implementation: state-aware keys (stone_slab[type=bottom]
         != stone_slab[type=top]) with a bare-id fallback,
         defaults for unknown ids, mutable-then-seal for build
         time. WorldView gained getCollisionShape(pos, mode) and
         getBlockTraits(pos, mode) as default methods (safe
         defaults = full cube / no traits). MockWorldView grew
         putShape / putTraits / setTraitsRegistry for offline
         test setup. boundaries.md decision 19 + 19a pin the
         discipline: every new Movement must answer "can its
         full physical precondition be derived from CollisionShape
         plus the small traits registry" before it lands. Four
         new gate tests: CollisionShapeGateTest (8 cases
         covering all boundary shapes - empty / full / lower
         slab / upper slab / fence / thin plate / box range
         validation / canonical factories), BlockTraitsRegistry
         GateTest (10 cases covering exact / state-aware / bare
         fallback / unknown-defaults / seal / blank-rejection /
         entries view), MockWorldViewShapeGateTest (9 cases for
         the offline injection surface), BasicMovesShapeGateTest
         (6 cases proving the move predicates answer from shape
         not id, including the same geometry under three
         different block ids returning the same answer). The
         shape-bearing corridor is now covered in-engine by
         GauntletGameTests (see follow-up 2).

### Stage 2 closeout follow-ups

1. RESOLVED 2026-08-23 (issue 0002): the five disabled
   world/collision cases are re-enabled and green under the
   settled contract - STEP_UP_REACH / STANDABLE_THRESHOLD split
   (step-up reach vs standability floor are different physical
   quantities), a BODY_WIDTH footprint rule on walkableTop, and
   fence-as-wall semantics replacing the physically-impossible
   squeeze-past expectation. Settled semantics now recorded as
   boundaries.md decision ledger 19b (issue 0002 archived).
2. RESOLVED 2026-08-23: gauntlet gametest shipped as
   GauntletGameTests and green in-engine via build runGameTest
   (7/7 with the slice and combat suites). Sections: bottom-slab
   carpet (auto-step), one-block plateau (JumpUp execution),
   drop off the far edge, fence wall with a single missing-post
   gap, plus a focused gap-routing test. Building it exposed the
   executor half of the climb vocabulary: steering's jump flag
   was never actuated because setJumping consumption is dead
   under the swapped-out MoveControl - BotBodyEntity now fires
   jumpFromGround directly (documented deviation). Water/lava
   sections stay out: no swim Movement exists; the function
   map's GAP rows own that growth.
3. RESOLVED 2026-08-23: PathingBehavior (738 lines) decomposed
   into an orchestration shell plus four package-private
   single-concern collaborators in core.behavior - WaypointCursor
   (126), PlanProgressFuse (159, Ruling-a invariants verbatim),
   ReplanGate (101), PlanLifecycle (235, Chebyshev freshness);
   shell now 500 lines. Behaviour zero-change: verdicts, tick
   order, keepalive keys, and ctor overloads all pinned by the
   existing gates; tickpipeline reflection centralised in
   PathingTestAccess. Verified offline (159 cases, 0 skips) and
   in-engine (7/7 gametests).
4. OPEN 2026-08-23: RCON console bridge shipped (boundary-D
   command surface + tool/rcon.py session ledger) - the first
   external transport. Acceptance is convergence criterion 2's
   rehearsal: one body driven unattended through /bot verbs for
   10 minutes of overworld, surviving without human input; the
   run's telemetry lands in tool/sessions/<run>/ and its verdict
   closes this entry.
5. OPEN 2026-08-23: swim vocabulary landed after the acceptance
   rehearsal found a body stranded in a lake - every surrounding
   cell read unstunnable (empty collision shape) and the planner
   answered NO_PATH correctly for a vocabulary that could not
   swim. Swim/SwimUp now read the liquid trait (decision 19a:
   precondition derivable from Shape plus Traits), Drop accepts a
   liquid landing, and BindingWorldView carries a baseline trait
   registry (water/lava) wired in both the live entry class and
   the gametest rig; datapack JSON supersedes the code baseline as
   a follow-up. PLAN_WALL_CLOCK_MS raised 50 -> 200: with the
   larger edge set, a budget-cut no-route search returned a
   misleading partial instead of concluding NO_PATH. Verified by
   BasicMovesSwimTest offline and crossesWaterTrench in-engine;
   gametests 8/8.
6. OPEN 2026-08-24: unreachable-goal escalation (NoPathEscalator,
   boundaries.md decision 21) landed after the verif-1 live session
   showed a floating goal burning the full 600-tick mission budget
   through a budget-cut partial loop (21s planning churn, 1-2-cell
   partial adoptions, TIMEOUT instead of NO_PATH). Offline-gated
   (NoPathEscalatorTest + NoPathEscalationGateTest); the emergent
   partial-loop shape itself is async/wall-clock field behaviour,
   so acceptance is a live rerun of the verif-1 scenario: floating
   goal must fail NO_PATH within seconds and the keepalive stream
   must show noPathWitnesses climbing to 3 first.
7. OPEN 2026-08-25: gameTestServer shares the run/ game directory
   with the server run (no gameDirectory configured in build.gradle
   runs), so a live runServer holds run/world/session.lock and any
   gametest attempt dies in DirectoryLock.create - observed when a
   leftover soak server blocked runGameTest for an hour. The tool's
   run.<task> lock namespaces assume game tasks only read shared
   files; that holds for client (run/saves) but NOT for
   gameTestServer (run/world, same as server). Fix direction: a
   dedicated gameDirectory for the gameTestServer run (e.g.
   run/gametest) in build.gradle; also decide whether the tool
   should serialize run.runServer vs run.runGameTest as a
   documentation note until then (a live server + gametest cannot
   coexist today).
8. RESOLVED 2026-08-25 (gametest fidelity hardening): the gametest
   rig and the /botspawn wiring built the same pipeline by hand and
   had drifted - the rig lacked the engage reflex, the engage
   mission factory, the LevelThreatSensor feed and the pre-tick
   lava flag. Both now assemble through one factory
   (adapter.BotAssembly) and the rig's driveTick mirrors the server
   listener's tick order, so wiring drift is impossible by
   construction. Consequences and additions in the same pass:
   - defendsByKillingZombie converted to the production reflex-
     engage chain (no test-side mission submission); the refusal
     scenario moved its skeleton to the 8-cell standoff so the
     direct-submission refusal stays isolated from reflex churn,
     and its vacuous NoAi health assertion became a 15-tick
     refusal window.
   - New scenarios (+5): crash-latch (ADR-0005 in-engine: latch,
     MinimalReflex-only, reset recovery), production-path
     integration (real /botspawn + /bot goto + ServerTickEvent,
     no test-side pipeline access), retaliation (AI zombie fights
     back under a sun-blocking roof), sight-blocked combat (wall-
     blind engage, zero damage through a full wall, honest TIMEOUT),
     and lava trench (fire-resistance harness; closes issue 0004's
     tracked lava-scenario follow-up).
   - gauntletEndToEnd's plateau checkpoint was dead code since
     introduction (absolute Y vs relative constant, the f98bf9d
     defect class, masked by the || !isActive() escape) - fixed.
   - Suite 10 -> 15 scenarios, all green via build runGameTest;
     GametestInventoryCheck pins the new inventory.
9. RESOLVED 2026-08-25 (threat visibility - user ruling): "the world
   treats the bot as a player, otherwise how would crafting-table
   interaction work later" - the ruling lands on the threat axis now:
   BotBodyEntity runs a carrier-side presence pass (every 20 ticks;
   free target slot + line of sight + the monster's own FOLLOW_RANGE;
   no-AI mobs stay frozen) so hostiles acquire the body on sight and
   unprovoked combat exists, which is what makes the survival gate's
   night acceptance honest. Pinned in-engine by hostilesAggroOnSight
   (zombie at 7 cells, outside the 6-cell engage trigger - only the
   world can start that fight). The interaction axis of the same
   ruling stays with the BotPlayerFacade plan (issue 0007 Path A):
   menus ask through typed Player parameters, which entity scans
   never see - the facade answers those, the presence pass answers
   scans. Suite 16/16 green.

## Pre-Stage-3 survival gate - basic survival capability

Unlocked by the Stage 2 backlog; blocks all Stage 3 work. The gate
exists because convergence criterion 2 (10-minute unattended
survival) was red at construction: the 2026-08-24 live session
(tool/sessions/20260824T135850Z-playerfeel-live) lost the body
twice unattended - drowned in the spawn lake after ~20 min (air
reflex reserved as issue 0004 F6(2)) and killed in a night cave
while idle (nothing engages hostiles without a mission) - and the
carrier had no health-recovery path at the time. All three core
defenses (air reflex, idle combat, passive regen) are now landed;
the remaining gate items are the 10-minute acceptance rehearsal,
the H-R4 convergence pass, and bookkeeping.

- [x] M  Air-supply reflex (issue 0004 F6(2)): surface the body's
         air supply on ThreatBlackboard + adapter sensor wiring;
         the rule itself is rule-table JSON (low air -> preempt
         toward the surface; Swim/SwimUp vocabulary already makes
         the surface plannable). MinimalReflex's jump-while-in-fluid
         is the crashed-state precedent, not the mechanism here -
         this is a normal reflex directive. Offline gate test +
         in-engine drowning-recovery scenario.
         Landed 2026-08-25 (vitals sensing pass, commit 6ccdb3b):
         ThreatBlackboard.airSupply + LevelThreatSensor feed,
         SurfaceOnLowAirRule (trigger 80 / release 200 / priority 110,
         hysteresis), reflex_rules.json SURFACE_ON_LOW_AIR row,
         SurfaceOnLowAirRuleTest offline gate, surfacesWhenAirRunsLow
         gametest (4-deep pool, forced air 60, verifies surface +
         survival + air regen).                              [dep: none]
- [x] M  Idle hostile protection: today nothing engages a hostile
         unless a DefendProcess mission is running. Design ruling
         at pickup: standing idle-band DefendProcess (arbiter-side,
         wins only when nothing else claims) vs reflex-carried
         combat directive (boundary-C shaped, but reflexes are
         stateless one-tick decisions and combat needs target
         tracking - the arbiter-side shape is the working
         hypothesis). Offline gate + in-engine night-idle
         survival scenario.
         Landed 2026-08-25 (idle-combat reflex, commit 79961e9
         context): the reflex-carried path was chosen over the
         arbiter-side idle-band — EngageOnHostileProximityRule
         (trigger 6 blocks / release 14 / priority 90, hysteresis)
         notices a hostile inside melee range and the controller's
         ENGAGE handling mints a reflex-owned DefendProcess via the
         assembly factory. reflex_rules.json ENGAGE_ON_HOSTILE_PROXIMITY
         row, EngageReflexGateTest offline gate (preemption + mission
         handoff + resume), defendsByKillingZombie gametest (no
         mission submitted, pure reflex trigger → kill → survive),
         refusesRangedItCannotAnswer gametest (standoff skeleton does
         NOT trip the rule). Ranged types are REFUSED by DefendProcess
         (decision 11) so the rule's 6-block trigger never fires on a
         kiting skeleton at its preferred 8-10 block standoff. [dep: none]
- [x] S  Body recovery policy: decide and implement the v1 health
         floor. Working hypothesis: peaceful-style regeneration as
         a documented carrier deviation; eating lands with Stage 3
         UseItem, not here.
         Landed 2026-08-25: peaceful-style passive regen on
         BotBodyEntity.customServerAiStep — 1 HP per 20 ticks
         (REGEN_INTERVAL_TICKS / REGEN_AMOUNT constants) while
         health < maxHealth, gated on tickCount alignment. Runs
         regardless of crash latch (the latch freezes the brain,
         not the body's life support). Documented as a carrier
         deviation in the class Javadoc so a future "remove free
         regen" decision is a one-line delete. regeneratesHealthWhenBelowMax
         gametest (setHealth 5 → verify intermediate rise → verify
         full recovery to max). Eating (Stage 3 UseItem, Phase 4)
         may supplement or replace this floor.               [dep: none]
- [ ] M  10-minute acceptance rehearsal through the RCON bridge to
         a recorded verdict in tool/sessions/. Programmatic driver,
         boundary-D verbs only, mixed day/night overworld, at
         least one reflex preemption and one failed-task recovery
         observed. Closes criterion 2 + closeout follow-ups 4 and
         5 + issue 0005's live acceptance run in one stroke.     [dep: air, idle, recovery]
- [x] S  Harness surface convergence slice 1 (issue 0011 D2/D3/D4/
         D5): /bot reset (the ADR-0005 5a promise finally wired,
         echoes `crashed`), /bot events [since] [only] kind-prefix
         narrowing via EventBatch.narrowedToKindPrefix (cursor
         fields stay true; EVENT_GAP/EVENT_DROPPED survive every
         filter), the console verb table + disclosure responsibility
         table frozen as contract (ledger 28). D1 session runtime
         rides the rehearsal item above; D6 MCP/HTTP deferred to the
         boundary-D reopen trigger.                                 [dep: none]
- [ ] S  H-R4 wire-key convergence pass (code-health ledger;
         scheduled before any Stage 3 vocabulary lands so pose work
         does not churn boundary-D consumers twice).            [dep: none]
- [ ] S  Bookkeeping: archive resolved issue 0006 per the issue
         workflow; rule issue 0004's disposition (keep open as the
         Stage 3 vocabulary seed vs promote); let the datapack
         water/lava trait JSON supersede the code baseline
         (closeout follow-up 5 residue).                        [dep: none]
- [x] M  Dig capability, first block-interaction slice (issue 0009;
         user ruling 2026-08-25, motivated by the gravel-suffocation
         escape): INTERACT channel + Intent.Dig (claim-per-tick is
         the held button), adapter DigExecutor mirroring vanilla
         destroy progress, core DigPacing arithmetic, suffocation
         rule upgraded FREEZE -> DIG (renamed DIG_ON_SUFFOCATION,
         old type a hard parse error), sensor eye-cell feed,
         datapack row rename. Offline gates (DigPacingTest,
         DigOnSuffocationRuleTest, controller claim mapping,
         reload-parity) + in-engine digsFreeWhenSuffocating
         (self-rescue, no scenario-side removal).               [dep: none]
- [x] M  Reflex ESCAPE action + rescue mission handoff (issue 0008
         D2-upgraded + D5-revised; user ruling 2026-08-25 "must have
         shortest-shore escape" + "fire find-water by burn-to-death
         threshold"): ReflexAction.ESCAPE (mission-handoff twin of
         ENGAGE), ReflexRescueSeat (80-tick cooldown),
         RescueMissionFactory (adapter; inLava -> nearest shore,
         fire -> nearest water-adjacent, 12-block scan), BotController
         rescue branch + resume guard dispatch. Two rules:
         ESCAPE_ON_LAVA (130, replaces ASCEND_IN_LETHAL_FLUID) and
         EXTINGUISH_FIRE (105, lethal-band only: fireTicks/20 >=
         health). Both carry reload-parity JSON + datapack rows.
         Offline gates (EscapeLavaRuleTest, RuleTableGateTest
         lockstep, controller ESCAPE mapping) + in-engine
         escapesLavaToShore + findsWaterWhenBurning. Ledger 26.
                                                                 [dep: none]

## Stage 3 - player parity (unlocked only by the survival gate)

Design review: doc/architecture/issues/0007-player-parity-
interaction.md (raw analysis distilled 2026-08-24; vanilla-API
claims spot-checked against the decompiled tree). Every item that
grows a frozen surface (Intent kinds, Actor channels, the carrier
decision, tick ordering) is backlog text until the Stage 3 review
ratifies it - per AGENTS.md the stage review is the only place the
freeze lifts.

- [x] L  Phase 1 - inventory sense + basic interaction: api
         ItemView/InventoryView (String ids, zero MC imports),
         SimpleContainer-backed BindingInventory, Intent.
         InteractBlock (face + hitPos; placement depends on the
         clicked face) with hold-to-mine semantics and the
         adapter-side mining state machine (survival digging is
         multi-tick progress, not single-shot), DropSelected.
         Acceptance: bot digs a block, reads its own inventory,
         drops an item.
         Progress 2026-08-25: inventory sense half landed in
         parallel with the survival gate (disjoint files — api/ +
         adapter/BindingInventory vs tick/reflex/entity). api
         ItemView + InventoryView records, WorldView.getInventory()
         default, adapter BindingInventory (SimpleContainer-backed,
         41 slots), wired into BotBodyEntity / BindingActor /
         BindingWorldView / BotAssembly / MockWorldView. Offline
         gate: InventorySenseTest (26 assertions). DropSelected
         landed 2026-08-25: Intent.DropSelected (fullStack boolean)
         on INTERACT channel, rising-edge fire in BindingActor,
         BotBodyEntity.dropSelectedItem via spawnAtLocation. Offline
         gate: DropSelectedIntentTest (10 assertions). In-engine
         gate: dropsSelectedItem gametest. InteractBlock (place)
         landed 2026-08-25: Intent.InteractBlock (target + face +
         hitPos) on INTERACT channel, api Direction enum (6 values,
         MC ordinal order), rising-edge fire in BindingActor,
         InteractBlockExecutor bypasses useItemOn (needs ServerPlayer)
         and places via level.setBlock + item shrink. Phase 1 scope:
         default-state block placement only (direction-aware states,
         use-block, replaceable cells deferred to Phase 2). Offline
         gate: InteractBlockIntentTest (21 assertions). In-engine
         gate: placesBlockOnInteract gametest. DigExecutor tool
         supplier landed 2026-08-25: reads selected ItemStack every
         tick, computes toolSpeed (getDestroySpeed) + hasCorrectTool
         (!requiresCorrectToolForDrops || isCorrectToolForDrops),
         passes to DigPacing; destroyBlock uses hasCorrectTool for
         drops (hand-mined stone breaks but no cobblestone). DigPacing
         signature grew to (destroySpeed, toolSpeed, hasCorrectTool,
         onGround). Offline gate: DigPacingTest extended with iron
         pickaxe (~8 ticks stone vs 150 bare-hand), wooden pickaxe
         (~23), correct-tool divisor 30 vs 100. DIG shipped via issue
         0009, now with tool support. Acceptance criterion fully met
         (dig + inventory read + drop). Phase 1 COMPLETE.
         [Phase 1 fully landed 2026-08-25]
- [x] L  Phase 2 - menu system + crafting-table disclosure: api
         MenuView/CraftingView (2x2 InventoryMenu baseline, 3x3
         table extension), core click-sequence planner over the
         read-only view (core holds no menu state - all mutations
         through the adapter's menu.clicked() so ResultSlot
         material consumption is never bypassed), adapter
         BindingMenu over AbstractContainerMenu with a no-op
         ContainerSynchronizer, MenuOpener per block kind,
         BotPlayerFacade (minimal Player adapter - see the carrier
         ruling in 0007). Acceptance: walk to a crafting table,
         open it, place materials via MenuClick, read the result
         slot, take the product.
         Progress 2026-08-25 (baseline + crafting table landed):
         BotPlayerFacade + BridgeInventory + BindingMenu +
         MenuView/SlotView + BotCraftingMenu (ServerPlayer cast
         bypass in slotsChanged) + MenuOpener (crafting_table +
         chest). In-engine gate: craftsDiamondBlockAtTable (9
         diamonds → diamond_block → ResultSlot.onTake consumes
         grid). 24/24 gametests pass. Remaining: core click-sequence
         PLANNER, chest gametest, CraftingView api type.
         Progress 2026-08-26 (disclosure + transaction surface,
         36/36 gametests): MenuView carries carried + sourcePos,
         SlotView carries SlotRole (vanilla layout knowledge in one
         adapter table), api MenuClick replaces the engine ClickType
         in every public signature, armor bridge translation landed
         (amended 0007 ruling), double chests open the full
         CompoundContainer, and the menu write surface landed per
         the A1 ruling as api.menu.MenuTransactions on BindingActor
         (ledger 29) - craftsViaMenuTransactions proves the
         click-only chain end to end. Remaining: core click-sequence
         PLANNER (planning half is L1-testable against MenuView
         fakes; execution rides MenuTransactions), CraftingView api
         type, and the walk-to-table acceptance loop.
         Progress 2026-08-26 (planner + acceptance loop, 37/37
         gametests): api CraftingView projects the crafting grid +
         result through SlotRole (2x2 baseline, 3x3 extension, no
         flat arithmetic for callers), core MenuPlanner plans pure
         click sequences from one snapshot - whole-stack lifts,
         one-item-per-cell right-click deposits, remainder returns,
         quick-move take; mixed recipes compose by chaining
         single-material plans. craftsViaMenuTransactions migrated
         to planner-driven (its hardcoded flat layout is gone) and
         walksToTableAndCrafts pins the acceptance criterion
         verbatim: goto mission to the adjacent cell, open, planned
         fill, take, close, exactly one product in the binding.
         Offline gates: CraftingViewTest + MenuPlannerTest.
         [Phase 2 fully landed 2026-08-26]
                                 [dep: P1]
- [x] M  Phase 3 - crafting automation: recipe query service over
         RecipeManager (adapter-side only - core stays
         MC-clean), quick-move sequences. Acceptance: given a
         recipe id, bot pulls materials, crafts, banks the
         product.
         Progress 2026-08-26 (landed same day as Phase 2; 39/39
         gametests): api RecipeView describes shaped recipes in
         PATTERN-relative coordinates with tag-expanded accepted-id
         lists (shapeless not representable - catalog emits empty);
         adapter RecipeCatalog translates byId/byResult off the live
         server datapack (catalogsShapedRecipes pins stick vs
         diamond_block vs shapeless-vs-unknown); core MenuPlanner
         grew planRecipe (pattern cells resolve first-fit against
         snapshot supply, group per chosen kind, one planGridFill per
         group, top-left anchoring on whatever surface is open) and
         the chest half planWithdraw/planDeposit (QUICK_MOVE whole
         stacks; withdrawal may overshoot demand, deposit refuses
         when nothing matches). pullsCraftsAndBanksByRecipeId runs
         the acceptance verbatim across three goto legs: chest ->
         table -> chest, final state pinned from both sides. The
         first engine run exposed a REAL pathing defect - an
         exhausted cursor returned before steering, so a brake
         undershoot idled one cell short until the mission budget
         died; fixed at root in PathingBehavior (wind-down steering,
         pinned offline by DepartureBrakeGateTest) instead of
         reworked test geometry.
         [Phase 3 fully landed 2026-08-26]
- [ ] XL Phase 4 - full player behaviour parity: remaining menu
         kinds (enchanting, anvil, loom, villager, ...), Intent.
         UseItem (eat / drink / bow / place), armor slots,
         offhand, experience and hunger sense. Acceptance:
         unattended survival loop (mine -> craft -> equip ->
         fight -> eat).                                         [dep: P3]


### Lean-round deferrals (2026-08-26 whole-repo over-engineering audit)

Rulings from the delegated lean round (user: "you make the rulings,
lean direction"). Executed same day: goto-migration scaffolding
removal, six-way RecordingActor / eight-way floorTo / six-way
repoRoot helper consolidation + SanityCheck deletion, one numeric
parser in GotoCommandHandler, consumer-count drain-lock retirement
(boundaries.md ledger 30), resetAt doc-truth fix. The items below
are the deliberate NON-executions, each with its trigger:

- [ ] S  Deterministic clock seam for wall-clock gates:
         AStarWallClockGateTest arms a real 1 ms budget (fast machines
         can finish the search inside it and flip the assertion);
         AdoptFreshnessGateTest and PlanWorkerGateTest sleep-poll the
         async worker. Inject a deadline/clock into AStarPathFinder +
         PlanWorker instead. No fake clock exists anywhere yet.
                                                        [dep: none]
- [ ] S  RecipeCatalog.list(offset, limit) / RecipePage (uncommitted
         backend of the recipes-list wire verb) ships without any
         offline test - add one for clamping, ordering, and the
         shapeless-skip rule before or with that commit.
                                                        [dep: none]
- [ ] S  Menu-family disposition (BridgeInventory 333L vs
         BindingInventory 148L overlap; BotInventoryMenu thin fork of
         BotCraftingMenu): rides the issue 0010 BotPlayerFacade /
         FoodData lifecycle ruling - do not churn vanilla-interaction
         adapter code twice.
                                            [dep: issue 0010 ruling]
- [ ] S  CombatOrder permits exactly Attack (single-subtype sealed
         hierarchy): flatten onto Overrides' payload at the Stage 3
         review - boundary-B Directive shape is frozen until then.
                                        [dep: stage 3 review opens]
- Verified non-findings kept on purpose: Heuristic interface
  (decision 7 injected seam; planned climbs/drops will supply second
  suppliers - same reserved-seam class as ViewMode.SNAPSHOT),
  PresenceLayer/IdleLook/BodyPositionSource (BodyPositionSource feeds
  PathingBehavior + CombatBehavior aiming - not cosmetic),
  /bot goto brigadier path (it IS the transport mc.py translates
  onto), parked issue 0003 stays in issues/ root (parked is a legal
  root status; archive policy binds resolved only).
