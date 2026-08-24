---
title: Extreme-scenario survival framework - the vital-sign ladder beyond drowning
last_verified: 2026-08-25
covers:
  - doc/architecture/function-map.md
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ThreatBlackboard.java
  - src/main/java/com/mcbot/mcbotserver/core/reflex/MinimalReflex.java
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ReflexAction.java
status: open
related:
  - doc/architecture/issues/0004-movement-primitive-vocabulary.md
  - doc/architecture/issues/0007-player-parity-interaction.md
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

Triage ladder is contract, not taste: SURFACE(110) > FREEZE(100) >
ENGAGE(90) — one lethal condition must not become two (surface
before freeze underwater); at three health points do not start a
fight (freeze before engage). Every new rule must state where it
slots into this ladder and why.

## 3. The lethality catalog (all numbers from the decompiled tree)

Sorted by reaction budget — the time the bot has between "signal
readable" and "dead from full health":

| Scenario | Mechanic (site) | Budget class | Readable via | Today |
|---|---|---|---|---|
| Lava | 4.0F **every tick** in lava, no interval + `setSecondsOnFire(15)` (Entity.baseTick:548-549) | **instant** — 5 ticks from full HP | `isInLava()` | crashed-state only (MinimalReflex); live pipeline BLIND — `board.inLethalFluid` exists with zero writers |
| Suffocation | 1.0F **every tick** eye-in-wall, no interval (LivingEntity.baseTick:387) | **instant** — 20 ticks | `isInWall()` | nothing |
| Void | 4.0F every tick below `minBuildHeight - 64` (LivingEntity:1859) | **instant, unanswerable** — nothing rescues below the line | `getY()` vs level min | nothing (planner-side Drop<=3 is the only guard) |
| Cactus | 1.0F/tick on AABB contact (CactusBlock.entityInside) | instant-ish, tiny per-tick | no getter — needs contact check | nothing |
| Fire | 1.0F per second (every 20 ticks), self-limiting at 300 ticks = 15 HP max; extinguished by water/rain/powder snow (Entity.baseTick:483) | **gauged** — seconds, survivable by waiting | `getRemainingFireTicks()` | nothing |
| Drowning | air 300, drain 1/tick, damage 2.0F/s once air <= -20 (Forge LivingDrownEvent) | **gauged** — 300 ticks + 1 s | `getAirSupply()` | SHIPPED (ledger 22) |
| Freezing | freeze +1/tick in powder snow to 140, decay 2/tick; at 140: 1.0F per 40 ticks (LivingEntity.aiStep:2666-2680) | **gauged** — 140 ticks to first damage, slow after | `getTicksFrozen()`, `isInPowderSnow` | nothing (carrier not FREEZE_IMMUNE-tagged, so it can freeze) |
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

Fire is self-limiting (15 HP worst case, 1 HP/s) and self-answering
the moment the bot's ongoing route touches water (extinguish on
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

### F6 - Freezing: gauged, slow, rare; deferred with numbers

140 ticks of budget and 1 HP/2s after — the least urgent gauge in
the catalog, and powder snow does not exist in the current test
scenarios. Sense-only rides F1 (`freezeTicks`); a LEAVE response is
mission-shaped (same as lava's exit) and can share that machinery
if F2's escape mission lands. Deferred.

### F7 - Starvation: the shape is pre-ruled, the carrier is not

Confirmed Player-only (FoodData on Player.java:168; zero
references elsewhere). When the 0007 carrier ruling lands a
player-side facade, hunger slots in as the exact parallel of
ENGAGE: sense `foodData.needsFood()` on the board, rule
EAT_WHEN_HUNGRY at routine urgency (eating is never
preemption-class), one-tick handoff to an eat mission (inventory
select + Stage 3 UseItem). Nothing to build before 0007 Phase 1/4.

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
| LAVA(?) + FREEZE | LAVA | 4 HP/tick does not negotiate |
| SUFFOCATE(?) + any | SUFFOCATE | 1 HP/tick, and movement may worsen it |
| FIRE + SURFACE | SURFACE, then fire dies in water | water extinguishes |

(Names with (?) are proposals, not shipped rules.)

## 5. Proposed sequencing (for ruling)

1. **Now, survival-gate residue**: F1 (vitals pass) + F2's reflex
   half (live-pipeline lava ASCEND) — S each, closes the only
   dead-field dishonesty in the board and the fastest killer's
   blind spot. F2's escape mission and F4's suffocation rule are
   the two candidates for the next mechanism slot after that.
2. **With 0007 Phase 1**: F7's sensing groundwork.
3. **Deferred with numbers recorded**: F3 (no rule), F5 (no rule),
   F6, F8 pending ruling.

## 6. Decision log

| # | Question | Ruling |
|---|---|---|
| D1 | Vitals sensing pass (F1)? | OPEN |
| D2 | Live-pipeline lava: ASCEND-only vs + escape mission (F2)? | OPEN |
| D3 | Suffocation FREEZE rule at 115 (F4)? | OPEN |
| D4 | Widen MinimalReflex with an air check (F8)? | OPEN |
| D5 | Fire: sense-only, no rule (F3)? | OPEN |
