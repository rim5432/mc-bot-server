---
title: Extreme-scenario survival framework - the vital-sign ladder beyond drowning
last_verified: 2026-08-25
covers:
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ThreatBlackboard.java
  - src/main/java/com/mcbot/mcbotserver/core/reflex/MinimalReflex.java
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ReflexAction.java
status: resolved (all rulings shipped 2026-08-25; deferred tails recorded in section 5; archived 2026-08-25)
superseded_by: architecture/boundaries.md

related:
  - doc/architecture/issues/archive/0004-movement-primitive-vocabulary.md
  - doc/architecture/issues/0007-player-parity-interaction.md
  - doc/architecture/issues/0009-block-capability-dig.md
  - doc/architecture/issues/0010-hungryprocess-food-acquisition-planner.md
  - doc/decisions/0003-reflex-layer-preemption.md
  - doc/guide/workplan.md
---

# Issue 0008: Extreme-scenario survival framework

## 1. Scope and lineage

Issue 0004 F6(2) reserved the drowning reflex as one line: "low
bubbles -> preempt toward surface, rule-table data, not new code."
That reservation is now fully shipped (boundaries.md decision
ledger 22; in-engine green via `surfacesWhenAirRunsLow`) and taught
us the general shape. This issue upgrades that single-scenario
reservation into a framework discussion for ALL extreme scenarios,
at the user's request: which lethality sources exist in vanilla
1.20.1, which the bot can actually sense on the current mob
carrier, what response closes each loop, and what it would cost.

Non-goal: implementing anything here without a ruling. Every
finding below ends in a proposal or an explicit open question.

## 2. What shipping the drowning reflex taught us (the framework)

Four pieces per vital, one triage ladder across all of them:

1. **Sense field with a safe default** on `ThreatBlackboard` —
   `beginTick` resets to "not alarmed", so a rig that never stamps
   the field reads as healthy (missing data must never mint an
   alarm). Air did exactly this (`airSupply = 300`).
2. **Sensor stamp** — vitals are entity state, not world state:
   they have no `WorldView` expression and arrive through the
   sensor/pipeline like `botHealth` and `airSupply` do.
3. **Rule row** — data, not code: trigger/release thresholds sized
   from the mechanic's real numbers, hysteresis supplied by the
   layer (activation `<=`, release strict `<`, decision 23a(c)).
4. **Response closure** — one of exactly three shapes observed so
   far:
   - **Self-rescue**: the reflex's own action physically removes
     the cause (ASCEND -> surface -> air regenerates). Cheapest;
     one Intent.
   - **Mission handoff**: the rule notices, the controller spends
     one preemption tick, then a mission owns the multi-tick state
     (ENGAGE -> DefendProcess, decision 23).
   - **Park and escalate**: the bot cannot fix it; FREEZE holds the
     body and TASK_PAUSED tells the harness it must decide.

Triage ladder is contract, not taste: LAVA(130) > SUFFOCATE(115)
> SURFACE(110) > FIRE_ESCAPE(105) > FREEZE(100) >
POWDER_SNOW_CLIMB(95) > ENGAGE(90) — one lethal condition must not
become two (surface before freeze underwater); at three health points
do not start a fight (freeze before engage). Every new rule must
state where it slots into this ladder and why.

## 3. The lethality catalog (all numbers from the decompiled tree)

Sorted by reaction budget — the time the bot has between "signal
readable" and "dead from full health":

