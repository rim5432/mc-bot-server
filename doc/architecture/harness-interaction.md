---
title: Harness Interaction Model - the canonical architecture of the bot's command surface
last_verified: 2026-08-27
covers:
  - doc/architecture/boundaries.md
  - tool/harness/mc.py
  - src/main/java/com/mcbot/mcbotserver/adapter/BotCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/MenuCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/WorldCommands.java
  - skills/patrol.py
related:
  - doc/architecture/issues/0011-harness-surface-convergence.md
  - doc/architecture/issues/archive/0012-work-block-usability-and-harness-menu-surface.md
  - doc/architecture/issues/0013-world-interaction-layer.md
  - doc/architecture/issues/0014-mineprocess-composite-mining-task.md
  - doc/architecture/issues/0015-interaction-model-executable-queue.md
---

# Harness Interaction Model

This is the canonical home of the interaction model between the bot
device and its harnesses (RCON CLI today; MCP/HTTP when a non-RCON
consumer appears). It owns the principles, the grammar tables, and
the endgame. The binding ledger summary is boundaries.md decision
33; issues 0011-0014 are the lineage where each rule was earned.
A rule changes here first, then the ledger entry follows - never
the reverse.

## 1. Thesis: two translations, not a compromise

Minecraft flows are slow, stateful, and spatial; Unix tools assume
fast, stateless operations. The reconciliation is not a middle
ground - it is translating the game into the two problems Unix
already solved:

| Game nature | Unix answer | Bot instantiation |
|---|---|---|
| Space (world state) | filesystem | path-addressed nouns: `/blocks` `/entities` `/player` `/recipes` `/stations` |
| Time (multi-tick processes) | job control | `write` submits, taskId is the job id, `wait` joins, the terminal event is the verdict, `events` is the log stream |

Everything else in this document is discipline that keeps those two
translations honest.

## 2. The grammar

Six verbs plus an admin escape hatch, and nothing else ever:

| Verb | Shape | Notes |
|---|---|---|
| `ls` | `ls <path>` | discovery; `ls /` lists the mounted roots |
| `cat` | `cat <path>` | read one thing; local-first where a materialized copy exists |
| `read` | `read <path>` | stream read (events); `cat`/`read` mixups get a typed suggestion, never substitution |
| `write` | `write <path> <value>` | mutate the noun the path names |
| `wait` | `wait <taskId> [--timeout S]` | join a job; exit code is the verdict |
| `events` | `events [--since N] [--only PREFIX] [--follow] [--idle S]` | event drain; peeking never advances the cursor |
| `admin` | `mc admin stop\|reset` | deliberately OUTSIDE the namespace; operator verbs are not harness vocabulary |

