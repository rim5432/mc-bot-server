---
title: HungryProcess - food acquisition planner with self-contained initial strategies
last_verified: 2026-08-30
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/api/reflex/ThreatBlackboard.java
  - src/main/java/com/mcbot/mcbotserver/core/process/
status: open (forage + hunt + fish COMPLETE 2026-08-27: ASSESS orders forage > fish > hunt - fish via FishBehavior over WorldView.getBobbers with the dip-not-nibble bite watch, section 7's use-item dependency answered by the BotPlayerFacade use chain; ledgers 34/35/37. REMAINING: engine verification of the fish pair (cast + bobber spawn) rides the staged gametest batch)
related:
  - doc/architecture/issues/0008-extreme-scenario-survival-framework.md
  - doc/architecture/issues/0007-player-parity-interaction.md
  - doc/architecture/issues/0009-block-capability-dig.md
  - doc/decisions/0002-capability-model-task-arbiter.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/guide/workplan.md
---

# Issue 0010: HungryProcess - food acquisition planner

## 1. Scope and lineage

Moved from issue 0008 F7. The original F7 ruling was narrow:
"sense `foodData.needsFood()`, rule EAT_WHEN_HUNGRY at routine urgency,
one-tick handoff to an eat mission." That covers the **consumption**
side only. The user ruling 2026-08-25 upgrades the scope: the bot
must also **acquire** food when its inventory is empty, and the
initial acquisition strategies must be self-contained — no harness
dependency for the common cases. Harness escalation is the fallback,
not the default.

This issue designs `HungryProcess`: a Process-tier planner that
monitors food state, selects an acquisition strategy based on the
immediate environment, executes it via existing Process/Behavior
machinery, and escalates to the harness only when every self-contained
strategy is infeasible or has failed.

Non-goal: cooking, farming, breeding, village raiding. These are
harness-tier decisions (setup cost, waiting time, multi-step
crafting). They are recorded as escalation targets in §6, not
implemented here.

## 2. Why acquisition is harder than consumption

Consumption (auto-eat) is a single reflex: hunger below threshold ->
select best food in inventory -> hold use for 32 ticks. The
acquisition problem has four coupled dimensions that no reflex can
solve:

1. **Source diversity**: passive mobs, berry bushes, ground drops,
   fishing, village crops — each needs different execution machinery.
2. **Environment dependency**: a desert has no animals, a mountain
   has no water, an ocean has no berries. The bot must adapt to
   where it spawns.
3. **Yield vs setup cost**: hunting is fast but depletable; farming
   is sustainable but needs 8+ minutes of real-time growth. The
   initial strategies must pick the fast path.
4. **Failure modes**: animals flee, fish don't bite, berry bushes
   are sparse. The planner must detect failure and try the next
   strategy before starvation kills the bot.

Professional projects confirm this depth:
- **AIBot** (mc_aiplayer, 30K LOC) implements five food paths
  (hunt-cook, farm-bread, forage, fish, village-raid) and measures
  food-chain reliability at 8/10 random seeds — the remaining 2/10
  are spawns with no animals, no water, and no village in range.
- **Agent Swarm** uses role specialization (Blade hunts, Flora
  farms) but reports "food supply can't sustain 5 bots" as an open
  issue — even a multi-bot swarm struggles with sustainable supply.
- **Baritone** only does harvest+replant (`FarmProcess`), explicitly
  leaving cooking and crafting to the user.
- **Voyager** composes skills (`killOnePig` -> `eat`) via LLM
  planning — no deterministic acquisition strategy at all.

The design bet of this issue: the **initial three strategies**
(forage, hunt, fish) cover the vast majority of spawn
environments, and the harness escalation handles the long tail.

## 3. Vanilla mechanics (decompiled 1.20.1)

### 3.1 FoodData (Player-only, confirmed Player.java:168)

Three coupled variables, not one hunger bar:

| Variable | Range | Role |
|---|---|---|
| `foodLevel` | 0-20 | visible hunger bar; <=0 causes 1 HP/80 ticks starvation damage |
| `saturationLevel` | 0-foodLevel | invisible buffer; depleted before foodLevel drops |
| `exhaustionLevel` | 0-40 | accumulates from activity; every 4.0 exhaustion -> -1 saturation (or foodLevel) |

`eat(nutrition, saturationModifier)`:
```
foodLevel  = min(nutrition + foodLevel, 20)
saturation = min(saturation + nutrition * saturationModifier * 2.0, foodLevel)
```
Saturation is capped at current foodLevel — eating high-saturation
food at partial hunger wastes saturation.

Natural regen: foodLevel>=20 + saturation>0 + hurt -> heal every
10 ticks; foodLevel>=18 + hurt -> heal 1 every 80 ticks. The 18
threshold is why professional bots trigger eating at 14-16, not 0.

### 3.2 Exhaustion rates (activity-dependent)

| Activity | Exhaustion/tick | Time to lose 1 saturation |
|---|---|---|
| Walking | 0.01 | 400 ticks (20s) |
| Sprinting | 0.1 | 40 ticks (2s) |
| Sprint-jumping | 0.2 | 20 ticks (1s) |
| Swimming | 0.01 | 400 ticks |
| Attacking | 0.1 per hit | n/a |
| Block breaking | 0.005 | 800 ticks |

A sprint-jumping bot loses a saturation point every second. This is
why "just keep walking" is not a viable food strategy — the bot
must minimize high-exhaustion activity while acquiring food.

### 3.3 Key food values (nutrition / saturation)

| Food | Nutrition | Saturation | Source |
|---|---|---|---|
| Cooked Beef | 8 | 12.8 | cow (cooked) |
| Cooked Porkchop | 8 | 12.8 | pig (cooked) |
| Golden Carrot | 6 | 14.4 | crafted (harness) |
| Cooked Mutton | 6 | 9.6 | sheep (cooked) |
| Cooked Chicken | 6 | 7.2 | chicken (cooked) |
| Bread | 5 | 6.0 | wheat x3 (harness) |
| Cooked Cod | 5 | 6.0 | fishing (cooked) |
| Sweet Berries | 2 | 0.4 | bush (forage) |
| Raw Beef | 3 | 1.8 | cow (raw) |
| Raw Chicken | 2 | 1.2 | chicken (raw, 30% hunger effect) |

Cooking multiplier: raw beef 3 -> cooked beef 8 = **2.67x**. But
cooking needs furnace + fuel + 10 ticks per item — recorded as
harness-tier in §6.

## 4. HungryProcess architecture

### 4.1 Position in the tick pipeline

```
BotController.onTick():
  reflexLayer.tick()        -> survival reflexes (lava/drown/etc.)
  arbiter.executeProcesses() -> HungryProcess lives here
  behaviors.tick()          -> execution via Actor claims
  actor.flush()
```

HungryProcess is a **Process** (side-effect-free, outputs
`Directive{Goal, Overrides}`), same tier as `DefendProcess` and
`GotoProcess`. It is NOT a reflex: acquisition takes many ticks and
needs environment scanning, which is Process-class work.

The reflex layer only carries the **signal**: `board.foodLevel` and
`board.saturationLevel` (stamped by the sensor, safe-defaulted to
20/5.0 so a rig that never stamps reads as well-fed). A reflex rule
`EAT_WHEN_HUNGRY` at routine priority (below all survival reflexes,
at or above ENGAGE) does a one-tick handoff: if inventory has food,
submit an `EatProcess`; if inventory has no food, submit
`HungryProcess`.

### 4.2 State machine

```
                 ┌─────────────┐
                 │   IDLE      │  foodLevel >= trigger
                 └──────┬──────┘
                        │ foodLevel < trigger AND no food in inventory
                        ▼
                 ┌─────────────┐
                 │  ASSESS     │  scan environment: mobs? berries? water+rod?
                 └──────┬──────┘
              ┌──────────┼──────────┐
              │          │          │
   viable hunt│   viable forage    │ viable fish
              ▼          ▼          ▼
        ┌──────────┐ ┌─────────┐ ┌────────┐
        │  HUNT    │ │ FORAGE  │ │ FISH   │
        │ (subprocess)│ │(subproc)│ │(subproc)│
        └─────┬────┘ └────┬────┘ └───┬────┘
              │           │          │
              └───────────┼──────────┘
                          │ food acquired?
                    ┌─────┴─────┐
                    │ yes       │ no (all strategies exhausted or failed)
                    ▼           ▼
              ┌──────────┐ ┌──────────────┐
              │ CONSUME  │ │ ESCALATE     │  emit FOOD_STRATEGY_EXHAUSTED
              │ (EatProc)│ │ to harness   │  event, FREEZE, wait
              └──────────┘ └──────────────┘
```

### 4.3 Strategy dispatch (the core planner logic)

ASSESS scans the environment in priority order and picks the first
viable strategy. Priority is by expected time-to-food:

1. **Forage** (fastest): scan for sweet berry bushes / glow berries /
   melon slices within N blocks. If found -> GotoProcess to bush +
   INTERACT (break bush) + collect drops. Zero setup, zero tool
   dependency.
2. **Hunt** (highest yield): scan for passive mobs (cow/pig/sheep/
   chicken/rabbit) within N blocks. If found -> submit DefendProcess
   with target = nearest passive mob (reuses existing combat
   machinery; target classification extended from "hostile" to
   "passive food source"). Collect drops after kill.
3. **Fish** (safest, sustainable): if water source within N blocks
   AND fishing rod in inventory -> GotoProcess to water + hold use
   (cast) + wait for bite + reel. Slow but zero risk and infinite
   supply.

If none are viable -> ESCALATE (§5).

### 4.4 Sub-process handoff pattern

HungryProcess does not execute acquisition itself. It dispatches to
existing Processes via the Arbiter, same shape as the ESCAPE reflex's
rescue mission handoff (ledger 26):

- **Hunt**: `arbiter.submit(DefendProcess(target=passiveMob,
  reason=HUNGER))` — DefendProcess already handles approach, swing
  pacing, LOS checks. The only change is target classification.
- **Forage**: `arbiter.submit(GotoProcess(target=berryBush))` +
  on-arrival INTERACT to break the bush + walk over drops.
- **Fish**: `arbiter.submit(GotoProcess(target=waterEdge))` +
  on-arrival USE item (fishing rod) + wait-for-bite state. This
  needs a new `FishBehavior` or an extension of UseItemBehavior —
  recorded as an open dependency.

After the sub-process completes (mob killed / bush broken / fish
caught), HungryProcess re-enters ASSESS to verify food is now in
inventory, then submits `EatProcess`.

### 4.5 Failure detection and retry

Each strategy has a failure budget:

| Strategy | Failure condition | Budget |
|---|---|---|
| Forage | GotoProcess fails (unreachable) or bush has no berries | 1 attempt, then next strategy |
| Hunt | DefendProcess verdict = TARGET_ESCAPED / TIMEOUT | 2 attempts (different target), then next strategy |
| Fish | No bite in 200 ticks (10s) or no rod | 1 attempt, then next strategy |

After all three strategies fail or are infeasible -> ESCALATE. The
failure budget prevents infinite retry loops (an animal that keeps
running, a bush on a cliff, a pond with no fish).

## 5. The escalation boundary (the key contract)

This is the most important design decision. The line between
"self-contained" and "harness" is drawn by **setup cost and
determinism**:

### Self-contained (HungryProcess owns it)

- **Forage**: walk to a berry bush, break it, collect. No tools,
  no crafting, no waiting. Deterministic if a bush exists in range.
- **Hunt**: walk to a passive mob, kill it, collect drops. Reuses
  combat. Deterministic if a mob exists in range and is reachable.
- **Fish**: walk to water, cast rod, wait for bite, reel. Needs a
  rod in inventory (which the bot may not have — if no rod, fish is
  skipped, not escalated).

### Harness-tier (escalate, do not attempt)

- **Farming**: needs hoe, seeds, water source, tilled land, 8+
  minutes of growth, crafting table for bread. Multi-step,
  long-wait, tool-dependent. The harness decides whether to invest.
- **Cooking**: needs furnace, fuel, 10 ticks/item. The harness
  decides whether raw meat is worth cooking or acceptable raw.
- **Breeding**: needs wheat/seeds, two animals, waiting for babies.
  Long-term sustainability, not immediate survival.
- **Village raiding**: needs pathfinding to a village (may be
  hundreds of blocks away), breaking crops, reputation cost.
- **Long-distance foraging**: beyond the scan radius, the harness
  must decide whether to travel or set up a farm.

### Escalation protocol

When all self-contained strategies are exhausted:

1. HungryProcess emits a `FOOD_STRATEGY_EXHAUSTED` event (Boundary D
   event stream) with context: current foodLevel, saturation,
   nearby entity summary, nearby food-block summary, which
   strategies were tried and why they failed.
2. The bot FREEZEs (stops high-exhaustion activity to slow hunger
   loss) — same shape as `FreezeOnLowHealthRule`.
3. The harness receives the event and may: submit a farming task,
   direct the bot to a known food source, provide food via inventory
   manipulation, or accept the starvation risk.
4. If the harness does nothing and foodLevel hits 0, the bot
   starves — this is honest failure, not a silent bug.

The escalation event is **not** a command request. It is a status
broadcast. The bot does not wait for a harness response before
freezing — freezing is the bot's own damage-mitigation reflex.

## 6. Open decisions (D1-D6)

### D1 - Trigger threshold

What foodLevel activates HungryProcess? Options:
- **16** (recommended): leaves 4 points of headroom for the
  acquisition attempt; above the 18 natural-regen threshold loss
  zone but not so high that the bot is constantly hunting.
- **14** (baritone-ts default): more conservative, leaves less
  margin but fewer interruptions.
- **18**: most aggressive, never loses regen capability, but
  hunting/foraging interrupts tasks constantly.

Recommendation: 16. Rationale: this bot does high-exhaustion
activity (sprint-jump pathfinding, mining) so hunger drops faster
than a walking player. 16 gives ~80 ticks of margin at sprint-jump
intensity.

### D2 - Scan radius

How far does ASSESS look? Options:
- **16 blocks**: fast scan, but may miss nearby food in complex
  terrain.
- **32 blocks** (recommended): matches the existing threat scan
  radius (LevelThreatSensor); one scan pass covers both threats and
  food.
- **64 blocks**: more coverage but slower; may path to food through
  dangerous terrain.

Recommendation: 32. Reuses the existing scan infrastructure.

### D3 - Hunt target classification

DefendProcess currently targets hostiles only. Extending to passive
mobs requires:
- A new `TargetCategory` (FOOD_SOURCE vs HOSTILE) or a reason field
  on the process.
- The combat behavior should not require a weapon (passive mobs
  don't fight back — bare hand is fine).
- After kill, the bot must walk to the drops (DefendProcess
  currently ends on target death/escape; drop collection is a
  follow-up GotoProcess).

This is the largest implementation dependency. Recorded as blocking
on the combat subsystem's target classification.

### D4 - Fishing rod prerequisite

If the bot has no fishing rod, the FISH strategy is skipped. Should
the bot craft a rod (3 sticks + 2 string) as part of HungryProcess?
- **No** (recommended): crafting is harness-tier. The bot uses what
  it has. If no rod, forage/hunt only.
- **Yes**: extends self-contained coverage but needs crafting table
  + inventory management + string acquisition (killing spiders),
  which is itself a sub-problem.

Recommendation: No. Rod crafting is a harness decision.

### D5 - Raw meat consumption

After a successful hunt, the bot has raw meat. Should it eat it raw
or wait for cooking?
- **Eat raw immediately** (recommended for initial strategies): raw
  beef gives 3 nutrition (vs 8 cooked), but it stops starvation.
  Cooking is harness-tier (§5). The bot's job is to not die, not to
  optimize nutrition.
- **Wait for cooking**: better nutrition but needs furnace + fuel,
  which the bot may not have. Starvation risk while waiting.

Recommendation: Eat raw. The consumption-side food selection
(BetterFood-style gap-aware) can prefer cooked food if both are in
inventory, but raw is always acceptable when hungry.

### D6 - Priority vs combat

If the bot is hungry AND a hostile is nearby, which wins?
- **Combat first** (recommended): a dead bot doesn't eat. The
  survival reflex ladder already puts ENGAGE (combat) at priority
  90; HungryProcess should be at routine priority **below** ENGAGE,
  so combat preempts hunger. After combat, if still hungry,
  HungryProcess resumes.
- **Hunger first**: flee from combat to find food. This is the
  "flee while low health" pattern (haksnbot-guts: health<=12 ->
  flee), but it only applies at very low health, not general
  hunger.

Recommendation: Combat first. Hunger is routine; combat is
reactive. The bot fights its way out, then eats.

## 7. Dependencies and sequencing

| Dependency | Source | Status |
|---|---|---|
| Player carrier (FoodData exists) | 0007 Phase 1 | blocked |
| Inventory read (food in inventory?) | 0007 Phase 1 | blocked |
| UseItem behavior (eat, fish) | 0007 Phase 3 | exists (facade use chain, 2026-08-27) |
| Passive mob target classification | combat subsystem (0009?) | design needed |
| Ground item collection | WorldView entity scan + Goto | design needed |
| Berry bush block detection | WorldView block scan | exists (block trait system) |
| Fishing rod detection | inventory read | exists (InventoryView hotbar scan) |

Minimum viable slice (after 0007 Phase 1): forage only. No combat,
no fishing — just scan for berry bushes, walk to them, break them,
collect. This proves the planner loop and escalation with the
smallest dependency set.

## 8. What this issue explicitly does NOT do

- No cooking, no furnace interaction.
- No farming, no crop planting/harvesting.
- No breeding, no animal husbandry.
- No village interaction, no trading.
- No food storage / chest management.
- No LLM-driven strategy selection — the initial three strategies
  are deterministic and priority-ordered.
- No dynamic strategy learning — if a strategy fails, try the next
  one; no memory of "this area has no animals."

All of the above are harness-tier or future-issue territory. This
issue's boundary is the **initial self-contained acquisition loop**:
scan -> pick -> execute -> eat -> escalate if all fail.
