---
title: Decision Ledger (append-only)
last_verified: 2026-08-27
covers:
  - doc/architecture/boundaries.md
  - doc/architecture/function-map.md
  - src/main/resources/data/mcbotserver/reflex_rules.json
related:
  - doc/architecture/harness-interaction.md
  - doc/guide/workplan.md
---

# Decision Ledger

This is the append-only decision ledger of the mc-bot-server
bot device: one numbered verdict per architectural ruling, earliest
first. Law-vs-canon layering: the frozen contracts live in
doc/architecture/boundaries.md; issue bodies hold problem / ruling /
contract / deferred; progress narratives belong to commits, never to
entries.

Admission protocol:

- Absorbing an issue's design means ARCHIVING that issue in the
  same commit that lands its entry here (0012 waited months open
  because this was not mechanical).
- Amending an earlier verdict means a NEW entry naming it, plus a
  symmetric amendment annotation on both sides; prose never edits an
  already-published entry except to append that annotation.
- Ordering-critical numbers (priorities, thresholds) are tuning data
  owned by datapack JSON and pinned by gates such as
  `ReflexPriorityOrderGateTest` - never restated as frozen prose
  inside an entry.

## Index

| # | Entry | Adopted |
|---|---|---|
| 1 | LLM stays out of the bot; harness is an external brain |  |
| 2 | Host = server-side fake player; engine owns physics |  |
| 3 | Capability stack: WorldView / Actor / skills / orchestration |  |
| 4 | Data-driven trio: Registry / Codec / ReloadListener |  |
| 5 | core package has zero MC imports |  |
| 6 | Process/Behavior duality; central winner-take-all arbiter |  |
| 7 | Goal is a pure data abstraction |  |
| 8 | Movement modeled as a five-tuple |  |
| 9 | Reflex layer is an independent bypass |  |
| 10 | Scenario combinations are rule-table data |  |
| 11 | DefendProcess light planner; structural refusals at engage time |  |
| 12 | InterruptionContext snapshot; resume revalidates world first |  |
| 13 | Behavior.tick returns ExecutionReport |  |
| 14 | Actor four independent channels, per-tick claims |  |
| 15 | Single tick entry; fixed reflex -> arbiter -> behaviors -> flush order |  |
| 16 | Minimal reflex rule exercises the full preempt chain early |  |
| 17 | WorldView exposes BlockSnapshot / EntitySnapshot / CollisionShape |  |
| &nbsp;&nbsp;17a | isLoaded(pos) distinguishes unloaded from air |  |
| &nbsp;&nbsp;17b | ViewMode SNAPSHOT/LIVE reserves the off-thread seam |  |
| 18 | First boundary-D semantics: GotoCommand, submit/cancel/status |  |
| 19 | Shape-vs-traits split for movement viability vs vocabulary |  |
| &nbsp;&nbsp;19a | New Movements derive preconditions from shape plus traits |  |
| &nbsp;&nbsp;19b | Settled STEP_UP_REACH / STANDABLE_THRESHOLD geometry semantics |  |
| 20 | Plan-progress fuse and vertical trigger gate | 2026-08-24 |
| 21 | Unreachable-goal escalation: honest NO_PATH | - |
| 22 | ReflexAction vocabulary (boundary C growth) | 2026-08-24 |
| 23 | Reflex-carried idle combat (ENGAGE) | 2026-08-24 |
| 24 | Vitals pass and the extreme-scenario reflexes | 2026-08-25 |
| 25 | Block capability axis opened - dig first | 2026-08-25 |
| 26 | Reflex ESCAPE action and rescue mission handoff | 2026-08-25 |
| 27 | Powder-snow climb reflex | 2026-08-25 |
| 28 | Harness surface convergence (console + disclosure tables) | 2026-08-25 |
| 29 | Menu transactions are imperative request-response | 2026-08-26 |
| 30 | Consumer-count drain-lock retired from the event stream | 2026-08-26 |
| 31 | Slot identity dual-space accepted; dual-RCON re-eval trigger | 2026-08-26 |
| 32 | State-snapshot field set pinned at BotState's categorical rule | - |
| 33 | Harness interaction model codified: space is a namespace, time is job control | 2026-08-27 |
| 34 | Carrier food lifecycle: Path A FoodData on the body, free-regen floor retired, EAT reflex consumption | 2026-08-27 |
| 35 | Third reflex seat promotion: ReflexMissionSeat unifies ENGAGE/ESCAPE; FORAGE action + ACQUIRE rule | 2026-08-27 |
| 36 | Shapeless recipes cataloged via ingredient-index encoding; scarce-first pattern resolution | 2026-08-27 |
| 37 | Bow ranging over USE: hold-draw semantics amend decision 14; melee ranking suppressed while ranging | 2026-08-27 |

## Amendment chains

Amendment chains recorded so far (both sides annotated):

