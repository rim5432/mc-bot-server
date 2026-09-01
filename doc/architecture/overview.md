---
title: Architecture Overview
last_verified: 2026-09-02
covers:
  - src/main/java/com/mcbot/mcbotserver/McBotServer.java
  - src/main/templates/META-INF/mods.toml
  - build.gradle
---

# Architecture Overview

MC Bot Server is a **mechanical bot device** for Minecraft 1.20.1 (Forge).
Its defining constraint is a clean separation between two layers:

```
+---------------------+        +--------------------------+
|   Device Layer      |        |   LLM Harness (external) |
|   (this mod)        | <----> |   planning / reasoning   |
|   perception,       |  JSON  |   stateless, no MC deps  |
|   actuation, state  |        +--------------------------+
+---------------------+
```

## Principles

1. **Device layer stays dumb.** The mod exposes mechanical operations
   (move, dig, place, inspect) and raw state snapshots. It never interprets
   intent.
2. **LLM harness lives outside the JVM.** All model calls, prompt assembly,
   and conversation memory belong to an external process talking to the mod
   over a socket/HTTP endpoint. The mod ships zero AI dependencies.
3. **Everything is event-sourced.** Bot actions and world observations are
   logged as discrete events so a harness can replay and reason about them.

Loaded terms (dumb, pure, one seam, reflex bypass...) are pinned in the
[glossary](glossary.md) - the definition sentence is the contract, the
term name alone has misled external review before.

## Entry points

| What | Where |
|---|---|
| Mod entry class | `McBotServer.java` (`@Mod("mcbotserver")`) |
| Mod metadata template | `src/main/templates/META-INF/mods.toml` |
| Build wiring (runs, mappings) | `build.gradle` |

## Runtime layering

Four tiers, strictly ordered per tick (details in ADR-0002 / ADR-0003):

```
+--------------------------------------------------------------+
| SurvivalReflexLayer   sensor -> blackboard -> rule table      |
| hard constraints; preempts everything from outside the        |
| arbiter; same-tick response (ADR-0003)                        |
+--------------------------------------------------------------+
| TaskArbiter           BotProcesses compete, winner-take-all   |
| GotoProcess / DefendProcess (+ reflex rescue missions)        |
| (ADR-0002)                                                    |
+--------------------------------------------------------------+
| Behaviors             always-on services                      |
| PathingBehavior, CombatBehavior, IdleLook                     |
+--------------------------------------------------------------+
| Actor                 movement input, single-writer handover  |
+--------------------------------------------------------------+
```

Generalization note: future non-survival hard constraints (friendly
fire, server rules) follow the same reflex pattern as separate guardians
(Phase 3, see ADR-0003 scope guard).

## Interaction model (device <-> harness)

The seam is the frozen wire verbs (`/bot status|goto|dig|mine|cancel|
stop|events|reset` plus the synchronous read family — decision 28);
every harness drives the same seam. The reference harness
(`tool/harness/mc.py`) is a Unix shell over it, and the model is
now core architecture: world state is a **path namespace**
(`/blocks` `/entities` `/player` `/recipes` `/stations`), multi-tick
actions are **jobs** (write submits -> taskId -> wait joins -> the
terminal event is the verdict and the exit code), loops needing
engine-speed feedback run **device-side as processes** (MineProcess)
while the harness orchestrates between tasks with skills over six
verbs. Binding law: boundaries.md decision 33; canonical detail:
[harness-interaction.md](harness-interaction.md); executable queue:
issue 0015.

## Current status

Live status lives in three places that stay honest by construction —
counts, dates, and queue positions are not restated here because they
rot the moment the next ruling lands:

- **Work queue**: [workplan.md](../guide/workplan.md) — items ordered by
  dependency; the first item with all blockers checked off is the current
  effort. Shipped items are removed; history lives in git commits.
- **Latest rulings**: [ledger.md](ledger.md) tail — every architectural
  verdict, append-only, newest at the bottom. The
  [decision index in boundaries.md](boundaries.md#decision-ledger-binding)
  resolves every `decision N` citation.
- **Engine-verified surface**: the gametest suite registered under
  `src/main/java/com/mcbot/mcbotserver/gametest/`; its exact inventory is
  pinned by `hygiene.GametestInventoryCheck` (code-health H-R5), not
  restated as prose.

Reverse-engineered vanilla player-behavior truths are collected in
[Player Behavior RE](player-behavior-RE.md).

Related: [Boundary Contracts & Ledger](boundaries.md),
[Work Plan](../guide/workplan.md),
[Build & Run Guide](../guide/build-and-run.md),
[ADR-0002 capability/arbiter](../decisions/0002-capability-model-task-arbiter.md),
[ADR-0003 reflex layer](../decisions/0003-reflex-layer-preemption.md)