| Scenario | Mechanic (site) | Budget class | Readable via | Today |
|---|---|---|---|---|
| Lava | 4.0F **every tick** in lava, no interval + `setSecondsOnFire(15)` (Entity.baseTick:548-549) | **instant** — 5 ticks from full HP | `isInLava()` | SHIPPED (ledger 26): ESCAPE_ON_LAVA 130, rescue GotoProcess to nearest shore; crashed-state MinimalReflex also handles |
| Suffocation | 1.0F **every tick** eye-in-wall, no interval (LivingEntity.baseTick:387) | **instant** — 20 ticks | `isInWall()` | SHIPPED (ledger 24, upgraded by 0009): DIG_ON_SUFFOCATION 115, digs eye block |
| Void | 4.0F every tick below `minBuildHeight - 64` (LivingEntity:1859) | **instant, unanswerable** — nothing rescues below the line | `getY()` vs level min | nothing (planner-side Drop<=3 is the only guard) |
| Cactus | 1.0F/tick on AABB contact (CactusBlock.entityInside) | instant-ish, tiny per-tick | no getter — needs contact check | nothing |
| Fire | 1.0F per second (every 20 ticks); lava-origin fire caps at 300 ticks = 15 HP (`setSecondsOnFire(15)`); other sources (fire aspect, lightning, blaze) vary; extinguished by water/rain/powder snow (Entity.baseTick:483) | **gauged** — seconds, survivable by waiting | `getRemainingFireTicks()` | SHIPPED (ledger 26): EXTINGUISH_FIRE 105, lethal-band only (fireTicks/20 >= health), rescue to nearest water |
| Drowning | air 300, drain 1/tick, damage 2.0F/s once air <= -20 (Forge LivingDrownEvent) | **gauged** — 300 ticks + 1 s | `getAirSupply()` | SHIPPED (ledger 22) |
| Freezing | freeze +1/tick in powder snow to 140, decay 2/tick; at 140: 1.0F per 40 ticks (LivingEntity.aiStep:2666-2680) | **gauged** — 140 ticks to first damage, slow after | `getTicksFrozen()`, `isInPowderSnow` | SHIPPED (ledger 27): CLIMB_OUT_OF_POWDER_SNOW 95, ASCEND (held jump — powder snow is climbable, Entity.isStateClimbable:764), trigger=100 (2s before damage) |
| Cramming | 6.0F, avg every 4 ticks over the 24-entity rule | instant-ish | rule + nearby count | N/A for a solo bot |
| Starvation | Player-only: `FoodData` exists solely on Player (Player.java:168); Mob has no hunger at all (confirmed: zero FoodData references outside Player) | n/a on this carrier | `getFoodData()` — player only | blocked on the 0007 carrier ruling |
| Fall | `ceil((fallDistance - 3) * multiplier)` on landing | **event** — no gauge to sense pre-impact | `fallDistance` (public) | planner-side only (Drop<=3 matches the 3-block safe fall) |

Two carrier facts worth pinning: the mob carrier is NOT
water-sensitive (`isSensitiveToWater()` false — no rain damage,
that path is player/undead class), and it DOES drown through the
Forge breathe hook (the gametest proved the air mechanics apply).

## 4. Findings and proposals

### F1 - One vitals sensing pass (the cheap consolidation)

Every readable vital in the table is an entity-state read in
exactly the `airSupply` category. Proposal: extend the sensor
contract with a vitals pass — `inLava`, `fireTicks`,
`freezeTicks`, `inWall` — each defaulted safe in `beginTick`.
Cost S; unblocks every rule below; no boundary change (board
vocabulary growth).

### F2 - Lava in the live pipeline (the honest gap)

`board.inLethalFluid` has been dead since ADR-0003 drew the board —
only the CRASHED path reads the body directly. Yet lava is the
fastest killer in the catalog (4 HP/tick). The F2 fluid-ascent fix
(0004) already gives the executor a lava branch: holding jump in
lava calls `jumpInFluid(LAVA)`. So the reflex half is nearly free —
but ascent alone parks the bot ON the lava surface burning; the
exit is horizontal (reach shore), which is mission-shaped.
Proposal, two layers: reflex `ASCEND` on `inLethalFluid` (buys
seconds, zero new vocabulary), plus an escape mission submitted by
the controller on the ENGAGE pattern (park current, submit a
GotoProcess to the nearest non-liquid cell — the planner already
models liquid traits). Open question: is the escape mission worth
its machinery now, or does ASCEND + harness escalation suffice
until an unattended run actually dies in lava?

### F3 - Fire: probably rule NOTHING v1