- 14 <- 25: INTERACT joins as the fifth channel.
  <- 37: USE hold is first-class (sustained press charges a draw,
  falling edge releases; orphaned charge force-released).
- 19a <- 19b: shared-constant era settled into two predicates.
- 24 <- 25 (rule rename DIG_ON_SUFFOCATION, ladder unchanged),
  <- 26 (ESCAPE_ON_LAVA replaces ASCEND_IN_LETHAL_FLUID; EXTINGUISH_FIRE
  rung), <- 27 (powder-snow rung); full order pinned by
  `ReflexPriorityOrderGateTest`, values live only in
  `reflex_rules.json`.
- 28 <- 33: wire-vocabulary tables canonized in
  doc/architecture/harness-interaction.md.

## Entries (verbatim)

1. LLM stays out of the bot; harness is an external brain.
2. Host = server-side fake player; physics owned by the engine.
3. Capability stack: WorldView / Actor / composite skills / orchestration.
4. Data-driven trio: Registry + Codec + ReloadListener; JSON says what,
   code says how.
5. core package has zero MC imports; offline unit-testable.
6. Process/Behavior duality with central arbiter: winner-take-all, DEFER,
   fresh intent enters queue head.
7. Goal is a pure data abstraction; search algorithms decoupled from goal
   types.
8. Movement modeled as five-tuple (src, dst, precondition, cost,
   execution state machine).
9. Reflex layer is an independent bypass: sensor -> blackboard -> rule
   table; it never competes in process arbitration.
10. Scenario combinations become rule-table data; mechanisms written once;
    no per-mob processes.
11. DefendProcess is a light planner; CombatBehavior owns micro-execution;
    reflex rules use dynamic priority functions. "Light planner" includes
    the decision NOT to fight: hostiles whose tactics structurally defeat
    melee (rangedTypes data) are refused at engage time
    (`TASK_FAILED(attrs.reason=ENGAGEMENT_REFUSED, attrs.threatType)`),
    never chased to timeout.
12. InterruptionContext: preemption carries a snapshot; resume validates
    world assumptions first.
13. Behavior.tick() returns ExecutionReport (RUNNING / SUCCESS / FAILED /
    STUCK); stuck detection lives inside behaviors; processes consume
    reports and stay pure.
14. Actor = four independent channels (MOVE / ROT / USE / SLOT); per-tick
    claims with priority resolution; claims expire each tick.
    Amendment (ledger 37, 2026-08-27): a USE hold is now first-class -
    sustained Use(true) claims charge a draw and the falling edge
    releases it (BowItem.releaseUsing reads the accumulated duration);
    the adapter force-releases an orphaned charge when the USE claim
    vanishes mid-draw. Rising-edge melee semantics unchanged.
15. Single tick entry: BotController on server tick END phase; fixed
    order reflex -> arbiter -> behaviors -> actor flush.
16. Phase 0 includes one minimal reflex rule so the full preempt ->
    resume chain runs against mocks before any real entity exists.
17. WorldView exposes three data primitives: BlockSnapshot, EntitySnapshot, CollisionShape.
    17a. WorldView must expose `isLoaded(pos): boolean`, distinguishing "unloaded" from "air". A naive `getBlockState(unloadedPos) == AIR` lies to the planner; the loaded/unknown distinction must be a first-class query.
    17b. WorldView queries support two view modes: `SNAPSHOT` (for the planner, a frozen view of a given tick) and `LIVE` (for the executor, a real-time view of the current tick). Under a single-threaded implementation the two are equivalent, but the interface must reserve a `ViewMode` parameter so future off-thread A* does not require an interface change. SNAPSHOT creation, invalidation, and lifecycle are entirely the caller's (the planner's) responsibility. WorldView promises only "return data per the requested `ViewMode` semantics"; snapshot storage, staleness, and disposal are NOT part of the WorldView contract -- if needed they go in workplan follow-ups, not in the interface.
18. First boundary-D semantics: GotoCommand{target, tolerance,
    timeoutTicks}; seam verbs stay submit/cancel/status.
