---
title: Horse / ride-back temper taming (mount-and-steer capability)
last_verified: 2026-09-02
covers:
  - doc/architecture/player-behavior-RE.md
  - src/main/java/com/mcbot/mcbotserver/core/tame/TameFoodCatalog.java
  - src/main/java/com/mcbot/mcbotserver/core/process/TameProcess.java
  - src/main/java/com/mcbot/mcbotserver/api/actor/Intent.java
status: open
---

# Problem

The `taming.chain` face covers item-press taming (wolf bone, cat
cod/salmon, parrot seeds) but deliberately excludes horses, donkeys,
mules, and llamas. Those species tame through a different mechanic:
the rider mounts the animal, the temper value rises on each ride
attempt, and a successful tame fires when temper exceeds the species
threshold (horse 0..100, tempered by food; the ride-bucking loop).
Implementing this requires capabilities the device does not have:

1. **Mount** — the bot must ride the entity (`Player.startRiding`),
   which needs a new intent or a boundary-D verb beyond
   `InteractEntity` (an empty-hand right-click on an untamed horse
   mounts it, but the current `InteractEntityExecutor` routes through
   `Player.interactOn` which does mount — the gap is the *steer* and
   *dismount* lifecycle, not the press itself).
2. **Steer** — while mounted, the body's MOVE channel drives the
   horse, not the bot. The current `BindingActor` maps MOVE intents
   to the bot body; a mounted state would need to redirect them to
   the vehicle. No vehicle-aware intent routing exists.
3. **Temper feedback** — the tame verdict is not a snapshot bit flip
   visible mid-ride; the horse bucks (dismounts) on failure and the
   temper persists across attempts. The process would need to detect
   bucking events and re-mount, which is a state machine the current
   `TameProcess` (sighting-driven, single-press cadence) cannot
   express.
4. **Food tempering** — golden carrot and apple raise temper without
   mounting; this is a secondary optimization path, not the core
   mechanic, but a complete horse-tame face would include it.

The `TameFoodCatalog.isTameable()` returns false for horses, so
`TameProcess` refuses them as `NOT_TAMEABLE` — behaviorally correct
for the current face, but it conflates "not item-tameable" with "not
tameable at all." A horse-specific face would need its own process
and behavior.

# Ruling

Deferred. The item-press `taming.chain` face is the correct first
slice; ride-back taming is a distinct capability that depends on
mount/steer infrastructure not yet present. The player-behavior RE
section 9 records the mechanic inventory (temper ranges, food
items, buck behavior) so the future face has a verified reference.
Reopen when either (a) a mount/steer intent lands (the prerequisite
capability), or (b) a harness use case requires horse domestication
and the Stage review lifts the boundary.

# Contract

- `TameFoodCatalog` MUST NOT include horse / donkey / mule / llama
  as item-tameable; the `NOT_TAMEABLE` refusal for those species is
  the correct behavior for `taming.chain`.
- `EntitySnapshot.tamed` remains valid for horses (a tamed horse
  carries an owner and the `TamableAnimal` bit); an `ALREADY_TAMED`
  refusal on a tamed horse is truthful even though `taming.chain`
  cannot tame an untamed one.
- A future horse-tame face MUST be a separate capability id (e.g.
  `taming.ride_back`), not an extension of `taming.chain` — the
  process shape (mount-steer-buck loop) is fundamentally different
  from the sighting-press cadence.
- The mount intent, when it lands, MUST go through boundary D as a
  new verb or an `Intent` variant; mounting via a raw
  `InteractEntity` empty-hand press is acceptable as the trigger but
  the steer/dismount lifecycle needs explicit modeling.

# Deferred-with-reopen

Reopen criteria (any one):
- A mount/steer capability lands in the intent vocabulary (the
  hard prerequisite).
- A harness consumer explicitly requests horse/donkey/mule
  domestication and the Stage review approves lifting the boundary.
- The `taming.chain` face's `NOT_TAMEABLE` refusal for horses is
  found to confuse harness users (UX signal that a separate face or
  a more specific refusal reason is needed).

Mechanic inventory (verified against decompiled 1.20.1, recorded in
player-behavior-RE.md section 9):
- Horse temper: 0..100, `random.nextInt(100) < temper` succeeds;
  golden carrot +4, golden apple +10, hay bale +heal (no temper);
  sugar/wheat/apple heal and grow baby, no temper.
- Untamed horse: empty-hand right-click mounts; bucking dismounts
  on failure; hearts particles on success.
- Donkey/mule: same mechanic, lower max temper.
- Llama: tamed by repeated mounting, temper 0..100, no food
  tempering; carpet is decor, not a tame item.