Fire is self-limiting in practice (lava-origin caps at 15 HP worst
case, 1 HP/s; the engine has no hard cap on remainingFireTicks but
lava is the dominant source for this bot) and self-answering the
moment the bot's ongoing route touches water (extinguish on
`isInWaterRainOrBubble`). A FIRE reflex that freezes the bot would
REDUCE survival (stop moving = never reach water). Proposal:
sense-only (F1), no rule; revisit if a harness wants an event. The
interesting interaction is recorded for triage: burning + drowning
— diving HELPS (water extinguishes), so SURFACE must not
permanently win that pair; the 80-air trigger leaves ~220 ticks of
submersion headroom for the fire to die first. Verify at rule
design time, not now.

### F4 - Suffocation: park and escalate, treat as a bug siren

1 HP/tick with no interval is instant-class, but the "rescue" is
unknown-direction movement — a reflex guessing a direction is worse
than honest escalation. Suffocation on this codebase almost always
means a move-vocabulary or collision-predicate bug (the body is
inside geometry it should never occupy). Proposal: sense-only + a
rule whose action is FREEZE (stop digging deeper into the glitch)
with priority ABOVE SURFACE (115?) — it is the one scenario where
freezing is strictly correct. The real fix is always upstream in
pathing; the reflex is the crash report.

### F5 - Void and fall: document as unanswerable, guard upstream

Below `minBuildHeight - 64` nothing in the intent vocabulary
helps. Fall has no pre-impact gauge to sense. Both belong to the
planner (Drop<=3, the correct 3-block safe-fall alignment) and to
future edge-guard vocabulary (function-map GAP already lists fall
protection). Proposal: no reflex rules; this issue records the
verdict so nobody re-derives it.

### F6 - Freezing: gauged, slow, rare; shipped as pure ASCEND

140 ticks of budget and 1 HP/2s after — the least urgent gauge in
the catalog. Sense-only rides F1 (`freezeTicks`). The response is
the cheapest self-rescue shape: powder snow is climbable
(`Entity.isStateClimbable` returns true for powder snow, same as
ladders/vines — decompiled Entity.java:764), so holding jump
(`ASCEND`) applies the same upward impulse as climbing a ladder and
walks the body out. No mission handoff needed — the reflex's own
action removes the cause. Trigger at freezeTicks=100 leaves 40
ticks (2 seconds) before the fully-frozen damage phase starts.
Priority 95 (below FREEZE 100, above ENGAGE 90): a bot at 3 HP
should freeze rather than climb, because the climb takes ticks and
the freeze damage is slow enough that FREEZE+harness is safer.
Shipped (ledger 27): `ClimbOutOfPowderSnowRule`, in-engine
`climbsOutOfPowderSnow`.

Design note: `ReflexHysteresis` cannot be used here — the layer's
`decideHysteresis` assumes "lower signal = more dangerous" (the air
supply shape), but freezeTicks is "higher = more dangerous". The
direction inversion would keep the rule off at the trigger. Pure
`computePriority` (same shape as `EscapeLavaRule`/`ExtinguishFireRule`)
is correct: freezeTicks changes at most 2/tick on thaw, so boundary
flapping is sub-perceptible and a hysteresis dead-band is unnecessary.

### F7 - Starvation: moved to issue 0010

Originally pre-ruled as "sense needsFood, EAT_WHEN_HUNGRY at routine
urgency, one-tick handoff to eat mission." The user ruling
2026-08-25 upgraded the scope: the bot must also **acquire** food
when inventory is empty, with self-contained initial strategies
(forage/hunt/fish) and harness escalation only when all fail. This
outgrew F7's one-line reservation. **Migrated to issue 0010**
(`0010-hungryprocess-food-acquisition-planner.md`), which designs
the full HungryProcess planner, the three initial strategies, the
escalation boundary, and six open decisions (D1-D6). F7's original
consumption-side ruling (auto-eat when food in inventory) is
absorbed as HungryProcess's CONSUME state.

### F8 - The crashed-state asymmetry (MinimalReflex)

