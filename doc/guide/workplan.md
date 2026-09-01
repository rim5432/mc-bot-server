---
title: Work Plan (effort-sized checklist)
last_verified: 2026-09-02
covers:
  - doc/architecture/boundaries.md
  - doc/decisions/0004-tick-pipeline-actor-channels.md
  - doc/decisions/0005-tick-pipeline-exception-policy.md
---

# Work Plan

Ordered by dependency, sized by relative effort: S < M < L < XL.
No item depends on anything below it. Check items off in place;
do not start an item before its blockers are checked.

**Shipped items are removed from this file when they land; their
history lives in git commits and the ledger. This file holds only
OPEN / IN_PROGRESS items and deferred queues.**

## v1 convergence criteria

Inherited from the retired function-map capability envelope
(2026-08-29); citations of "criterion N" in this file mean these.

1. Every shipped item has offline tests plus in-engine gametests; no
   red suite at commit time.
2. A programmatic driver (no LLM) keeps one body alive >= 10 minutes
   of mixed day/night overworld using only boundary-D verbs,
   surviving at least one reflex preemption and one failed-task
   recovery (the survival-gate rehearsal below).
3. Any capability a harness might assume that is not shipped is
   answered by a row in the deferred queues or an issue, never by
   silence.

## Stage 2 closeout follow-ups

RESOLVED entries (1, 2, 3, 8, 9) moved to archive. Open items:

4. OPEN 2026-08-23: RCON console bridge shipped (boundary-D command
   surface + tool/rcon.py session ledger) - the first external
   transport. Acceptance = the survival-gate 10-minute rehearsal
   below; its verdict closes this entry.
5. OPEN (vocabulary landed 2026-08-23): swim vocabulary +
   PLAN_WALL_CLOCK_MS 50->200 after the lake stranding; verified by
   BasicMovesSwimTest offline + crossesWaterTrench in-engine.
   Residue: datapack water/lava trait JSON supersedes the code
   baseline (bookkeeping item in the survival-gate section).
6. OPEN (escalator landed 2026-08-24): NoPathEscalator offline-gated
   (boundaries.md decision 21) after verif-1 burned the full budget
   on a floating goal. Acceptance = live rerun of that scenario:
   NO_PATH within seconds, noPathWitnesses climbing to 3 first.
7. OPEN 2026-08-25: gameTestServer shares run/world with runServer,
   so a live server blocks gametests via DirectoryLock (observed as
   an hour-long deadlock). Fix direction: dedicated gameDirectory
   (run/gametest) or serialize the two tasks; until then they cannot
   coexist.

## Pre-Stage-3 survival gate - basic survival capability

Unlocked by the Stage 2 backlog; blocks all Stage 3 work. The gate
exists because convergence criterion 2 (10-minute unattended
survival) was red at construction: the 2026-08-24 live session
lost the body twice unattended - drowned in the spawn lake after
~20 min and killed in a night cave while idle - and the carrier
had no health-recovery path at the time. All three core defenses
(air reflex, idle combat, passive regen) are now landed (archive);
the remaining gate items are the 10-minute acceptance rehearsal,
the H-R4 convergence pass, and bookkeeping.

- [ ] M  10-minute acceptance rehearsal through the RCON bridge to
         a recorded verdict in tool/sessions/. Programmatic driver,
         boundary-D verbs only, mixed day/night overworld, at
         least one reflex preemption and one failed-task recovery
         observed. Closes criterion 2 + closeout follow-ups 4 and
         5 + issue 0005's live acceptance run in one stroke.     [dep: air, idle, recovery]
- [x] S  H-R4 wire-key convergence pass: canon tables reconciled
         to the shipped surface (/actions/ + /nearby/ roots,
         console-root enumeration, admin row, stream-truth wire
         keys, STATE_PUSH wording, /player/menu citation);
         component->wire-key mapping documented at boundaries.md +
         BotStateJson and pinned by
         WireVocabularyGateTest.stateJsonWireKeysStayFrozen;
         finishTask now refuses reason-less TASK_FAILED. Canon-
         ahead-of-CLI gaps stay queued in 0015 (events --only,
         ls /).                                      [dep: none]
- [ ] S  Bookkeeping residue: let the datapack water/lava trait
         JSON supersede the code baseline (closeout follow-up 5
         residue). Issue 0004's disposition settled 2026-08-27:
         archived with its open reserves preserved as the
         movement-vocabulary seeds below.                       [dep: none]

