---
title: Decision Ledger (append-only)
last_verified: 2026-09-01
covers:
  - doc/architecture/boundaries.md
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
- Engine-debugging narrative (session.lock wedges, pooled-run
  cross-contamination, workarounds for tooling quirks) belongs in
  `doc/reference/toolchain.md` or the tool README, never in a verdict
  entry. An entry records the ruling and its contract; the war story
  of how it was verified is commit history and tooling docs.

## Amendment chains

Amendment chains recorded so far (both sides annotated):

- 14 <- 25: INTERACT joins as the fifth channel.
- 37 <- 51: range routing stateful + weapon-aware (committed draws,
  point-blank bow when unarmed of better melee, bow-only standoff
  opening at the process tier).
- 39 <- 40: attack kill-confirmation (health-zero sighting =
  SUCCESS; absence-past-grace ESCAPED unchanged).
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
    rides the staged gametest batch. Amendment (ledger 51,
    2026-09-01): range routing is stateful and weapon-aware - a
    committed draw finishes through band-edge flips, the bow answers
    point-blank under the weapon-aware configuration, and bow-only
    carriers open melee-target fights at the standoff rim.
    38. Grammar fold executed: every write addresses its noun
    (user release 2026-08-28 of the 0015 section-3 trigger, then
    extended by user ruling in the same session - utilities must
    stay orthogonal to nouns, read means documents). The /actions/
    drawer is RETIRED as a path root and /tasks/dig with it:
    `write /blocks/<x,y,z> <blockid>[@face]` places (sync receipt,
    the value is a post-state contract - the receipt's block must
    match the asked id, because the server places the HELD
    BlockItem), `write /blocks/<x,y,z> air` digs (job receipt),
    `/blocks/<pos>/use <face>` runs the block's own interaction,
    `/blocks/<pos>/sleep` is the device-completed bed rest (the
    all-sleepers rule the engine cannot see is WHY it is not /use),
    sneak/hotbar/held-use move under /player. `read` is re-homed
    to DOCUMENTS (book pages, item information - /items, its own
    future capability slice; typed error until then); station
    snapshots ride `cat` as complete open-read-close transactions,
    killing the 0012 D4 lazy-session wart class. `ls` is universal:
    every root answers with an enumeration or a typed explanation
    (ls /player self-describes the fields; ls /blocks explains
    addressing; ls /recipes lists the materialized cache) - pinned
    by the LsUniversalityTest conformance gate. The ruled migration
    window collapsed to zero: rg-verified the only consumers were
    the CLI and its own tests. Pure client-side translation - zero
    wire change; canonical doc sections 2/10 and issue 0013's wire
    table updated in the same change, 0015 section 3 closed.
    39. The two harness-supervision conveniences landed as one
    slice (user GO 2026-08-28 on the honest-gap list). (a) DIRECTED
    ENGAGEMENT: `/bot attack <targetId> [timeoutTicks]` submits an
    AttackProcess - the DefendProcess sibling with targeting
    inverted: the harness HANDS the entity id (from `ls /entities`,
    whose lines now carry the id= column), so there is no
    hostile-type filter and no engagement refusal; a cow is as
    attackable as a zombie. A never-seen id fails fast as
    NO_SUCH_ENTITY; absence past grace fails TARGET_ESCAPED - the
    same conservative death-vs-flight verdict as defend, because a
    false SUCCESS costs a missed kill and a re-scan is cheap.
    Amendment (ledger 40, same day): death is directly judged - a
    scan sighting of the target at zero health completes as SUCCESS; CLI:
    `write /entities/<id>/attack`. (b) BATCH CRAFT CHAIN: `menu
    craft` accepts comma-separated recipe ids executed IN THE
    GIVEN ORDER inside the one open menu session - the harness
    still plans the chain (the list is verbatim input, the bot
    never decides what to craft), the bot only batches the
    execution; first failure stops the batch with partial results
    kept in the reply (`crafted[]` + `failedAfter` - the single-id
    reply shape changed from result/count to the same array, a
    sanctioned break recorded here). The chain rides the existing
    `recipe` role path - the value carries the list, no plural
    role minted.

    40. Attack kill-confirmation amends 39 (2026-08-28 code review):
    the death-unseen fallback stays conservative, but death itself is
    directly machine-judged - EntitySnapshot carries health and dead
    entities are not filtered from the scan, so a sighting of the
    target at zero health completes the attack mission as SUCCESS.
    Waiting for absence misreported every clean kill as
    TARGET_ESCAPED (exit 1), forcing a harness re-scan the snapshot
    had already answered. The absent-past-grace ESCAPED verdict, the
    fast NO_SUCH_ENTITY, leash and timeout are unchanged.
    41. 2026-08-28 architecture wave (audit-driven paydown): the two
    documented package cycles are dissolved and gated
    (NO_SUBPACKAGE_CYCLES: ExecutionReport now lives in api.process
    with its consumer BotProcess; the adapter entity cycle ended by
    moving the container constants into adapter.entity). Tick order
    owns ONE home: BotAssembly.tickOnce, driven by both the server
    listener and the gametest rig - the rig's subset-tick copy that
    could read green where production would not is gone. Menu kind
    knowledge (engine class, wire kind aliases, slot roles) lives in
    ONE table: adapter MenuSlotLayouts.KindRow; BindingMenu and
    MenuVerbs resolve through it. Sanctioned exemptions: MenuVerbs /
    WorldCommands / HungryProcess / MenuFixtures /
    MenuPlannerCountedRoleTest / BotBodyEntity carry inline
    GodClass or TooManyMethods rulings (flat verb tables and the
    vanilla entity shape); splitting remains the move if any accretes
    a second shape. -Plint is fully green (PMD wall 0, CPD wall 0,
    EP + NullAway clean) for the first time since the slot-role
    series. Engine-backed verification of the touched gametest
    surface (rig tickOnce + spawnHostile) rides the pooled
    runGameTest batch, not this entry.

    42. 2026-08-28 audit completion round (amends 41): the audit's second-tier findings landed - Goals.cellOf dedupes the three hand-rolled goal-cell extracts behind one exhaustive api/goal method; RangedLoadouts ends DefendProcess's cross-tier reach into CombatBehavior; TargetTracker owns the Defend/Attack sighted/leash/grace state machine with its load-bearing order in one doc; BotController's telescoping constructors collapsed 4 -> 2; WorldCommands gained posArg/faceArg/posJson; the rescue finders share one scanNearest skeleton; the rig pins SETTLE_TICKS and the hostile-spawn convention. Declined with rationale: a discard finisher and terrain-pit helper (tail shapes vary across 40 sites; unification would churn engine-unverified scenarios), and a shared SourceScan beyond RepoRoot unification (each gate's pinned list is a deliberate review checkpoint). code-health's god-class paydown item closed - PMD-as-yardstick met.

    43. 2026-08-29 stop-contract fix + H1 closure: /bot stop had handed the verb gotoHandler.stopAll() alone, so the emergency brake (issue 0011: cancel every live task) silently spared dig/mine/attack missions since their verbs landed; the wiring now sums VerbTaskHandler.stopAllSweep over the assembled taskHandlers list (05d0394). H1 closes on census evidence (code-health): the api surface measured ~8% thick, all trims off-contract (88568bd). New issue 0016 records the boundaries.md decision-12 drift the census surfaced - EventQueue reset is unwired (no production caller of reset/resetAt) while the contract text claims resetAt wipes the queue; ruling deferred to the boundary-D owner, both resolutions are one-commit changes.
    44. 2026-08-29 ponytail re-audit (user ruling: the prior lean rounds leaned conservative; executed as atomic commits): ReflexSeat folded into ReflexMissionSeat - post-unification the interface had one implementation and zero test injections (code-health abstraction row re-pinned). GametestRig absorption: eventsOf/eventSeen replace six per-scenario statusSnapshot chains, cellOf(BlockPos) replaces eight inline CellPos constructions, and fillPool generalizes over a z-range and fluid so both trenches and the shore pool ride it - amends 42: the 40-site churn rationale binds the discard finisher only; the terrain-helper piece it declined is executed with engine-neutral deltas (the one-deep lane trenches gain an inert stone base one block below the floor). Declined: a FIRE_RESISTANCE helper (two one-line sites; net inflation). Queued: InterruptionContext.interruptedTask() (written, never read) as a Stage-3-review flatten candidate - boundary-C vocabulary, not cuttable outside it. The MenuCommands mini-JSON row resolved without code: e2b0330 dissolved the embedded protocol.

    45. 2026-08-29 rulings executed + the owed engine batch: the user's three rulings landed. (1) Issue 0016 wired: the harness reset now wipes the event stream and advances resetAt (BotController.reset, 3166ebd); onRespawned deliberately keeps the stream - the 5b counter-preservation logic applies to diagnostics wholesale. Issue archived to issues/archive/. (2) The pooled runGameTest batch ran to completion for the first time since the slot series - ALL 49 required tests passed - after two engine-only defects were root-caused and fixed: HungryProcess's terminal hold pointed at the world origin (0,64,0), so a failed forage mission steered the planner into a 380,160-cell capture box and wedged the server thread for minutes per reflex cooldown (7f02283); and the eat chain applied zero nutrition - VanillaFoodCatalog's entry-set iteration read food properties as null for 1215 of 1255 items while direct key lookups worked (table rebuilt key-driven), and eatHeldItem consumed the stack BEFORE Forge's contexted FoodData.eat re-read properties from the already-consumed stack (nutrition now captured pre-consumption, primitive eat) - both fd520a8. (3) The PIT weakest gates strengthened: scoped pitest runs (-PpitClasses/-PpitTests on the gradle pitest block) measured the PathingBehavior+MineProcess pair at 121/200 killed; MineProcessContractGateTest (scan geometry, budget boundaries, returned-directive discipline, resume honesty) and PathingSteerMathGateTest (the distanceToSegment metric with endpoint clamping; the method widened to package-private per the MineProcess.phase() precedent) killed 29 more -> 150/200 (75%), from the 36%/31% baselines. Note for future engine debugging: runGameTestServer wedges on exit when the world DirectoryLock survives a killed MC - delete run/world/session.lock before rerunning.

    46. 2026-08-29 anvil/XP surface + the movement/combat/dig slice, engine-proven: the user transferred fix authority over the concurrent session's in-flight WIP, and the pooled runGameTest batch ran ALL 52 required tests green after three root causes were fixed. (1) The anvil/XP menu surface landed as reviewed (ad93fc2): setAnvilName promoted to MenuTransactions as a claim-only default no-op with the ActorMenuTransactions override - the facade's public-field write is what AnvilMenu.mayPickup and EnchantmentMenu.clickMenuButton read - and giveExperienceLevels resets the progress bar only on level LOSS (vanilla semantics); anvilRenamesAndChargesLevels, enchantsThroughMenuButton and xpSurvivesMenuCycles pin it in-engine. (2) The steering sneak flag is medium-gated (PathingBehavior.sneakForLeg, 68dec65): keyed on waypoint-below-floor alone it held shift on every land Drop and step-down leg, and the body's maybeBackOffFromEdge guard pins a sneaking body at the ledge - the corridor-STUCK class behind the gauntlet/deep-pool/trench failures; the gate mirrors SwimDown's source-liquid viability (SteerSneakGateTest). (3) The body never registered ATTACK_SPEED/ATTACK_KNOCKBACK - Player-tier attributes absent from Mob.createMobAttributes - so getAttributeValue threw on every USE press and the fight scenarios died in the crash latch; both registered at vanilla bases 4.0/0.0 (725e528). (4) MeleeResolver's cooldown timer reset on every swing, unready ones included; with zero damage below full charge, a caller pulsing faster than the item cooldown (CombatBehavior paces 10 ticks, a sword needs 12.5) deferred the window forever - reset is ready-gated now (vanilla resets unconditionally but pays scaled damage; deviation documented). The dig slice landed alongside (e03e945): the Player.getDigSpeed modifier stack minus the airborne half in DigPacing (pure, DigPacingTest-gated) and a tool-aware break sequence (held stack as TOOL loot context, mineBlock durability, vanilla mining exhaustion). Durable engine lesson: pooled runs cross-contaminate - crashed combat scenarios' zombies wandered into neighbouring structures and took down the lava-trench LOCOMOTION scenario, so one root cause surfaces as failures in apparently-unrelated scenarios; suspect the cascade before multi-cause theories. Doc paydown rode along (f1f4a91): toolchain.md gained the PIT scoped-round flags, ADR-0001 re-verified.

    47. 2026-08-29 mechanics QA execution round (qa-results/mc-bot-mechanics, seeded 98c8d67): the run went from zero-executed to 32/32 case verdicts - 8 offline-backed against the 543-test suite, 24 engine-backed against pooled receipts, two honestly partial (positive falling-crit and sprint-sweep-negative; no production path stages a reliable mid-fall swing or a forced sprint, and faking one would pin bot physics, not the gates). The engine suite grew 52 -> 67 behind a new BotDiggingGameTests family plus combat/locomotion/inventory additions, inventory-pinned same-commit (H-R5 friction), two consecutive GREEN pooled runs. The round's one product P0, caught by the first pooled run: DigExecutor called dropResources unconditionally, so BARE HANDS dropped cobblestone - vanilla gates drops AND the 0.005 exhaustion on canHarvestBlock (ServerPlayerGameMode.destroyBlock flag1 branch; stone.json's cobblestone entry carries no match_tool condition, only survives_explosion), while mineBlock wear stays unconditional; fixed 5adeea1, pinned by the bareHandedStoneLeavesNoDrop/pickaxeDigsStoneIntoCobblestone pair. Verification-currency mechanism landed (399f494): runGameTest writes qa-results/engine-runs receipts and status renders receipt-vs-later-commits staleness - the mechanical half H-R5 lacked. Assertion-stability pattern for drop/XP scenarios (6bd9bfb): drops and orbs scatter past the ~1.5 pickup box and the goto-to-drop races the reflex mission releasing the move channel, so fidelity pins accept residency either way (banked/absorbed OR lying at the site). Durable engine lessons: BLOCK_BROKEN is the command-handler sweep's disclosure, invisible to direct-arbiter scenario submissions; engage spam lands cooldown-scaled weak hits whose knockback slides NoAi targets out of the structure (zero ATTACK_KNOCKBACK stages a stationary kill); a 7-XP roll levels up and zeroes the progress bar (assert level>=1 || progress>0); a zero-drive Move claim takes the actor's idle branch and never latches sneak; absolute world coords in scenario asserts silently test the wrong cell under pooled placement (toLocal). Scaffolding paydown: the QA session's six one-shot mutation scripts left the tree, qa-results declared a Chinese-content data lane (README) - the one deliberate exemption from repo English-only, justified by its Lark-doc consumer.

    48. 2026-08-30 ranged/survival QA batch 1 (bow, shield, sleep, tool-select, rod; the round seeded 046799e): five scenarios landed green (engine 72/72, two consecutive runs) and the diagnostics caught a defect FAMILY, not just bugs - vanilla's held-item resolution never goes through getInventory() or getMainHandItem(): getItemInHand routes through getItemBySlot (LivingEntity:1923) and Player.getProjectile's fallback iterates the private this.inventory field (Player:2130), so the facade's overrides were invisible to exactly those paths. Fixed by bridging getItemBySlot to the body's equipment mirror and overriding getProjectile over the bridge (09ad5ae); InteractBlockExecutor's compensating manual shrink died with them (it was an obverse workaround for the same private-list emptiness and would double-count now). Ballistics aimed from the feet - CombatBehavior shared the pathing supplier, overstating target elevation by the full eye height; ranged fire now gets eyePoseOf (5f422fa). BobberSnapshot carried a block CellPos so the dip watch compared integer cells - half the bites were phase-dependent misses; it carries the exact Vec3 now with threshold-edge pins (1ba8a6c). Confirmed-correct-but-unverified: the body-side shield raise blocks frontal arrows when the USE claim stays armed through the flight (a claim gap releases the hold mid-flight - scenario discipline). Diagnostic lessons: the bare-facade probe (fresh facade driven directly in-scenario) isolated the routing bug in one run; FishingRodItem.use toggles cast/reel on player.fishing so a same-tick double invocation retrieves instantly - that masqueraded as an owner-resolution defect for most of the round; world-wide entity census plus nearest-entity coordinates beats structure-local counting for projectile forensics.

    49. 2026-08-30 ranged/survival batch 2 (closes the round): three scenarios landed - a rear arrow lands through the raised shield (the hurt-side direction gate, LivingEntity:1307-1313, is the mechanism the frontal pin's sibling), the 4-tick bow tap stays in the weak band against the 25-tick full draw on one south-column target, and the tool selector swaps to shears for leaves. Two round verdicts closed by audit, not scenarios: the rescue surface (TC-021) is engine-backed through the existing escapesLavaToShore / findsWaterWhenBurning pins - RescueMissionFactory's null-to-FREEZE degradation path is the only uncovered residual (deferred); the shield 0-4-tick latency premise (TC-015) is NOT-A-APPLICABLE in 1.20.1 - isBlocking has no warmup (LivingEntity:3131; the warmup mechanic is 1.21+), recorded with the citation. The batch also paid one pooled-flake tax: a stray angled tap arrow once took down crossesLavaTrench in a neighbour structure (the ledger-46 cross-contamination class, third sighting) - projectile scenarios now keep every shot path inside the structure walls. Scope note (post-review annotation): this entry's own commits are scenario/doc/closeout only; the ROUND's three product fixes (facade getItemBySlot/getProjectile bridging, eye-line ballistics, bobber Vec3) landed in batch 1 under ledger 48 (09ad5ae/5f422fa/1ba8a6c); any "seven fixes" figure counts both QA rounds cumulatively (mechanics round: harvest gate + compensation removal; ranged round: the three above).

    50. 2026-08-31 boundary-D honesty round, receipt-closed: the black-box QA round (qa-results/boundary-d, ledger-of-record in its round-summary) pinned both known section-S gaps of issue 0015 with red receipts and closed them the same day. (1) resetAt epoch honesty: the marker was queue-instance state, so every /botspawn and every JVM boot minted 1 - colliding with client bookmarks at epoch 1 and silently stranding new events below the stale cursor (loss, not replay). The marker now draws from an allocator at construction and on reset; the production source is EventEpochStore, a SavedData sequence in the overworld (crash window = last world save, detected by the client's id-space backstop). ResetEpochGateTest pins the queue-side contract; boundary-D cases C2b/C2-post flipped red->green live. (2) entity-ticking ticket: BotChunkTicket follows the body with a forceload grant from BotAssembly.tickOnce (before the pipeline reads), released on despawn/replace/death, re-granted while the body is UNLOADED_TO_CHUNK (unload is not death - the load re-materializes the body, the mechanism C1's manual-forceload probe proved). Mechanism verdict with probes: a TicketType.create region ticket at distance 3 LOGGED as granted yet never loaded its chunk (setblock kept answering "not loaded"); setChunkForced proved itself twice live, is operator-visible via forceload query, and costs one entity-tick skip per border crossing (accepted, momentum persists). Verification instrument is the boundary-D runner, not a gametest: the gametest world is void outside structures, so a far tp lands in ungenerated chunks and the bare-server shape is unreachable in-engine (a first-cut scenario also cross-contaminated a neighbour structure - removed, GametestInventoryCheck repinned). Open engine observation (not this round's scope, pinned live): the mission directly after a NO_PATH failure can itself NO_PATH instantly; a completing mission heals the window. Declined: streamGeneration as a second restart signal (resetAt is the stream generation - one concept one owner) and the MAX_DISTANCE goto precheck (+80 STUCK, +150 still-walking controls show no distance fast-fail; the +400 NO_PATH was genuine unreachability on that seed).
    51. 2026-09-01 Combat range routing goes stateful and weapon-aware
    (amends 37; exposed by the 08-31 pooled receipt where the ranged
    scenario never landed an arrow). Three rulings, one concern: (1)
    ROUTING HYSTERESIS - CombatBehavior's ranged/melee route was a
    per-tick distance reflex, so a draw aborted the tick the target
    crossed ATTACK_REACH and a target oscillating across 3 blocks
    (or a chase closing through it) restarted the draw every approach
    tick and never released; a draw past BOW_COMMIT_TICKS (half
    charge) now finishes its shot through BOTH band-edge flips, with
    the waste bounded at one draw and the release under half a draw
    away. (2) POINT-BLANK POLICY - the bow never answered inside
    melee reach even for a carrier with no other weapon; under the
    weapon-aware configuration (the catalog-carrying constructor; the
    no-catalog constructor keeps the legacy melee band) the bow
    answers at every distance while nothing in the hotbar outranks
    it - full-draw arrows replace fist-tier clubbing, with the
    ranking predicate shared as RangedLoadouts.meleeWeaponBeatsBow.
    (3) WEAPON-AWARE ENGAGEMENT - DefendProcess charged every
    melee-typed target to the swing rim regardless of loadout; a
    bow-only carrier (no hotbar weapon outranking the bow) now opens
    those fights at the standoff rim and fires on the close, the
    decision frozen at engage time; AttackProcess gains the same
    first-sighting freeze, and the two tiers read ONE catalog
    instance (BotAssembly wires VanillaWeaponCatalog into both). The
    standoff rim is an OPENING posture, not a maintained range -
    GoalNear never backs the body up; a genuinely maintained range
    needs the behavior-coordination channel and is deferred to issue
    0018. Pinned offline by the CombatRangedGateTest commit/abort
    pairs and the DefendProcess/AttackProcess standoff tests; the
    engine bow scenarios close through the new standoff path. The
    same round's second receipt line (bowtap landing full-draw
    damage, double RED at delta 9.2/10.2) diagnosed to its root: the
    hand-pumped scenario spawns its target at exactly
    EngageOnHostileProximityRule's 6.0 trigger boundary, so on the
    fire side of the knife edge the reflex-owned CombatBehavior
    fights the pump for the USE channel and the release edges
    corrupt (the bowtap 14.096 flake family). Fixed at the scenario
    layer - target re-spaced to seven cells, a no-reflex-verdict pin
    added - hand-pumped scenarios own their channels exclusively.
    52. 2026-09-01 Reactive shield: the combat tier answers incoming
    projectiles. This closes the integration gap the 09-01 capability
    review exposed - the raise/release mechanism shipped with issue
    0003's body-blocking work, but no combat decision ever reached it
    (raisedShieldBlocksFrontalMelee rigged the USE claim by hand).
    Boundary A grows one query, bobber-precedent shaped:
    WorldView.getProjectiles (default empty) answers
    ProjectileSnapshot{pos, velocity} over the Projectile family in
    the adapter, bobbers excluded (they own their query). The
    decision is combat-tier geometry, not a reflex: a live combat
    order plus a hit-course flight plus a carried shield preempts
    BOTH attack paths - even a committed draw releases (the commit
    guard bounds wasted charge; it does not outrank survival). ROT
    tracks the flight itself (isDamageSourceBlocked gates on the
    source bearing, and the projectile IS the source); the shield
    claims SLOT one tick before the USE press because the actor
    applies USE before SLOT - a same-tick press would read the
    weapon; the hold releases when the flight clears plus a 10-tick
    linger that deliberately does NOT bridge a skeleton's ~40-tick
    cadence (a longer hold is refusal to fight, not defense). The
    threat geometry (ProjectileThreats) is straight-line closest
    approach: speed floor 0.2 b/t, a 1.2-block guard band, a 25-tick
    arrival horizon; receding flights self-filter, so the bot's own
    arrows never trigger it. Two budget facts are stated, not papered
    over. (1) The vanilla arm delay - isBlocking requires five held
    ticks - makes the reaction cost 2 channel ticks + 5 arm ticks, so
    a full-draw arrow deep inside the band can still land first;
    anticipating a shooter's draw is deferred until a second consumer
    needs it. (2) A carrier with nothing but a shield never presses a
    swing: the melee hold-item gate refuses to press a USE edge on a
    still-held shield (it would re-raise the guard), which also
    closes the pre-existing race where a bow-held swing tick drew the
    bow instead of swinging - the ranged/melee transition tests now
    feed SLOT claims back into their mocks (BindingActor.applySlot
    semantics) to pin it. Offline: ProjectileThreatsTest geometry and
    the CombatShieldReactionGateTest raise/linger/release/resume
    pairs. Engine: blocksIncomingArrowsMidCombat - damage control
    (unshielded arrow drops health), blocked (identical geometry
    deals nothing with the guard armed), release (guard lowers after
    the flight) - through the production scan/decision/body chain.
    53. 2026-09-01 Cut-vs-drained verdict weight in the async
    planner (the pullscraftsandbanksbyrecipeid 13:10 flake, root-caused
    from the receipt shape: bot never moved, mission already dead, in
    the one loaded run where bowtap and the ladder timeout also fired).
    AStarPathFinder's empty-result return served THREE different
    endings with one shape: a naturally drained open set (real
    unreachability), a wall-clock/node-budget cut whose partial
    collapsed under MIN_PARTIAL_CONFIDENCE, and - one hop up in
    PlanLifecycle - an exceptional worker future. All three collapsed
    into NO_ROUTE, and PathingBehavior turned that into an instant
    mission-level NO_PATH the same tick. The killer detail: the
    wall-clock deadline is armed at compute entry and checked every 64
    expansions, so a worker thread the scheduler starves past the 200ms
    budget BEFORE its first check returns a zero-progress cut - a
    starved thread read as a drained search space, killing 7-block
    walks that one retry would complete. Ruling: PathResult carries
    drainedOpenSet (true only when the open set emptied naturally);
    failed() keeps verdict weight, a new cut() factory carries none,
    and the exceptional-future catch lands on cut(0).
    PlanLifecycle maps empty results through that flag - NO_ROUTE for
    drains, a new CUT outcome for cuts - and CUT gets STALE's
    treatment (primeLadder, re-ask; the cooldown gates the retry, the
    progress fuse bounds patience at 20 stalls, and sustained
    starvation now dies an honest STUCK/TIMEOUT instead of a false
    NO_PATH). The sync test-only mode is deliberately unchanged: no
    wall clock runs offline and its budget-cut semantics are pinned by
    the existing gates. Pinned by AStarWallClockGateTest's flag pair
    (starved-start cut carries no weight, drained pocket keeps it) and
    PlanCutRetryGateTest (node-budget-8 cut re-asks, exceptional
    future re-asks, pocket drain stays NO_ROUTE, all through the real
    worker). The scenario's wait message now prints failure= for the
    next occurrence. Engine rerun for this round: deliberately not run
    (session closeout); the offline gates are the receipt.
    54. 2026-09-01 GoalRange vocabulary growth (closes issue 0018,
    amends 51). The ranged standoff was GoalNear(target, 10): satisfied
    once inside 10 blocks, never backs the body up, so any closing
    target walks straight through the rim to point-blank. The fix is a
    pure Goal-algebra extension — no claim-model change, no behavior
    coordination channel. GoalRange(center, min, max) is a band
    predicate: isInGoal is true only when min <= chebyshevTo(center)
    <= max. A cell inside min fails the predicate, so A* terminates at
    the nearest outward band edge and the body moves away. The load-
    bearing piece is the heuristic: Goals.heuristicOf(goal) returns
    band-edge distance for GoalRange (max(0, min-dist, dist-max)) —
    zero on the goal set, positive inside min (pulling outward) and
    outside max (pulling inward). GoalBlock/GoalNear keep the historical
    euclideanTo anchor. PlanLifecycle.request/computeSync swap
    Heuristic.euclideanTo(cellOf(goal)) for Goals.heuristicOf(goal).
    DefendProcess and AttackProcess ranged standoff migrate from
    GoalNear(target, 10) to GoalRange(target, 8, 12) — the band is
    RANGED_STANDOFF +/- 2, giving a 2-cell buffer for the async replan
    latency. Melee stays GoalNear(target, 2). Pinned by
    GoalRangeGeometryTest (predicate boundaries, heuristic direction,
    validation, GoalNear regression pin) and PathingRangeBandGateTest
    (in-band SUCCESS, inside-min plans outward, outside-max plans
    inward, equal-goal no-replan). Engine gametest
    rangedBotBacksAwayWhenTargetClosesInsideMin: NoAi zombie at
    distance 5 (inside reflex trigger 6 and GoalRange min 8), reflex
    engage path (no manual submitDefend), assert the body backs west
    to restore >= 8 separation and lands arrows while kiting. ENGINE
    STATUS: gametest fails in-engine — the body does not move from
    its spawn cell (bodyX constant across the full timeout), even
    though the same GoalRange directive drives correct outward
    replanning in the offline PathingRangeBandGateTest. The failure
    is environmental (gametest assembly / mission wiring), not in the
    Goal algebra or heuristic — offline gates are the receipt this
    round. Compile also intermittently blocked by concurrent-session
    WIP (BotHungerGameTests lambda / BotController emitEatStarted).
    [Amended by 56: the engine failure recorded here was NOT
    environmental — it was the steering-frame coupling between the
    combat aim's ROT claim and the body-relative MOVE intent; the
    offline-green / engine-red asymmetry is exactly that no offline
    rig carries a competing aim claim. Root-caused and fixed in 56.]
    55. 2026-09-01 Core nullness strategy: get-deref audit, JsonFields.require
    convergence, and the GetDerefGateTest regression guard. Triggered by a
    six-site audit of chained get-deref patterns (receiver.get(key).member)
    in the offline-testable layers. The audit found three invariant classes:
    (1) temporal - SurvivalReflexLayer:154 read hysteresisState.get(name)
    after decideHysteresis had computeIfAbsent'ed the same key; the non-null
    guarantee lived in call order, not in data flow. Fixed by returning the
    HysteresisState from decideHysteresis so the caller consumes s.lastPriority
    directly - temporal invariant becomes data dependency. (2) structural -
    CraftingPlanner iterated recipe.placements().keySet() then reverse-lookup get(pos)
    three times; GotoCommandJson:40 did raw.get(e.getKey()) inside an
    entrySet loop. Fixed by entrySet iteration: e.getValue() replaces the
    get reverse-lookup, key origin becomes the entry itself. (3) guarded - GotoCommandJson
    had root.get("verb").getAsString() with no has-check (the only behavior
    defect: missing verb threw NPE, not the Javadoc-promised JsonParseException);
    AttackCommandHandler chained args.get("targetId").isBlank() behind a
    containsKey||get==null short-circuit; PlanSmoother chained runs.get(i).y()
    and runs.get(j).y() inside a while condition. All fixed by local-variable
    extraction. The shared JsonFields.require(obj, key) helper converges both
    JSON codecs (GotoCommandJson, ReflexRuleJsonReader) on a single
    checked entry point - require() throws JsonParseException on absent keys,
    and the helper name does not match the get-deref gate pattern, so codec
    bodies stay zero-hit. Key analyzer-capability fact: SpotBugs NP checks
    are SILENT on the computeIfAbsent-then-get pattern (no NP_ suppression
    exists in config/spotbugs/exclude.xml for SurvivalReflexLayer) - the
    analyzer does not track that a preceding method call computeIfAbsent'ed
    the same key on the same map. This is why a source-scan gate is needed:
    the pattern is invisible to bytecode analysis. GetDerefGateTest scans
    api/ + core/ (matching SpotBugs onlyAnalyze scope) for the literal
    pattern \.get\([^)]*\)\s*\.[a-zA-Z] and asserts zero violations, with an
    EXEMPT_FILES set (empty at launch) following the EnglishOnlyScan /
    SpotBugs-exclude inline-justification discipline. Documented blind spots:
    assignment-then-unchecked-use (get and deref on different lines - covered
    by SpotBugs NP on core/) and List-vs-Map distinction (no type info in a
    source scan). The deferred upgrade path is an Error Prone custom BugChecker
    (AST + type resolution, can distinguish Map.get from List.get and can see
    across statements); trigger condition: EXEMPT_FILES non-empty growth or
    List.get false-positive friction. NullAway core/ expansion is explicitly
    deferred: the api/-contract / core/-construct-reject split is a deliberate
    design fork, and pre-paydown expansion would flag every keySet-origin and
    computeIfAbsent-then-get site as possibly-null (the gradual-migration baseline law).
    Registration (outside-repo-unverifiable): the CI static-analysis job
    (.github/workflows/ci.yml, runs qualityCheck -Plint on every push and PR)
    is asserted to be a required branch-protection check. This assertion cannot
    be proven from repository content - branch protection lives in GitHub
    Settings, not in any git object. Verification is a one-time human check of
    the Settings/Branches page; the assertion is recorded here so a future
    agent does not infer "CI runs it" from ci.yml alone and mistake presence
    for enforcement. Scope note: this entry covers the offline layers only;
    adapter/ get chains ride MC types and are excluded from the gate (same
    scope as SpotBugs onlyAnalyze and JaCoCo offline exclusions).
    56. 2026-09-01 Steering frame decoupling + band-aware progress (amends 54).
    Decision 54's engine failure was misattributed as environmental.
    The MOVE intent is body-relative and the drive adapter resolves it
    along the live facing (vanilla Entity.getInputVector: forward basis
    (-sin Y, cos Y), strafe basis (cos Y, sin Y), positive strafe is
    LEFT per the xxa convention), while the combat aim's ROT claim
    (priority 20) beats the mover's look claim (10). A GoalRange plan
    away from an aimed target therefore marched the body INTO the
    target — the exact opposite of the kite. Ruling: PathingBehavior
    decomposes the steer step into forward/strafe against a
    BodyYawSource — the live body yaw wired in BotAssembly, the
    identity steer-bearing default in every legacy constructor, so
    uncontested walks keep the bit-identical forward-only drive and
    the 43 offline construction sites change nothing. This is the
    vanilla skeleton kiting pattern: strafe and backpedal while
    facing the target. The arrival brake bounds the drive vector
    magnitude; sprint already requires forward >= 0.95, so backpedal
    and strafe never sprint. Second organ, same disease: the progress
    tier still measured against Goals.cellOf(goal) = the center, so
    an inside-min kite's correct outward motion read as regression
    (false STUCK accumulation, false NO_PATH witnesses on long kite
    legs). Goals.distanceOf now supplies the position-space distance
    to the goal set — continuous Chebyshev band-edge for GoalRange,
    the exact historical Euclidean-to-anchor-centre for point goals
    so goto STUCK timing is untouched — feeding PlanProgressFuse
    criterion 2 and NoPathEscalator witnesses. Pinned by
    PathingBehaviorFrameGateTest drive-frame cases (aligned identity,
    opposed backpedal, xxa left sign, diagonal split),
    GoalRangeGeometryTest distanceOf cases, NoPathEscalatorTest band
    witnesses, PlanProgressFuseSegmentTest criterion-2 kite case.
    Rig companion: GametestRig.sweepForeignEntities clears a
    structure's footprint of foreign entities before aimed-shot
    scenarios (ledger 46 cross-contamination family, the bowtap
    clean-miss class).
