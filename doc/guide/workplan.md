---
title: Work Plan (effort-sized checklist)
last_verified: 2026-08-23
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
         SHIPPED 2026-08-23 (offline half; build/test green -
         CollisionShapeGateTest 8, BlockTraitsRegistryGateTest
         10, MockWorldViewShapeGateTest 9, BasicMovesShapeGateTest
         6, of which 5 cases stay @Disabled under issue 0002): new
         api.world.CollisionShape now carries a cell-local Box
         (0..1) and the derived predicates passable() and
         walkableTop(). passable = EMPTY or PARTIAL with top below
         STEP_HEIGHT (0.625); walkableTop = FULL_CUBE or PARTIAL
         with top >= STEP_HEIGHT and below a jump. BasicMoves
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
         gauntlet gametest (a corridor with bottom-slab / upper /
         stairs / fence / ladder / water / lava sections, end to
         end) is the remaining open piece, carried as a Stage 2
         closeout follow-up; its five shape-semantics cases wait
         on issue 0002 (fence + slab default traits), the rest of
         the offline suite runs green.

### Stage 2 closeout follow-ups

1. Issue 0002 shape-contract decision: the five disabled
   world/collision cases exposed three conflicts between the
   tests and the current CollisionShape contract - walkableTop's
   code threshold (0.625) contradicts its own Javadoc ("a
   half-slab top at y=0.5 is the threshold"); the fence cases
   need a Box with maxY=1.5, which Box validation ([0,1]) and
   the enabled boxRejectsOutOfRangeBounds case both reject; and
   passable()'s pure-Y rule cannot return true for a fence under
   any Y representation. Fixing this amends boundary vocabulary
   (decision 19 discipline), so it waits for stage review per
   the AGENTS.md stop rule.
2. Gauntlet gametest (shape-bearing corridor end to end) -
   blocked on follow-up 1: its fence/slab sections assert the
   exact semantics issue 0002 must settle first.

