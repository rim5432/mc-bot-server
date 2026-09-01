---
title: Harness Interaction Model - the canonical architecture of the bot's command surface
last_verified: 2026-09-01
covers:
  - doc/architecture/boundaries.md
  - tool/harness/mc.py
  - src/main/java/com/mcbot/mcbotserver/adapter/BotCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/MenuCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/MenuVerbs.java
  - src/main/java/com/mcbot/mcbotserver/api/inventory/ItemIds.java
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
| `ls` | `ls <path>` | discovery; every root answers `ls` or typed-rejects; `ls /` lists the mounted roots |
| `cat` | `cat <path>` | read one state thing (incl. station snapshots: open, read, close); local-first where a materialized copy exists |
| `read` | `read <path>` | document read: in-game text content (book pages, item information - `/items`, not yet shipped); typed error until then, never substitution |
| `write` | `write <path> <value>` | mutate the noun the path names |
| `wait` | `wait <taskId> [--timeout S]` | join a job; exit code is the verdict |
| `events` | `events [--since N] [--only PREFIX] [--follow] [--idle S]` | event drain; peeking never advances the cursor |
| `admin` | `mc admin stop\|reset\|dump-recipes` | deliberately OUTSIDE the namespace; operator verbs are not harness vocabulary |

Canonical path roots (this table is where they live; a new row
ships with its process issue but lands here - decision 33
superseding decision 28's table-lives-in-the-issue clause):

| Root | Carries | Writer shape |
|---|---|---|
| `/tasks/` | job family: `goto` `mine`, `<id>` (cat), `<id>/cancel` | value encodes args; receipt is a taskId |
| `/player/` | `health`, `inventory/free`, `bag` (per-slot inventory truth), `menu` (pending 0012 D1); writable: `sneak` (latch), `hotbar` (selection 0..8), `held/use` (held item against the POV ray) | reads synchronous; writes are synchronous; reply is the verdict |
| `/blocks/<x,y,z>` | one cell; volume reads aggregate nearby | `<blockid>` places (sync receipt; optional `@face` suffix for orientation, default up), `air` digs (job receipt), `/use <face>` runs the block's interaction, `/sleep` bed-rests (device-completed rule the engine cannot see) |
| `/entities/` | nearby entity list, distance-sorted (lines carry the id= column) | `/attack` = directed engagement (job receipt; the harness hands the target id) |
| `/nearby/` | nearby entity digest (distance-sorted, self-flagged) | read-only; rides the `entities` wire verb |
| `/recipes/<slug>` | recipe pages, materialized to disk (section 6) | read-only; `dump-recipes` refreshes |
| `/stations/<type>@<x,y,z>/<role>` | workstation snapshots | last segment is always a ROLE, never a verb |
| `/events` | the event stream | read via `events` |

Utility-noun orthogonality (executed with section 10): verbs carry
no per-root special cases. Every root answers `ls` (an enumeration
or a typed explanation of why not - `/blocks` is address-based, not
enumerable), every state leaf answers `cat`, and bulk analysis is
never a query language - it is `admin dump-<noun>` materializing to
disk once, after which the shell's own grep/awk/jq compose freely.
The consumer is the grep for small payloads; materialization wins
exactly when payloads get big.

Wire-side namespace split (recorded wart, owned by 0013): the task
family registers under `/bot ...` while the synchronous family
registers at the console root (`block`, `blocks`, `entities`,
`place`, `use`, `sleep`, `use-item`, `sneak`, `equip`, `menu`,
`scan`, `recipes`); the operator verbs `botspawn`/`botdespawn` also
register there, outside the harness vocabulary. The CLI hides the
split entirely - the `/actions/` drawer is retired client-side and
its members fold into the noun roots above; the wart is repaired
only with a non-RCON transport that justifies touching the wire.

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
4. `mc wait <id>` exits `0` only on COMPLETED; `1` on every other
   terminal kind - FAILED, REJECTED, CANCELLED, DROPPED (verdict
   event JSON still on stdout); `124` on wait timeout following GNU
   `timeout`'s convention. Success is the only zero: this is what
   makes `mc wait $id && mc write /tasks/...` mean what a shell
   reader expects.

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
where: STATE_PUSH is the change notification (stamped attrs:
posX/posY/posZ/dimension/task, pushed only when the whole record
changes), status is the authoritative full state object pulled,
KEEPALIVE is plan telemetry only.

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

## 7a. CLI presentation shapes (pinned by the 2026-08-31 black-box round)

The `mc cat` verbs answer derived values, not raw wire objects -
pinned live against runServer (qa-results/boundary-d):

- `cat /player/pos` answers a bare `[x, y, z]` array;
  `cat /player/inventory` answers the items map alone; `cat
  /player/status` answers the flat state face (pos, yaw, pitch,
  dim, items, slot, effects, task, healthHearts, freeSlots, food,
  xp - the boundaries D1 table is the field authority).
- Write receipts carry `ok`/`task`/`replay`; `replay:true` holds
  exactly inside the live window - after a terminal verdict the
  same key legitimately mints a new task.
- Cancelling an already-terminal task is a typed `no such task`
  error, never a silent success.
- Ops note: `@e[type=mcbotserver:bot_body]` cannot see a body
  parked in an unloaded chunk - reset through `/botdespawn` +
  `/botspawn` (binding references), not selectors.

## 8. Fidelity rules

- **Vocabulary travels verbatim on output; input is normalized at the boundary.**
  Registry ids (`minecraft:stone`) in snapshots and receipts are always
  canonical — never shortened, never sanitized into shell-friendly
  shapes. On input, bare ids (`stone`) are accepted and expanded to
  canonical form (`minecraft:stone`) by `ItemIds.normalize` at every
  wire entry point (deposit, recipe lookup); already-canonical ids and
  modded namespaces (`create:stone`) pass through unchanged. Brigadier's
  `word()` rejects colons, so id-bearing wire args are `string()` and
  the caller quotes them (bitten twice: menu verbs, then mine - the
  lesson is now law).
- **Deposit/take errors are taxonomized.** A zero-supply deposit rejects
  with `item not found in player region: <id>` (the item is absent,
  distinct from a format mismatch); a partial-supply deposit rejects
  with `not enough: have <N>, need <M>` (the item exists but in
  insufficient quantity). A consumer can branch on the prefix:
  `item not found` → wrong id or empty inventory; `not enough` →
  request too large for current supply.
- **Cursor truth is never filtered client-side.**
  The stream-truth wire keys `latest` / `resetAt` / `dropped` (the
  record components `latestEventId`/`droppedCount` serialize to the
  short keys, pinned by H-R4) are the TRUE stream values in every
  reply; display narrowing rides the server-side
  `[only]` kind-prefix filter, and the cursor-integrity kinds
  (EVENT_GAP, EVENT_DROPPED) survive every filter.
- **Typed errors, never silent substitution.** A wrong verb or path
  gets a suggestion and exit 1; the tool never guesses a different
  operation.
- **Verdicts arrive on reflex ticks too.** The full derivation — why a
  freeze landing in the one-tick retirement lap emits the mission verdict
  on the reflex tick, and why both `COMPLETED → PAUSED → RESUMED` and
  `PAUSED → RESUMED → COMPLETED` are legal sequences — lives in
  [boundaries.md invariant 7](boundaries.md#invariants-must-hold).
  Harness-side implication: treat any `TASK_*` event as authoritative
  regardless of pause state; a live park emits `TASK_PAUSED` only, and
  verdicts for parked missions arrive after `TASK_RESUMED`.
- **Escaped-target verdicts are conservative.** A defend target that
  leaves the engaged tracking scan (14 blocks) after the 10-tick
  grace fails as TARGET_ESCAPED - death and escape are
  indistinguishable from scans, so a kill is never claimed (issue
  0006). A target between leash (12) and the tracking scan (14)
  fails immediately as LOST_TARGET. TASK_COMPLETED on a defend means
  the scan came up empty at submission time (area clear), not a
  confirmed kill; re-scan to confirm one.

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

## 10. Write addresses the noun it mutates (executed 2026-08-28)

The grammar's last verb-shaped writes are gone. Every mutation now
addresses the noun it changes; `/actions/` is retired as a path
root, and the fold landed as one coordinated break (the ruled
migration window collapsed to zero: the only consumers were the CLI
and its own tests - verified before the break):

- `write /blocks/<x,y,z> <blockid>` = place (synchronous receipt -
  placing is a one-tick rising edge; optional `@face` suffix picks
  the clicked face for orientation, default up).
- `write /blocks/<x,y,z> air` = dig (job receipt - breaking is
  multi-tick, so the receipt is a taskId; same noun, honest time
  shape). `/tasks/dig` retired with it.
- `write /blocks/<x,y,z>/use <face>` = the block's own interaction
  handler (doors, buttons, levers).
- `write /blocks/<x,y,z>/sleep` = bed rest as a device-completed
  interaction: the all-sleepers rule the engine cannot see (the
  body is not in the player list) is why this is not `/use`.
- `write /player/sneak on|off`, `write /player/hotbar <0..8>`,
  `write /player/held/use` = player state and held-item use.
- `read` is re-homed to documents (book pages, item information -
  `/items`, queued as its own capability slice; until it ships the
  verb answers a typed error). Station snapshots moved to `cat` as
  self-contained open-read-close transactions.
- `ls` is universal: every root answers with an enumeration or a
  typed explanation (`ls /player` lists the readable fields - the
  surface is self-describing; `ls /blocks` explains addressing).

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