ADR-0005's degraded mode handles exactly one vital: lava jump. A
crashed bot standing in deep water drowns; on fire it burns. The
ADR's constraint is "a few ifs, no dependency on the rule table" —
an air check (`airSupply < X -> hold jump`) is one more if in the
same dependency class as the lava flag it already takes. Open
question for ruling: widen MinimalReflex by one if, or keep the
crashed state maximally dumb and let the crash counter + harness
own it? The asymmetry argument: drowning-while-crashed was
witnessed in the field only if the crash happens underwater — rare
x rare.

### F9 - Triage interactions table (design-time obligation)

Any new rule must be checked against the ladder, not just added:
burning-vs-drowning (F3), lava-vs-low-health (surface of lava
while at 3 HP — ASCEND still right, it is the only exit),
freeze-vs-everything (no interaction — slowest gauge). Proposal:
the ladder rule set lives in this issue's table below and each
ruling must add its row before implementation.

| Fires together | Winner should be | Why |
|---|---|---|
| SURFACE + FREEZE | SURFACE (shipped, ledger 22) | freezing underwater doubles the death |
| FREEZE + ENGAGE | FREEZE (shipped, ledger 23) | no fights at 3 HP |
| LAVA + any | LAVA (shipped, ledger 24; upgraded to ESCAPE, ledger 26) | 4 HP/tick does not negotiate; ESCAPE submits shore rescue |
| SUFFOCATE + SURFACE/FREEZE/ENGAGE | SUFFOCATE (shipped, ledger 24; DIG, ledger 0009) | 1 HP/tick, and the eye block is the known dig target |
| SUFFOCATE + LAVA | LAVA (shipped, ledger 24) | both instant-class; lava is faster and ESCAPE is the only exit |
| FIRE_ESCAPE + SURFACE | SURFACE; fire then dies in water | water extinguishes; drowning is 2 HP/sec vs fire 1 HP/sec |
| FIRE_ESCAPE + FREEZE | FIRE_ESCAPE (105 > 100, ledger 26) | freezing does not stop the burn; finding water does |
| FIRE_ESCAPE + ENGAGE | FIRE_ESCAPE (105 > 90) | burning to death outranks starting a fight |
| FIRE_ESCAPE + LAVA | LAVA (130 > 105) | lava escape also leaves the fire source; 4 HP/tick outranks 1 HP/sec |
| POWDER_SNOW_CLIMB + FREEZE | FREEZE (100 > 95, ledger 27) | at 3 HP the climb takes ticks; freeze damage is 1 HP/2s, so FREEZE+harness is safer than a slow climb |
| POWDER_SNOW_CLIMB + ENGAGE | POWDER_SNOW_CLIMB (95 > 90) | freezing to death outranks starting a fight |
| POWDER_SNOW_CLIMB + SURFACE | SURFACE (110 > 95) | drowning is 2 HP/sec; freezing is 1 HP/2s — surface first |
| POWDER_SNOW_CLIMB + LAVA/SUFFOCATE/FIRE_ESCAPE | the faster killer (all > 95) | powder snow is the slowest gauge; every other lethal reflex outranks it |

## 5. Sequencing (as ruled)

1. **Shipped 2026-08-25 (ledger 24)**: D1 vitals pass + D2 lava
   ASCEND + D4 crashed-state air (416bd31), D3 suffocation FREEZE
   and the reload-parity repair (review follow-up: 416bd31 shipped
   the lava rule without a JSON form or datapack row - the first
   /reload would have silently dropped it; ReflexRuleJson branches
   + default table rows +
   `shippedDatapackTableCoversEveryCodeRegisteredRuleType` now pin
   the lockstep).
2. **Shipped 2026-08-25 (ledger 24, D3 upgraded by issue 0009)**:
   suffocation FREEZE renamed DIG_ON_SUFFOCATION with dig
   self-rescue; in-engine `digsFreeWhenSuffocating`.
3. **Shipped 2026-08-25 (ledger 26, D2 upgraded + D5 revised)**:
   D2 lava ASCEND replaced by ESCAPE_ON_LAVA (rescue GotoProcess
   to nearest shore, in-engine `escapesLavaToShore`); D5 fire
   sense-only revised to EXTINGUISH_FIRE (lethal-band trigger,
   rescue to nearest water, in-engine `findsWaterWhenBurning`).
   New infrastructure: ReflexAction.ESCAPE, ReflexRescueSeat,
   RescueMissionFactory, BotController rescue handoff (mirrors
   ENGAGE). Both rules carry reload-parity JSON forms + datapack
   rows.