Canonical path roots (this table is where they live; a new row
ships with its process issue but lands here - decision 33
superseding decision 28's table-lives-in-the-issue clause):

| Root | Carries | Writer shape |
|---|---|---|
| `/tasks/` | job family: `goto` `dig` `mine`, `<id>` (cat), `<id>/cancel` | value encodes args; receipt is a taskId |
| `/player/` | `health`, `inventory/free`, `menu` (pending 0012 D3) | read-only |
| `/blocks/<x,y,z>` | one cell; volume reads aggregate nearby | endgame write target (section 10) |
| `/entities/` | nearby entity list, distance-sorted | read-only |
| `/recipes/<slug>` | recipe pages, materialized to disk (section 6) | read-only; `dump-recipes` refreshes |
| `/stations/<type>@<x,y,z>/<role>` | workstation sessions | last segment is always a ROLE, never a verb |
| `/events` | the event stream | read via `events` |

Wire-side namespace split (recorded wart, owned by 0013): the task
family registers under `/bot ...` while the synchronous family
registers at the console root (`block`, `entities`, `scan`, `menu`,
`recipes`). The CLI hides the split; the wart is repaired only with
a non-RCON transport that justifies touching the wire.

## 3. Jobs and verdicts

A multi-tick action is a job. The contract:

1. `write /tasks/...` returns `{ok, task, replay}` - the taskId is
   the join handle.
2. The bot emits exactly one terminal event per task:
   `TASK_COMPLETED` or `TASK_FAILED`.
3. **The terminal event is the verdict.** Completed-vs-failed and,
   on failure, the reason must be machine-decidable from the event
   alone (`kind` + `attrs.reason`), because the harness derives its
   exit codes from it. A TASK_FAILED without a machine-readable
   reason is a contract break.
4. `mc wait <id>` exits `0` on COMPLETED, `1` on FAILED (verdict
   event JSON still on stdout), `124` on wait timeout following GNU
   `timeout`'s convention. This is what makes
   `mc wait $id && mc write /tasks/...` mean what a shell reader
   expects.

Timeout duality is deliberate and documented (0012 D5): task
`--timeout` values are SERVER ticks; `wait --timeout` is CLIENT
seconds. Two clocks, never merged.

## 4. The loops divide at engine speed

The single most load-bearing placement rule in the model:

- A loop that needs **per-tick world feedback** lives device-side as
  a process. `MineProcess` (0014) is the exemplar: search, walk,
  aim, dig, collect, repeat - all inside one arbiter-seated
  process, because break progress expires per tick and only the
  device can re-issue claims at that rate. The harness never polls
  the world to drive a loop; it polls the **event stream**.
- **Between-task orchestration** lives harness-side as skills:
  scripts over the six verbs only (patrol.py is the pattern), never
  raw RCON, chained by `wait` exit codes.
- Consequently the CLI never grows control flow - no variables, no
  conditionals, no loops. **The shell is the language.** The moment
  mc.py needs an `if`, that logic belongs in a skill file or a
  device-side process, and which one is decided by the engine-speed
  test above.

## 5. Reads and disclosure

Reads are cheap, synchronous, and honest. They ride the same
boundary-D seam (`WorldView` via wire verbs between ticks) and are
bounded by design (radius caps, pagination). The three-channel
responsibility table (issue 0011 section 3) binds what may appear
where: STATE_PUSH is the authoritative current body state, status
is the same object pulled, KEEPALIVE is plan telemetry only.

## 6. Materialization: heavy reads land on disk once

RCON frames split at 4KB and authenticate per command - it is a
control channel, not a data plane. Reads heavier than a frame
materialize once and are served locally:

- `dump-recipes` paginates the server catalog (50/page) into
  `~/.mc/recipes/<slug>` files.
- `cat /recipes/<slug>` is local-first: disk hit means zero wire.
- Reverse lookup (which recipes consume X) is `grep` over the
  materialized tree - the query load never touches the wire.

The pattern generalizes: the wire carries the first copy, never the
query load. A second materialization target earns its verb only
when a second heavy read class actually hurts (YAGNI until then).

## 7. Stream discipline

- **stdout is data**: stable JSON, one object per answer, pipeable.
  Any harness or `jq` downstream depends on this shape.
- **stderr is narration**: taskId hints, stream re-anchor notices,
  typed suggestions, warnings. Nothing on stderr is a parseable
  contract.
- Terminal detection (isatty) may adjust human formatting
  (indentation, color) on stdout; when piped, output is one-line
  JSON per answer.

## 8. Fidelity rules

- **Vocabulary travels verbatim.** Registry ids (`minecraft:stone`),
  slot roles, task ids - never sanitized into shell-friendly
  shapes. Brigadier's `word()` rejects colons, so id-bearing wire
  args are `string()` and the caller quotes them (bitten twice:
  menu verbs, then mine - the lesson is now law).
- **Cursor truth is never filtered client-side.**
  `latestEventId` / `resetAt` / `droppedCount` are the TRUE stream
  values in every reply; display narrowing rides the server-side
  `[only]` kind-prefix filter, and the cursor-integrity kinds
  (EVENT_GAP, EVENT_DROPPED) survive every filter.
- **Typed errors, never silent substitution.** A wrong verb or path
  gets a suggestion and exit 1; the tool never guesses a different
  operation.

## 9. Anti-patterns (review-time negatives)

A change proposing any of these is wrong at the design review,
before the diff:

1. Minting a new verb where a path would answer ("which path does
   this read or write?" has no answer -> the feature is shaped
   wrong).
2. Driving a device-side loop by polling world reads from the
   harness (engine-speed test failed - make it a process).
3. Growing control flow in mc.py (the shell is the language).
4. Client-side cursor filtering or dropping stream-truth fields.
5. Sanitizing game vocabulary for shell aesthetics.
6. A terminal event a harness cannot turn into an exit code.
7. Querying heavy data over the wire repeatedly instead of
   materializing once.

## 10. Endgame: write addresses the noun it mutates

The grammar's one remaining wart is verb-shaped writes:
`write /tasks/dig "x,y,z"` and `write /actions/place "x,y,z,face"`
name the action, not the noun. The endgame unifies them under the
cell path:

- `write /blocks/<x,y,z> <blockid>` = place (synchronous receipt -
  placing is a one-tick rising edge).
- `write /blocks/<x,y,z> air` = dig (job receipt - breaking is
  multi-tick, so the receipt is a taskId; same noun, honest time
  shape).

`/tasks/dig` and `/actions/place` become aliases for one migration
window, then retire. The verb-table rows change in the same commit
(section 2 table + boundaries state). Execution is queued behind
issue 0015 with an explicit trigger (the second station-style noun
rewrite, or user go) - endgames are ruled here, timed by issue.

## 11. Living water: how this doc stays true

The model is not frozen prose; it has standing re-evaluation
triggers (decision 33). Every new task verb, every new path root,
and every new consumer kind re-opens an audit of sections 2-10 in
the shipping issue. The model itself re-opens when a real flow
cannot be expressed without breaking a clause - at which point the
clause changes HERE first, with the evidence attached, and the
ledger summary follows. An architecture that cannot absorb its own
product's growth is already dead; this document's job is to be the
part that keeps drinking.
