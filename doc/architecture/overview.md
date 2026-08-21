---
title: Architecture Overview
last_verified: 2026-08-22
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
| MineProcess / FollowProcess / DefendProcess ... (ADR-0002)    |
+--------------------------------------------------------------+
| Behaviors             always-on services                      |
| PathingBehavior-equivalent, LookBehavior, CombatBehavior      |
+--------------------------------------------------------------+
| Actor                 movement input, single-writer handover  |
+--------------------------------------------------------------+
```

Generalization note: future non-survival hard constraints (friendly
fire, server rules) follow the same reflex pattern as separate guardians
(Phase 3, see ADR-0003 scope guard).

## Current status

Stage 0 complete and review-hardened: the full four-tier pipeline
(reflex -> arbiter -> behaviors -> actor flush) runs offline against
mocks behind 44 layer-1 tests, with the boundary-D command / event /
state channels frozen and gated. Stage 1's vertical slice is wired
in-engine: a custom mob carrier (entity-binding spike, see workplan)
receives Actor claims through the adapter package, `/botspawn`
assembles a full pipeline around one body, and `/goto` drives it.
Still missing: the in-engine gametest suite (blocked on the 1.20.1
structure-template question) and the external harness transport.

Related: [Boundary Contracts & Ledger](boundaries.md),
[Work Plan](../guide/workplan.md),
[Toolchain Reference](../reference/toolchain.md),
[Build & Run Guide](../guide/build-and-run.md),
[ADR-0002 capability/arbiter](../decisions/0002-capability-model-task-arbiter.md),
[ADR-0003 reflex layer](../decisions/0003-reflex-layer-preemption.md)