## Stage 3 - player parity (unlocked only by the survival gate)

Design review: doc/architecture/issues/0007-player-parity-
interaction.md (raw analysis distilled 2026-08-24; vanilla-API
claims spot-checked against the decompiled tree). Every item that
grows a frozen surface (Intent kinds, Actor channels, the carrier
decision, tick ordering) is backlog text until the Stage 3 review
ratifies it - per AGENTS.md the stage review is the only place the
freeze lifts.

Phases 1 (inventory sense + basic interaction), 2 (menu system +
crafting-table disclosure), and 3 (crafting automation) are fully
landed (archive). Remaining:

- [ ] XL Phase 4 - full player behaviour parity. Acceptance:
         unattended survival loop (mine -> craft -> equip ->
         fight -> eat). Decomposed 2026-08-27 after surface recon:
         eat/drink orchestrate existing Intent kinds (SelectSlot +
         Use), so the loop needs no Intent growth; the frozen-surface
         crux is hunger sense, which ledger entry 32 pins behind the
         0010 foodData lifecycle ruling (0007 6.3 carrier Path A/B).
         Items tagged [review] stay backlog text until the Stage 3
         review rules; the rest grows only boundary-D vocabulary,
         which the treaty allows.                     [dep: P3]

  - [ ] M  Wear slice (armor): `menu wear` synchronous verb over
           menu transactions (armor clicks already landed, pinned
           by equipsArmorThroughMenuClicks) + core wear planner
           with an adapter-backed armor classification catalog.
           NOTE: the `equip` verb name is taken (0013 hotbar
           selection), so the armor verb is `wear`; canon root
           table row + H-R4 friction protocol + harness-interaction
           audit reopen (0015 section 4) land with it. Offline
           planner gates + wear gametest.           [dep: H-R4 pass]
  - [x] S  Combat loadout (landed 2026-08-27, vanilla-mechanics
           dossier first): mob melee has no attack-speed scaling or
           crits, so per-hit damage is the whole ranking (axe beats
           same-tier sword) - api WeaponCatalog + adapter
           VanillaWeaponCatalog (generic registry scan of MAINHAND
           ATTACK_DAMAGE modifiers) + CombatBehavior holds the best
           hotbar weapon via one SLOT claim. Root fixes ride along:
           BotBodyEntity equipment mirror (getItemBySlot/setItemSlot
           over the container, so LivingEntity.detectEquipmentUpdates
           applies weapon AND armor modifiers automatically),
           ATTACK_DAMAGE attribute base 3.0 (zombie scale, fists
           unchanged), MeleeResolver routed through
           Mob.doHurtTarget (enchant bonus, knockback, Fire Aspect,
           shield-disable for free). Offline:
           CombatBehaviorLoadoutTest (5 cases); gametest
           equipmentMirrorFeedsAttributes staged pending engine
           (live server holds run/world, H-R5).    [dep: none]
           AMENDED 2026-08-29 (725e528): MeleeResolver no longer
           routes through Mob.doHurtTarget - the chain is inlined
           (performMeleeAttack) to carry the 1.5x crit multiplier
           and the sword sweep, the item-derived attack cooldown
           gates damage with a ready-gated reset, and the body
           gained ATTACK_SPEED/ATTACK_KNOCKBACK bases. Weapon
           ranking is DPS-shaped again; the inlined chain is the
           same deviation class as DigExecutor's break sequence.
  - [x] M  Hunger sense + consumption (landed 2026-08-27, user GO
           = Path A ruling, ledger 34): carrier-owned FoodData with
           verbatim tick clone (free regen floor retired - regen is
           food-driven now), foodLevel into BotState via the H-R4
           four-piece (wire key food), ThreatBlackboard
           foodLevel/saturationLevel/foodSlot, EAT_WHEN_HUNGRY rule
           at 85 (D1 trigger 16, D6 combat-first) executing the
           ReflexAction.EAT select-and-use pair, adapter eats on the
           USE rising edge via finishUsingItem + FoodData.eat.
           Offline: EatWhenHungryRuleTest + gate updates; gametests
           eatsWhenHungry + food-driven regen rewrite staged pending
           engine (live server holds run/world).
  - [x] M  Forage strategy COMPLETE (2026-08-27, ledger 35): the
           third reflex seat landed with the reserved promotion -
           ReflexMissionSeat unifies the fight/rescue mirror classes
           (EngageReflexGateTest stayed green through the refactor),
           FORAGE action + ACQUIRE_FOOD_WHEN_HUNGRY (80, the eat
           rule's exact complement, exclusivity-pinned) hands off to
           HungryProcess through it.
  - [x] M  Hunt strategy (landed 2026-08-27): D3's classification
           is a food-types set (cow/pig/sheep/chicken/rabbit) on
           HungryProcess; the hunt phase engages the nearest prey
           with the DefendProcess directive vocabulary (GoalNear +
           Attack - CombatBehavior kills, no weapon required); a
           vanished prey walks its last cell for pickup (GroundPickup)
           and the inventory check closes the loop; no-bush-no-prey
           is FOOD_STRATEGY_EXHAUSTED.   [dep: none]
  - [x] M  Fish strategy COMPLETE (2026-08-27, external capability
           review queued it; the dip-not-nibble bite watch rides
           FishBehavior + WorldView.getBobbers; HungryProcess grows
           the FISH phase between forage and hunt - forage > fish >
           hunt by 0010 section 4.3 as implemented). Offline:
           FishBehaviorGateTest 5 cases + the fish-vocabulary transit
           pin; the engine pair (cast + bobber spawn) rides the
           staged gametest batch.   [dep: none]
  - [x] S  Farming till_soil face SHIPPED (2026-09-02): hoe
           right-click through InteractBlockExecutor triggers
           HoeItem.useOn -> IForgeBlock.getToolModifiedState(HOE_TILL);
           grass_block/dirt/dirt_path -> farmland, coarse_dirt -> dirt.
           Forge 1.20.1 guard is above().isAir() only (vanilla
           onlyIfAirAbove clicked-face check is bypassed by the
           Forge hook). 3 gametests: dirt till, grass_block till,
           covered-block no-till. capability face farming.till_soil
           GREEN. fishing.bite_watch face formalized from existing
           FishBehavior code (2 gametests, GREEN).
  - [ ] M  Farming plant + grow + harvest faces: plant_seeds
           (ItemNameBlockItem.place on farmland, CropBlock.mayPlaceOn
           gate), crop_growth (randomTick light>=9, bonemeal
           performBonemeal), harvest_mature (AGE==7 left-click dig
           drops seeds + crop). Each needs a gametest anchor before
           promote.                       [dep: till_soil]
  - [ ] M  Water fish hunt face: acquisition.water_fish — melee
           attack on AbstractFish in water (cod/salmon/tropical_fish
           -> raw fish drops; pufferfish -> poison). Bucketable
           bucketMobPickup is an alternative acquisition path.
           [dep: combat.melee (existing)]
  - [ ] M  Loop acceptance: unattended mine -> craft -> equip ->
           fight -> eat driven through boundary-D verbs only,
           recorded verdict in tool/sessions/ (wait exit codes
           landed; the 0015 live chain-halt check rides here).
                                    [dep: equip, loadout, HungryProcess]
  - [x] M  Bow / ranged combat COMPLETE (2026-08-27: the user
           sequenced it past this review gate by accepting the
           capability plan; ledger 37 amends decision 14 - a held
           USE draw charges and the falling edge releases). Offline:
           CombatRangedGateTest 5 cases; draw-release onto a live
           target rides the staged gametest batch.
  - [x] M  Remaining menu kinds SHIPPED (2026-08-28: the slot-role
           series db96778..cf99695 landed anvil, brewing stand,
           grindstone, stonecutter, loom, cartography, smithing,
           enchanting + button clicks, beacon effects, merchant
           (villager) and horse inventory via entity-backed opening).
           Offline: MenuPlannerCountedRoleTest family + wire gates;
           engine scenarios ride the pooled gametest batch.
  - XP sense, offhand, drink/place Use variants: stay 0007 6.1
    backlog until the review sequences them.

  Stage 3 review agenda (the freeze-lifting sitting, 0007 + 0010):
  carrier Path A facade vs Path B ServerPlayer reopen; foodData
  lifecycle on the never-ticked facade; confirm eat/drink reuse
  SelectSlot + Use with no new Intent kind; menu-family disposition
  (BridgeInventory vs BindingInventory) and the BotController
  crash-policy carve-out ride the same ruling.

- [ ] M  BotController reflex-preemption extraction: route
         routeReflexDecision/preemptAndHold/handoffToSeat/evict/
         resumeParkedMission into a ReflexPreemption collaborator;
         retires the PMD.GodClass suppression on BotController
         (TCC axis; ruled 2026-08-27 with the eat slice).
                                                         [dep: none]

### Lean-round deferrals (2026-08-26 whole-repo over-engineering audit)

Delegated lean-round rulings; executed items are recorded in the
commit history (goto-migration scaffolding removal, helper
consolidations, drain-lock retirement, resetAt doc-truth fix). The
survivors are deliberate NON-executions, each with its trigger:

- [ ] S  Deterministic clock seam for wall-clock gates:
         AStarWallClockGateTest arms a real 1 ms budget (fast machines
         can flip it); AdoptFreshnessGateTest / PlanWorkerGateTest
         sleep-poll the worker. Inject a deadline/clock into
         AStarPathFinder + PlanWorker.          [dep: none]
- [ ] S  RecipeCatalog.list(offset, limit) / RecipePage ships without
         an offline test - add clamping / ordering coverage before
         or with that commit.   [dep: none]
- [ ] S  Menu-family disposition (BridgeInventory vs BindingInventory
         overlap; BotInventoryMenu thin fork): rides the issue 0010
         BotPlayerFacade ruling - do not churn vanilla-interaction
         adapter code twice.                  [dep: issue 0010 ruling]
- [ ] S  CombatOrder permits exactly Attack: flatten onto Overrides'
         payload at the Stage 3 review - boundary-B Directive shape
         is frozen until then.            [dep: stage 3 review opens]
- [ ] S  InterruptionContext.interruptedTask() written-never-read:
         flatten candidate for the Stage 3 review - boundary-C
         frozen vocabulary, not cuttable outside it.
                                            [dep: stage 3 review opens]
- [x] S  MenuCommands embedded mini-JSON protocol extraction:
         RESOLVED 2026-08-29 without a helper - the Reply merge
         (e2b0330) and the MenuVerbs/InspectOps splits dissolved the
         embedded protocol this row worried about; nothing left to
         extract.                                    [dep: none]
- Verified non-findings kept on purpose (do not re-litigate without
  new evidence): Heuristic interface (decision 7 seam; planned
  climbs/drops supply second suppliers), PresenceLayer / IdleLook /
  BodyPositionSource (BodyPositionSource feeds PathingBehavior +
  CombatBehavior aiming), /bot goto brigadier path (it IS the
  transport mc.py translates onto), parked issue 0003 stays in
  issues/ root (parked is a legal root status).
- Round-2 KEEP rulings: BotController crash-policy extraction
  REJECTED for now - relocation would move the four
  `invariant: see ADR-0005` markers off their write sites; carve-out
  rides the seat wiring after issue 0010 rules on the facade.
  PathingBehavior's four ctors kept (three are valid default pairs).
- Round-3 kept-rulings: EngageReflexGateTest stays whole (~55%
  scaffolding; re-evaluate on a fourth scenario family - counter:
  fight / rescue / forage = 3 seat families today);
  sleep-poll loops stay (the flaky part was the wall-clock budget,
  fixed via the injected AStar clock); MockWorldView terrain motifs
  added opportunistically during future edits, never speculatively.

### Movement-vocabulary seeds (inherited from archived issue 0004)

Issue 0004 archived 2026-08-27 with rulings D1/D2 shipped; these are
its open reserves, none blocking a current stage:

- [ ] M  yya dive / ascend adapter translation for deliberate depth
         control - unlock: first underwater scenario that needs it.
         Requires threading the sneak bit through setDrive and a
         gametest proving travel() consumes yya unrotated.
         UPDATE 2026-08-29: the descent half is real - sneak
         in water maps to downward thrust (applyWaterDescent) and
         the planner drives it through the SwimDown edge with the
         liquid-gated sneak flag (68dec65); ascent predates
         (jumpInFluid). Remaining: a harness-facing deliberate-
         depth story if one is ever demanded.   [dep: none]
- [ ] S  Pose.SWIMMING getDimensions override + pose-aware collision
         predicates so the carrier can crawl 1-block flooded gaps -
         rides issue 0003's pickup.      [dep: issue 0003 un-parks]
- [ ] S  Depth-tiered fluid costs measured by gametest then
         re-derived (shallow ~= walk, surface lane cheap-ish,
         sub-surface expensive); the surface-lane preference falls
         out of the tiers instead of flat Swim cost. [dep: none]
- [ ] L  Construction Movements (pillar-up / bridge / tunnel - the
         retired map's construction GAP rows): ruling D5 sequencing
         holds -
         inventory model -> Place/Break intents -> Movements, and
         Movement.isViable never grows a bot-state parameter
         (MoveGraph injection or adoption-time check instead).
                                            [dep: frozen-boundary lift]
- Sprint-jump candidate F6(3): already shipped via the player-feel
  layer (BindingActor.sprintClearAhead); recorded here so nobody
  re-imports it.

### Interaction-model executable queue (2026-08-27, issue 0015)

The model is law (boundaries.md decision 33) and canon
(doc/architecture/harness-interaction.md); this is its executable
arm. Harness-side only, zero wire change:

- [x] S  0015 queue item 1: wait exit codes - 0 only on
         TASK_COMPLETED, 1 on every other terminal kind (FAILED,
         REJECTED, CANCELLED, DROPPED), 124 timeout; success is
         the only zero (ruling in issue 0015 section 2, canon
         harness-interaction.md 3.4); all five kinds mock-pinned.
                                                          [dep: none]
- [x] S  0015 near-term queue residue: emit_json isatty discipline
         (tty indent, pipe one-line) across all answers; events
         --only rides the wire's narrowing on every poll; ls /
         lists the nine canonical roots; mc help [verb] with
         examples. Seven ResidueQueueTest cases pin the lot
         (106 total).                              [dep: none]

### Deferred infrastructure queue

- [ ] S  EventQueue `JsonlJournal` persistence for cross-restart
         replay (event queue ships in-memory only today);
         inherited from the retired AGENTS.md open questions -
         `resetAt` epoch honesty is already queued as issue 0015
         section 7.                                         [dep: none]

### Capability gaps (inherited from the retired function map)

The retired capability envelope's open rows, kept so nothing silently
dies (2026-08-29):

- [ ] M  Follow / escort and harvest-and-place loops - the demand
         anchors issue 0009 F3/F4 cite.                      [dep: none]
- [ ] S  Multi-step crafting chains (log -> planks -> sticks as one
         request) - the device fills one recipe per call; the harness
         orchestrates chains today.                          [dep: none]
- [ ] M  Multi-target combat and retreat choreography.       [dep: none]
- [ ] S  Climb (ladders / vines) - PARTIAL 2026-08-29: the
         deliberate move landed (BasicMoves.ClimbUp, trait-driven,
         offline-gated by BasicMovesClimbTest, 68dec65) but no
         engine scenario exercises a real ladder ascent yet - the
         gametest is the remaining half.     [dep: none]
- [ ] S  Spatial memory / waypoint persistence across sessions.
                                                             [dep: none]
- [ ] M  Transport beyond the RCON console bridge (MCP / HTTP /
         stdio) - the bot stays blind to the choice.         [dep: none]
- [x] S  Dig-fidelity tail: enchantment-aware dig speed and
         match_tool loot fidelity (landed 2026-08-29, e03e945):
         DigPacing.applyDigSpeedModifiers ports Player.getDigSpeed
         minus the airborne half (efficiency / haste / mining
         fatigue / underwater without Aqua Affinity;
         DigPacingTest-gated), and the break sequence drops
         resources with the held stack as TOOL context plus
         Item.mineBlock durability and vanilla mining exhaustion -
         engine-proven by the pooled batch's dig scenarios
         (52/52).                             [dep: none]
- [x] S  Directed taming (landed 2026-09-02): `/entities/<id>/tame`
         task over the `taming.chain` face - `Intent.InteractEntity`
         on the INTERACT channel riding `Player.interactOn`, the
         `Tame` order as Overrides' third additive component, typed
         refusals (NOT_TAMEABLE / ALREADY_TAMED / NO_TAME_ITEM /
         TARGET_DEAD), the species item map (TameFoodCatalog:
         wolf bone, cat cod/salmon, parrot six seeds), and three
         engine scenarios (tamesWolfWithBones, alreadyTamedWolfFailsFast,
         tameRejectsNonTameableTarget). Horse/ride taming deferred -
         the ride-bucking temper loop needs a mount+steer
         capability (mechanics survey in player-behavior-RE.md
         section 9).                          [dep: none]

Combat envelope honesty (retired map "known limits", unchanged):
melee standoff-kill; creeper trades hits (accepted risk); ranged
engages at bow standoff only when armed (bow+arrows), otherwise
refuses with the threat type; unarmed against any hostile it times
out - the pre-check belongs to the driver.
