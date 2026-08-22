---
title: Work Plan (effort-sized checklist)
last_verified: 2026-08-22
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
- [ ] L  DefendProcess planner + CombatDirective + CombatBehavior
         micro-execution skeleton
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
         SHIPPED 2026-08-22: new api.reflex.ReflexHysteresis
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