4. **Adopted without code (original D4)**: fire is sense-only for
   non-lethal fire; the sensing fields landed in D1. The lethal
   band is now covered by D5-revised.
5. **Migrated to issue 0010 (2026-08-25)**: F7 starvation. The
   consumption-side auto-eat and the acquisition-side HungryProcess
   planner (forage/hunt/fish initial strategies + harness
   escalation) are now designed in `0010-hungryprocess-food-acquisition-planner.md`.
   Blocked on 0007 Player carrier + inventory.
6. **Shipped 2026-08-25 (ledger 27, F6)**: POWDER_SNOW_CLIMB 95,
   pure ASCEND (held jump — powder snow is climbable), trigger=100.
   In-engine `climbsOutOfPowderSnow`. Design constraint:
   `ReflexHysteresis` is direction-locked to "lower = more dangerous"
   (air supply shape); freezeTicks is "higher = more dangerous", so
   the rule uses pure `computePriority` (same as lava/fire escape).
   Reload-parity: JSON branch + datapack row + lockstep gate.
6. **Deferred with numbers recorded**: F5 (void/fall - no rule,
   planner's job), F6 (freezing - slowest gauge, shares the ESCAPE
   machinery now that it lands).

## 6. Decision log

| # | Question | Ruling |
|---|---|---|
| D1 | Vitals sensing pass (F1)? | RULED YES - shipped 2026-08-25 (416bd31, ledger 24): fireTicks / freezeTicks / inWall / inLethalFluid all sensor-stamped with safe beginTick defaults |
| D2 | Live-pipeline lava: ASCEND-only vs + escape mission (F2)? | RULED ASCEND-only first (416bd31): 130/ASCEND buys surface seconds. **UPGRADED 2026-08-25**: user ruling "must have shortest-shore escape" — replaced by ESCAPE_ON_LAVA (ESCAPE action, same priority 130, same hold 10). The reflex submits a rescue GotoProcess to the nearest shore cell (RescueMissionFactory, 12-block radius scan); the pathing behavior handles ascent + horizontal swim. The old ASCEND_IN_LETHAL_FLUID type is a hard parse error now. In-engine: `escapesLavaToShore` (5x5 pool, fire-resist, body reaches dry ground). Ledger 26 |
| D3 | Suffocation FREEZE rule at 115 (F4)? | RULED YES - shipped 2026-08-25 (ledger 24): FREEZE_ON_SUFFOCATION 115/hold 10, FREEZE action; ladder-gated between SURFACE and LAVA. SUPERSEDED same day by issue 0009: with dig capability the rescue direction is known (the eye block), so the rule is renamed DIG_ON_SUFFOCATION with the DIG self-rescue action - same priority, same hold, same ladder slot; the FREEZE survives only as the targetless-degrade path and the crashed-state behavior |
| D4 | Widen MinimalReflex with an air check (F8)? | RULED YES - shipped 2026-08-25 (416bd31): one more if, air < 80 holds jump; ADR-0005 D3 dependency class intact |
| D5 | Fire: sense-only, no rule (F3)? | RULED ADOPTED initially (no rule; sensing fields arrived with D1). **REVISED 2026-08-25**: user ruling "fire find-water is needed, urgency by burn-to-death threshold" — EXTINGUISH_FIRE rule added (ESCAPE action, priority 105 between SURFACE and FREEZE). Triggers only when remainingFireTicks/20 >= health (lethal band; integer division matching engine's 1.0F-per-20-ticks cadence, decompiled Entity.baseTick:483). Non-lethal fire stays sense-only per the original D5 intent. Rescue factory paths to nearest water-adjacent cell; water contact extinguishes. In-engine: `findsWaterWhenBurning` (health=5, fireTicks=100, water 4 blocks east). Ledger 26 |