19. Shape-vs-traits split: geometry is derived from
    `CollisionShape`, non-geometric properties (climbable, liquid,
    damaging) come from `BlockTraitsRegistry`. Movement viability
    (can the body pass, can it stand) reads the shape; movement
    vocabulary expansion (ladder, swim, pillar) reads the traits.
    The split exists because "is the top half of this cell solid"
    is a property the shape answers for free (the box) while "is
    this a ladder" is a property the shape cannot infer (the
    collision box is empty above the rung). Putting geometry in
    the traits registry would force a per-id entry per block;
    putting traits in the shape would force a box scheme that
    pretends non-geometric behaviour. The trait registry is
    state-aware: `minecraft:stone_slab[type=bottom]` and
    `[type=top]` are different keys, because the two halves have
    different damage / climb answers. Type-only keys collapse
    them and the first half-slab the bot walks across teaches
    the planner the wrong lesson. Registry default for an
    unknown id is `BlockTraits.defaults()` (the safe "no
    special property" record); a missing entry is the registry's
    problem to surface, not the planner's.
    19a. Every new Movement must answer "can its full physical
    precondition be derived from `CollisionShape` plus the small
    traits registry" before it lands. Diagonal corner clearance
    (4-cell sweep, 1x2 vertical) is the first case the rule
    applied to; future Movement classes (Climb, Drop, Ladder,
    Swim, Pillar) are gated by the same answer.
    19b. Settled geometry semantics of the two predicates
    (issue 0002, adopted at the Stage 2 closeout review).
    `STEP_UP_REACH` (0.625) gates `passable()` - what a hop
    clears; `STANDABLE_THRESHOLD` (0.5) gates `walkableTop()` -
    the floor of MC's partial-top spectrum (slab 0.5, soul sand
    0.875, dirt path 0.9375 above it; farmland 0.25 and beds
    0.375 below it are not routing surfaces). The split exists
    because step-up reach and standability are different
    physical quantities that briefly shared one constant. A
    PARTIAL top is additionally walkable only if the box spans
    >= BODY_WIDTH (0.6) on both horizontal axes - the footprint
    rule that makes a fence post answer false: top high enough,
    footprint nothing to balance on. Fence cells are walls -
    passable false, walkableTop false; the way past a fence is
    the missing-post cell, which is EMPTY and already answers
    true. Pose-aware parameterization reopens via issue 0003
    without touching this entry.
    20. Plan-progress fuse and vertical trigger gate (issue 0001,
    adopted through its four-PR resolution chain). Stuck detection
    measures PLAN-PROGRESS, never per-tick motion: three OR
    criteria - waypoint index advanced, goal distance decreased,
    3D distance to the current waypoint beat the latched minimum
    by more than STUCK_EPSILON (a margin, not equality - in-place
    jitter must not mint new minima). External replan triggers
    (exhaustion, offPath, freshness drop) must not clear the
    accumulator; only real progress or the fuse firing resets it.
    The vertical gate complements the fuse: airborne ticks skip
    trigger evaluation (a falling pose would score false progress
    or request replans against snapshots the freshness check
    discards), and the landing edge bypasses the replan cooldown
    so the post-landing pose is the next plan's start cell. Fuse
    without gate leaves the airborne replan waste; gate without
    fuse leaves the free-fall limbo; both are required.
    HARD RE-TEST TRIGGER: the 3D-distance criterion and the
    motion-detector subsumption hold iff every waypoint gap is
    <= 1 cell AND steering is move-at-waypoint. Any change to
    BasicMoves.from (a long-range move), AStarPathFinder.reconstruct
    (waypoint simplification), or a path-smoothing pass (issue
    0005 P1.2 is the first queued one) must re-run the limbo and
    detour coverage (LimboCharacterizationGateTest plus the detour
    cases) and upgrade the criterion to arclength projection if
    waypoint gaps exceed one cell.
    Trigger fired 2026-08-24 by issue 0005 P1.2 (PlanSmoother,
    collinear-run collapse plus same-Y line-of-sight merge).
    Resolution: the arclength upgrade is NOT needed - merged
    segments are straight, so waypoint distance stays monotone
    along a segment - but two follow-throughs landed in the same
    change: off-path drift now measures XZ distance to the WALKED
    SEGMENT (previous-to-current waypoint; mid-segment distance to
    the endpoint is legitimate, not drift), and the criterion-3
    latched minimum resets on waypoint advance (a stale minimum
    would silence criterion 3 for a whole segment and false-STUCK
    a moving body on a detour leg). Limbo and detour coverage
    re-run green (offline suite plus runGameTest).
    21. Unreachable-goal escalation (NoPathEscalator, PathingBehavior
    package). A drained A* open set was always an immediate NO_PATH
    (empty waypoints -> empty cursor -> FAILED same tick); the gap
    was the budget-cut PARTIAL loop: each individual search
    "succeeds" locally (confidence >= MIN_PARTIAL_CONFIDENCE), the
    follower adopts, walks, exhausts, replans - and the mission
    burns its whole tick budget on a goal it can never reach
    (observed in the wild: a floating goal cell, 21s of planning
    churn, TIMEOUT at 600 ticks). Semantics frozen here: every
    adopted partial is a WITNESS; a witness whose terminal waypoint
    fails to improve the best-ever terminal-to-goal distance by
    more than 0.5 blocks (an axis step is 1.0) increments the
    ledger, an improving witness resets it, and WITNESS_LIMIT (3)
    consecutive non-improving witnesses declare FAILED(NO_PATH) -
    the mission process keeps its existing verdict mapping, nothing
    new crosses boundary B. Each witness also escalates the next
    search's node budget (x4 per witness, capped at 320k; wall
    clock unchanged), so the verdict only lands after the search
    had a progressively fair chance: a plateau at maximum budget is
    evidence of unreachability, not of a stingy search. Improvement
    is measured against the BEST-EVER distance, never the previous
    witness - a legitimate detour that temporarily moves the
    terminal away cannot mint false witnesses. The ledger resets on
    goal change and on any complete-route adoption; the count rides
    the keepalive event as `noPathWitnesses` for harness-side
    verdict anticipation.
    22. Reflex action vocabulary (ReflexAction, boundary C growth
    within ADR-0003 section 2's "the layer decides WHAT the frozen
    body does"). Rules name an action KIND; the controller owns the
    kind-to-Intent mapping - rules stay pure blackboard functions
    and Intent shapes stay owned by exactly one place. Kinds:
    FREEZE (MOVE(0,0), the default) and ASCEND (held jump: vanilla
    LivingEntity#aiStep routes a held jump in water deeper than the
    fluid-jump threshold to jumpInFluid, +0.04/tick upward impulse;
    the engine computes all motion, boundary A holds). The drowning
    rule SURFACE_ON_LOW_AIR (issue 0004 F6(2), after the 2026-08-24
    spawn-lake death) grounds its numbers in decompiled vanilla:
    ThreatBlackboard.airSupply drains 1/tick from 300 and regenerates
    4/tick; beginTick resets it to FULL so a rig whose sensor never
    stamps air reads as breathing (no air data must not mint a
    drowning reflex); the sensor (not the pipeline) stamps it -
    air is body state with no WorldView expression, same category
    as botHealth. Trigger 80 (~4s of air left; terminal swim-up is
    ~0.16 blocks/tick against 0.8 vertical drag), release 200 (the
    ascend holds through surface-bobbing regen instead of flapping
    at the film). Priority ordering is part of the contract:
    SURFACE (110) must outrank FREEZE (100) - freezing while
    drowning converts one lethal condition into two.
    23. Reflex-carried idle combat (ENGAGE_ON_HOSTILE_PROXIMITY,
    ReflexAction#ENGAGE; after the 2026-08-24 night-cave death).
    Resolves the workplan's standing ruling - standing idle-band
    DefendProcess vs reflex-carried combat directive - as a hybrid:
    the RULE only notices the threat (boundary-C shape, pure
    blackboard function, trigger 6 / release 14 / priority 90, below
    the survival holds); the CONTROLLER converts a winning ENGAGE
    decision into one preemption tick (shared park skeleton) plus a
    factory-built reflex-owned DefendProcess submitted to the
    arbiter; from the next tick the fight runs THROUGH the mission
    stage, because a fight is multi-tick state (target tracking,
    leash, grace) and a held-body reflex cannot carry it. The
    factory is wiring-owned (identity, timeout, type sets) and
    nullable - no factory degrades ENGAGE to the freeze hold. Two
    controller-side guards are part of the semantics: (a) the
    threat-gone resume path runs only when NO reflex fired this tick
    AND no mission is seated - otherwise the parked mission would
    steal the body back on the submission's very next tick (the
    one-tick window) or mid-fight; (b) a resubmit cooldown (40
    ticks, deliberately above the rule's hold window plus a
    release-band transit) absorbs both a threat fleeing through the
    release band and the hysteresis-held rule with no threat left -
    neither may mint a defend per tick, and the engagement tail
    must not mint a spurious instant-SUCCESS defend. Trigger 6 sits
    inside melee closing range and below ranged kiting standoff
    (~8-10): a skeleton at its preferred distance never trips the
    rule, because engaging it would only mint ENGAGEMENT_REFUSED
    churn (decision 11). Ranged idle threats remain an open gap
    (workplan survival gate).
    23a. Engage-chain slot discipline (review-hardened follow-up to
    23; three sibling holes shared one root - the controller created
    a two-level control chain (parked original + reflex fight) that
    TaskArbiter's single paused slot could not represent). Fixes,
    frozen here: (a) SINGLE-SLOT INVARIANT in the arbiter - parking
    over an occupied paused slot evicts the occupant through
    revalidate-and-requeue (its own resume(ctx); valid -> back to
    pending, invalid/dead -> onContextInvalidated), so no mission is
    ever orphaned; the controller calls the eviction explicitly
    before parking so a DROPPED revalidation reaches the harness as
    TASK_DROPPED (the arbiter never emits). (b) RESUME-GUARD
    DISPATCH on the paused occupant's identity: when the FIGHT is
    the parked one (a survival reflex parked it mid-fight), resume
    it whenever the seat is free - even while ENGAGE keeps firing,
    because threat-present is exactly when the fight must resume
    (gating on decision==null seats the requeued original over it
    and strands the fight in the paused slot); when the ORIGINAL is
    the parked one, resume only on a fully quiet tick with nothing
    seated and no fight awaiting its pending seat (the submission's
    one-tick window - there the arbiter must SELECT the fight).
    (c) The hysteresis activation edge is <= (boundary value fires),
    aligned with computePriority's documented "at or below" - a
    strict < shifted every hysteretic rule's effective trigger one
    tick late.
    24. Vitals pass and the extreme-scenario reflexes (issue 0008
    D1/D2/D3/D5; ruling batch 2026-08-25). D1: ThreatBlackboard
    gains fireTicks, freezeTicks, inWall - all entity-state reads in
    the airSupply category (sensor-stamped suppliers, beginTick
    reset to safe) - and the inLethalFluid field dead since ADR-0003
    finally has a writer. D2: ASCEND_IN_LETHAL_FLUID (priority 130,
    ASCEND - held jump routes to jumpInFluid(LAVA); lava is 4 HP/tick
    with no interval, the one vital that outranks everything). D3:
    FREEZE_ON_SUFFOCATION (priority 115, FREEZE - 1 HP/tick instant
    class where the rescue direction is UNKNOWN, so halting is the
    only strictly-correct reflex response; inWall on this codebase
    is almost always a pathing bug, the reflex doubles as the
    siren). D5: MinimalReflex.tick gains an airSupply parameter -
    a crashed bot with air < 80 holds jump; one more if in the same
    dependency class as the lava flag, ADR-0005 D3 intact. D4 ruled
    NO RULE: fire is self-answering (extinguished by water the
    ongoing route touches) and sense-only since D1 - that half aged
    well; fire itself gained rules later (decision 26). The v1
    triage order was five rungs, LAVA > SUFFOCATION > SURFACE >
    FREEZE > ENGAGE, recorded deliberately without numbers: values
    are tuning data owned by reflex_rules.json, and
    ReflexPriorityOrderGateTest pins the rung inventory plus the
    strict full ordering; decisions 26 and 27 appended their rungs
    without disturbing any v1 pair. RELOAD-PARITY RULE (found
    live in review): a reflex type registered by assembly code MUST
    land with its ReflexRuleJson parse/write branch AND its default
    datapack row in the same change - /reload replaces the whole
    table, so a code-only rule is silently dropped by the first
    reload; the RuleTableGateTest
    shippedDatapackTableCoversEveryCodeRegisteredRuleType gate pins
    the lockstep. Boolean-condition rules (lava, suffocation) carry
    only their priority in JSON - the inverted 0/1 signal's
    hysteresis thresholds are code constants, not tunable data.
    25. Block capability axis opened - dig first (issue 0009; user
    ruling 2026-08-25 "start enriching the bot's block capability",
    motivated by the gravel-suffocation escape). Decision 14's
    "four channels" grows to FIVE: INTERACT carries held block
    interaction (USE stays entity-melee; the same channel 0007
    section 6.2 hypothesized, pulled forward because bare-hand
    mining is inventory-free). Vocabulary: `Intent.Dig(CellPos)` - a
    claim this tick IS the button state; multi-tick destroy progress
    accumulates adapter-side in DigExecutor (mirrors
    ServerPlayerGameMode: per-tick = digSpeed / destroySpeed /
    (correctTool ? 30 : 100), x0.2 airborne, break at 1.0 via
    `level.destroyBlock(pos, true)`, crack stages through
    `destroyBlockProgress`; deviations: no stop-destroy 0.7 shortcut
    and no Forge LeftClickBlock/onBlockBreakEvent hooks - both
    Player/client-shaped, deferred with the 0007 facade). The
    arithmetic lives in core `DigPacing` (gravel 18 hand-ticks,
    stone 150, hardness-0 pops, unbreakable never). Reflex half:
    `ReflexAction.DIG` maps (controller) to MOVE hold + ROT aim
    (presentation only - the executor never reads facing) +
    INTERACT dig claims from the decision's `actionTarget`
    (`ReflexRule.actionTarget(board)`); a targetless DIG degrades to
    the freeze hold - missing data must not mint a dig-at-null.
    FREEZE_ON_SUFFOCATION is renamed DIG_ON_SUFFOCATION (115 /
    hold 10 unchanged, ladder unchanged): with dig capability the
    rescue direction is KNOWN (the eye block, sensor-stamped onto
    `ThreatBlackboard.suffocationBlock`), superseding the stopgap
    whose whole argument was the unknown direction. The old type
    string is a hard parse error (loud datapack update, not silent
    drop); the reload-parity gate pins the lockstep. Crashed-state
    suffocation stays freeze-class - MinimalReflex keeps "a few ifs"
    (issue 0009 F1).
    26. Reflex ESCAPE action and rescue mission handoff (issue 0008
    D2-upgraded + D5-revised; user ruling 2026-08-25 "must have
    shortest-shore escape" and "fire find-water by burn-to-death
    threshold"). `ReflexAction.ESCAPE` is the mission-handoff twin of
    ENGAGE: the controller spends one preemption tick (FREEZE hold),
    calls `rescueMissionFactory.get()` to mint a GotoProcess to a
    safe cell, registers + requests control, and from the next tick
    the rescue runs through the normal mission stage. Bookkeeping
    mirrors ReflexEngageSeat in ReflexRescueSeat (maySubmit /
    submitted / retireFinished / rescueIsParked /
    rescueAwaitingSeat, 80-tick resubmit cooldown — longer than
    ENGAGE's 40 because a swim/walk route takes longer to resolve
    than a fight). The resume guard dispatches on both fight and
    rescue seat states (a reflex-owned rescue parked by another
    reflex resumes whenever its seat is free, same as ENGAGE).
    RescueMissionFactory (adapter) reads body state at call time:
    inLava -> nearest non-liquid shore cell (passable + walkable top
    below, 12-block radius scan); remainingFireTicks > 0 -> nearest
    water-adjacent cell; neither -> null (degrades ESCAPE to FREEZE).
    Two rules: ESCAPE_ON_LAVA (130, replaces ASCEND_IN_LETHAL_FLUID
    — the old type string is a hard parse error; pure ASCEND floated
    the body to the surface but never reached shore, so the bot
    still burned) and EXTINGUISH_FIRE (105, between SURFACE 110 and
    FREEZE 100; triggers only when fireTicks/20 >= health — the
    lethal band; non-lethal fire stays sense-only per the original
    D5 intent). Both carry reload-parity JSON forms + datapack rows.
    In-engine: `escapesLavaToShore` (5x5 pool, fire-resist, body
    reaches dry ground) and `findsWaterWhenBurning` (health=5,
    fireTicks=100, water 4 blocks east, fire extinguished by
    contact).
    27. Powder-snow climb reflex (issue 0008 F6; user ruling
    2026-08-25 "this how to solve"). `ClimbOutOfPowderSnowRule`
    (CLIMB_OUT_OF_POWDER_SNOW, priority 95, trigger freezeTicks=100)
    emits ASCEND (held jump). Powder snow is climbable
    (Entity.isStateClimbable returns true, decompiled Entity.java:764
    — same class as ladders/vines), so the held-jump impulse walks
    the body up and out of the snow; no mission handoff needed.
    Trigger at 100 leaves 40 ticks (2s) before the fully-frozen
    damage phase (1 HP/40t at freezeTicks>=140). Priority 95 slots
    below FREEZE(100) and above ENGAGE(90): at 3 HP the bot freezes
    rather than climbs (the climb takes ticks; freeze damage is
    slow enough that FREEZE+harness is safer). Design constraint:
    `ReflexHysteresis` is direction-locked to "lower signal = more
    dangerous" (the air-supply shape in SurvivalReflexLayer.decideHysteresis);
    freezeTicks is "higher = more dangerous", so the rule uses pure
    `computePriority` (same shape as EscapeLavaRule/ExtinguishFireRule).
    Thaw is -2/tick once clear, so the freeze budget recovers fast.
    Reload-parity: JSON branch + datapack row + lockstep gate
    (7 rule types). In-engine: `climbsOutOfPowderSnow` (3x3 two-deep
    pit, body climbs out, no freeze damage taken).
    28. Harness surface convergence (issue 0011; user ruling
    2026-08-25 "the harness-interaction side is getting messy"). The
    CONSOLE verb layer is frozen contract surface, not convenience:
    six verbs (status / goto / cancel / stop / events / reset), one
    JSON object per answer, machine-readable `reason` on failure -
    the table lives in the issue and grows by one row per new
    process kind, decided in the shipping issue, never as an ad-hoc
    brigadier branch. Known wart, documented not fixed: `/bot events
    since` is a console Integer while the cursor contract is long
    (repair rides the first non-RCON transport). RESET is the
    ADR-0005 5a promise finally wired - clears latch + counter,
    echoes `crashed` so a harness resetting a healthy bot learns it
    did nothing. EVENTS narrowing (`[only] <kindPrefix>`, e.g.
    TASK_) lives on EventBatch.narrowedToKindPrefix so every
    transport shares it; two non-negotiables: the cursor fields
    (latestEventId, resetAt, droppedCount) stay the TRUE stream
    values - the harness's next poll advances by what existed, not
    what was shown - and the cursor-integrity kinds (EVENT_GAP,
    EVENT_DROPPED) survive every filter, because hiding a gap is a
    silent lie. The STATE_PUSH-vs-status-vs-KEEPALIVE responsibility
    table (issue 0011 section 3) binds disclosure growth: state
    channels answer "what is the model now", keepalives answer "is
    the plan progressing", and no fourth overlap may appear. Session
    runtime (a tool/harness subpackage: rcon transport + cursor/reconnect state
    + auto-journal) is QUEUED with its first customer - the survival
    rehearsal driver - not built speculatively; MCP/HTTP stays
    deferred until a non-RCON consumer exists (the boundary-D
    reopen trigger).
    29. Menu transactions are imperative request-response on the
    boundary-A actor binding (issue 0007 section 6.2, hypothesis A;
    user ruling 2026-08-26 "A1 now"). The api interface is
    `api.menu.MenuTransactions` (openMenu / openInventoryMenu /
    menuSnapshot / menuClick / closeMenu) implemented by BindingActor
    next to Actor - NOT per-tick claims (claims expire and arbitrate,
    both wrong for a transaction), NOT a second controller (that
    alternative needs review sanction and was rejected). Actor itself
    stays claim-only: claim-only actors (ChannelArbiter, test
    recordings) are legitimate and carry no dead menu methods.
    Clicks re-run the menu's own stillValid predicate per click
    against the synced facade position; distance policy: open gate
    4.5 blocks (project interaction reach), keep-open gate vanilla
    8.0 (BLOCK_REACH + 3.5). The click vocabulary is api.menu
    .MenuClick (PICKUP / QUICK_MOVE / THROW; CLONE and QUICK_CRAFT
    deliberately excluded), and MenuView carries carried + sourcePos
    + per-slot roles so no harness or planner re-derives vanilla
    flat layouts.
    30. Consumer-count drain-lock retired from the event stream
    (whole-repo over-engineering audit; user delegation ruling
    2026-08-26 "you make the rulings, lean direction"). The api
    EventQueue carried reserved-but-never-used lock / unlock /
    isLocked over an opaque-token holder set - a multi-consumer
    gate that contradicted invariant 6 (the bot knows nothing
    about consumer count) and whose only caller across the tree
    was its own self-test. Deleted from interface and reference
    implementation together with that test; drain stays un-gated,
    exactly as every protocol surface already described it. If a
    real second consumer class ever appears, outflow policy
    belongs to the harness side or to a new ledger entry - not to
    bot-side bookkeeping.
    31. Slot identity dual-space accepted; dual RCON protocol gains
    an explicit re-evaluation trigger (over-engineering audit round
    2; user delegation 2026-08-26). (a) InventoryView (binding-flat
    36 main / 4 armor / offhand) and MenuView (menu-flat indices +
    SlotRole annotations) remain TWO index spaces on purpose: polled
    state and open-menu transactions have different lifetimes, and
    unifying them would spend wire payload where a join key
    suffices. The defect class this closes is SILENT INDEX
    ARITHMETIC - the armor-order reversal grew from exactly that -
    so SlotRole is the only legal join between the spaces and every
    translation must live in a named, commented bridge (BridgeInventory
    armor mapping is the exemplar site). (b) Task verbs riding
    CommandBus + event stream while menu verbs answer synchronous
    JSON on the RCON socket stays the 0012 D1 shape, but
    re-evaluation fires when ANY of: menu-family verbs exceed ~12
    or grow another payload mechanism beyond pagination; a second
    non-RCON consumer materializes (the standing boundary-D reopen
    trigger); or the harness needs a unified error model across
    both surfaces.
    32. State-snapshot field set pinned at BotState's categorical
    rule (issue 0013 R5). `healthHearts` (whole hearts 0..10,
    round(HP/2) - the raw 20-HP float would push on every
    regeneration tick, the bucket does not) and `freeSlots`
    (unoccupied main slots of 36) joined pos / yaw / pitch /
    dimension / itemCounts / selectedHotbarSlot /
    effectAmplifiers / currentTaskSummary. hunger stays OUT:
    FoodData is Player-only, BotBodyEntity carries no food state,
    and issue 0010 owns that lifecycle. Fields enter only through
    the H-R4 friction protocol - WireVocabularyGateTest component
    pin, DisclosureGateTest field set, this state section, and
    BotStateJson change together or not at all.
    33. Harness interaction model codified: space is a namespace,
    time is job control (emerged through issues 0011-0014; user
    ruling 2026-08-27 promotes the interaction norms to core
    design considerations). Canonical home:
    doc/architecture/harness-interaction.md owns the principles,
    grammar tables, verb + path rows, anti-patterns, and endgame;
    this entry binds only what the doc states and supersedes
    ledger 28's "the table lives in the issue" clause - new verb
    and path rows still ship with their process issue, but the doc
    is where the table lives. Clause names (a)-(e): path-addressed
    NOUNS under the fixed six-verb shell; JOBS whose terminal event
    is the machine-decidable verdict; loops divided at engine speed;
    heavy reads materialize to disk once; vocabulary fidelity -
    verbatim quoted ids, cursor truth never filtered client-side.
    Living-water: every new task verb, every new path
    root, and every new consumer kind re-opens the model audit in
    the shipping issue; the model itself re-opens when a flow
    cannot be expressed without breaking (a)-(e).
    34. Carrier food lifecycle ruled (user GO 2026-08-27, Stage 3
    Phase 4 eat slice): Path A confirmed - BotBodyEntity owns and
    ticks a vanilla FoodData; tick(Player) is cloned carrier-side
    verbatim (exhaustion drain, saturated fast regen, foodLevel>=18
    slow regen, starvation) with the Player parameter's four uses
    (level difficulty/regen rule, isHurt, heal, hurt-starve) all
    LivingEntity-available. The peaceful-style free-regen floor is
    RETIRED by this - regeneration and starvation are food-driven,
    exactly the one-line delete its javadoc promised. Eating is the
    Player.eat split on a mob: finishUsingItem (sound, probability
    effects, shrink, EAT game event) plus FoodData.eat
    (nutrition/saturation), gated on needsFood. Consumption is a
    reflex, not a process (0010 section 2 blesses this): rule
    EAT_WHEN_HUNGRY at priority 85, below ENGAGE (D6 combat-first),
    trigger 16 (D1), availability-gated on the sensor's best-food
    slot; execution is the ReflexAction.EAT select-and-use pair,
    one item per rising USE edge. `foodLevel` joins the state
    snapshot through the H-R4 friction protocol (wire key `food`);
    ThreatBlackboard carries foodLevel/saturationLevel/foodSlot
    safe-defaulted 20/5.0/-1. Hungry-with-no-food never fires a
    reflex - acquisition (forage/hunt/fish, HungryProcess) stays
    open in 0010 as the escalation side.
    35. Third reflex seat landed with the reserved promotion
    (2026-08-27): the arch-drift no-generalization ruling held seat
    dispatch un-promoted until the third seat existed; landing the
    forage trigger was that event. ReflexMissionSeat now unifies the
    ENGAGE/ESCAPE mirror classes (they differed only in cooldown
    constant and naming) - the controller's resume dispatch is
    seat-generic, a fourth seat is one construction away, and
    EngageReflexGateTest stayed green through the refactor.
    ReflexAction.FORAGE + rule ACQUIRE_FOOD_WHEN_HUNGRY (priority
    80, one rung below EAT) is the eat rule's EXACT complement:
    hungry-with-food eats, hungry-without-food forages, the pair is
    mutually exclusive by construction (pinned). HungryProcess rides
    the seat (HungryProcess v1, FOOD_STRATEGY_EXHAUSTED escalation
    per 0010 section 5); ReflexRuleJsonReader carries a
    GodClass-suppression ruling - it is a flat type->factory
    registry whose cohesion metric reads 0% by construction.
    36. Shapeless recipes join the catalog through the
    ingredient-index encoding (2026-08-27; external capability
    review flagged planks as a progression blocker, code truth
    confirmed). Vanilla's ShapelessRecipe.matches is
    arrangement-agnostic (non-empty cell count equals ingredient
    count, then multiset match), so a shapeless RecipeView carries
    NO geometry: positions are compact ingredient indices 0..n-1,
    patternWidth is a placeholder (3) with no geometric meaning,
    the inventory-2x2 fit is the vanilla count rule (n<=4,
    canCraftInDimensions(2,2)), and resolvePattern maps index i
    onto flat grid cell i on whatever surface is open. The catalog
    drops empty-option ingredients and compacts; more than nine
    usable ingredients stays unrepresentable (position space caps
    at 8, vanilla's own serializer cap is 9). Pattern resolution
    order is now SCARCE-FIRST - positions sort by ascending
    accepted-kind width, stable within equal widths - so nested
    acceptance sets ({oak} inside {oak,birch}) resolve the way
    vanilla's multiset matcher would instead of the position-order
    greedy that spent the last oak on the wide cell and refused
    the narrow one; shaped recipes keep their previous outcomes
    (all existing placements sort stable at equal width). The
    wood age is craftable end-to-end: log -> planks -> sticks ->
    table (all offline-pinned; the engine pair - planks craft +
    paginate-with-shapeless - rides the staged gametest batch).
    37. Bow ranging joins combat over the existing USE vocabulary
    (2026-08-27; external capability review queued the bow, the user
    sequenced it past the 0007 6.1 review gate). Decision 14's
    amendment above carries the semantic half; the ranking half:
    CombatBehavior suppresses holdBestWeapon while ranging because
    bows carry no attack-damage modifier - the melee ranking would
    switch off the bow every tick - and the bow slot wins SLOT until
    the target closes inside ATTACK_REACH, arrows run out, or the
    directive drops (each force-releases an in-flight draw). The
    draw charges 20 ticks (vanilla crit threshold) and the aim
    point rises by ARROW_DROP_PER_BLOCK_SQUARED * range squared, a
    constant-velocity parabola fit honest to within a block across
    the bow band. The engine pair (draw-release onto a live target)
    rides the staged gametest batch.
