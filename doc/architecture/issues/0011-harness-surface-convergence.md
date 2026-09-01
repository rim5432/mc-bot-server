---
title: Harness surface convergence - the console verb contract, session runtime, and disclosure responsibilities
last_verified: 2026-09-01
covers:
  - doc/architecture/boundaries.md
  - src/main/java/com/mcbot/mcbotserver/adapter/BotCommands.java
  - src/main/java/com/mcbot/mcbotserver/adapter/BotControlSocket.java
  - src/main/java/com/mcbot/mcbotserver/api/event/EventBatch.java
  - src/main/java/com/mcbot/mcbotserver/McBotServer.java
status: open (D2/D3 shipped 2026-08-25; D1/D4/D5/D6 queued)
related:
  - doc/architecture/boundaries.md
  - doc/decisions/0005-tick-pipeline-exception-policy.md
  - doc/reference/disclosure-patterns.md
  - doc/guide/workplan.md
---

# Issue 0011: Harness surface convergence

## 1. Scope and diagnosis

The user ruling 2026-08-25: the harness-interaction side "feels
messy". Audit outcome - the core boundary-D seams are disciplined
(cursor event stream with gap recovery, idempotent submit,
sync-error-vs-async-failure; all gate-tested). The mess is at the
edges, and it concentrates before Stage 3 floods it:

1. The five console verbs (`/bot goto|cancel|stop|status|events`)
   became the de-facto harness wire API without ever being written
   down as one contract. boundaries.md section D freezes the in-JVM
   seams; nothing freezes the console layer (verb set, JSON shape,
   cursor semantics, cancel-vs-stop split). The layer was hand-rolled
   per need - its `since` argument is a brigadier Integer while the
   cursor contract is long (works today, drifts tomorrow).
2. One promised verb is missing entirely: ADR-0005 5a's recovery
   design is "harness reset clears the latch" - and no `/bot reset`
   exists. A crashed bot today is only recoverable by restarting the
   server. That is a contract hole, not a nicety.
3. `/bot events` returns everything; a harness almost always wants
   one slice (task lifecycle, or urgent). Twelve kinds today, more
   coming with Stage 3.
4. `tool/` conflates four roles (build tool, harness transport
   client, dev-client convenience, session verdicts), and every
   harness-side driver script re-implements cursor management,
   reconnect, and session journaling by hand.
5. Three disclosure channels overlap: STATE_PUSH, `/bot status`, and
   KEEPALIVE attrs all carry position/health-adjacent data with no
   written rule for which to trust when.

## 2. The frozen verb table (the console layer IS a contract)

One JSON object per answer, `{ok: true, ...}` or
`{ok: false, reason: <machine-readable>}`; output text is wire
surface. All verbs require permission level 2 and answer "no active
bot" before the first `/botspawn`.

| Verb | Shape | Semantics |
|---|---|---|
| `/bot status` | `ok, state{...}` | Pull the state snapshot (pose, health, task summary). |
| `/bot goto x y z tolerance timeoutTicks [key]` | `ok, task, replay` | Submit a goto BotCommand; `key` is the idempotency key. Sync rejection carries `reason` (structural errors only; execution failures arrive as TASK_FAILED events). |
| `/bot cancel [taskId]` | `ok, cancelled` | Cancel one task or the active one. |
| `/bot stop` | `ok, stopped` | Cancel every live task (the emergency brake; `cancel` is surgical). |
| `/bot events [since] [only]` | `ok, batch{latest, resetAt, dropped, events[]}` | Poll the event stream after cursor `since`; `only` is a kind prefix (e.g. `TASK_`) that narrows the payload. The cursor fields stay the TRUE stream cursor - filtering never lies about position. EVENT_GAP and EVENT_DROPPED always survive the filter: they are cursor-integrity metadata, and hiding them would turn a missed batch into a silent gap. |
| `/bot reset` | `ok, crashed` | Clear the crash latch and counter (ADR-0005 5a); `crashed` reports whether a latch was actually set. First wiring of a promised contract. |

Lifecycle pair (server wiring, predates the table, kept for
completeness): `/botspawn` and `/botdespawn` answer human chat text,
not JSON - a harness treats their output as opaque and reads
readiness from the verbs above.

Growth rule: verbs derive from the `BotCommand` vocabulary - each new
process kind (follow, dig-as-task) grows the table by one row in the
same shape, decided in the issue that ships the process, not ad-hoc
brigadier branches. The `since` cursor stays long at the contract
level; the console's Integer narrowing is a documented v1 wart to fix
when a transport with real volume arrives (MCP/HTTP, row D6).

## 3. Disclosure responsibility table (STATE_PUSH vs status vs KEEPALIVE)

| Channel | Answers | Cadence | Trust rule |
|---|---|---|---|
| STATE_PUSH | "what is the body's model state NOW" (pose, yaw/pitch, health, task summary) | on change | Authoritative current value; replace, do not accumulate |
| `/bot status` | same fields, pulled | on demand | Same source object as STATE_PUSH - identical values by construction |
| KEEPALIVE | "is the plan alive and progressing" (waypoint index, since-progress, plan age) | every 20 ticks | Progress telemetry only - never a source of pose/health; a harness reading position from keepalives is doing it wrong |

## 4. Decision log

| # | Question | Ruling |
|---|---|---|
| D1 | `tool/harness` session runtime (rcon transport + cursor/reconnect state + auto-journal into `tool/sessions`)? | QUEUED with recommendation: three files (`rcon.py` moved verbatim, `session.py`, `journal.py`), stdlib-only; `sessions/` becomes the journal's output, not a hand-curated folder. Ships with the first driver that needs it (the 10-minute survival rehearsal is the first customer) - not before. |
| D2 | `/bot reset` verb (close the ADR-0005 hole)? | RULED YES - shipped 2026-08-25: verb + Channels wiring; `crashed` echoes whether a latch was cleared. |
| D3 | Events kind-prefix narrowing? | RULED YES - shipped 2026-08-25: `/bot events [since] [only]` with `only` a kind prefix; narrowing lives on `EventBatch` itself (`narrowedToKindPrefix`) so every transport gets it; cursor-integrity kinds (EVENT_GAP, EVENT_DROPPED) always survive. |
| D4 | Freeze the console layer as the boundary-D wire contract? | RULED ADOPTED - the section 2 table is the contract; ledger 28 records the freeze. The `since`-Integer wart is documented, not silently kept. |
| D5 | The three-channel responsibility table? | RULED ADOPTED - section 3, binding for Stage 3 additions (inventory/menu events must slot into it, not grow a fourth overlap). |
| D6 | MCP / HTTP transport now? | RULED DEFERRED with reopen condition: RCON covers every current consumer; reopen when a non-RCON consumer actually appears (the boundary-D reopen trigger). No `harness-mcp/` directory until then. |

## 5. Sequencing

1. **Shipped 2026-08-25 (this issue's first slice)**: D2 + D3 + the
   D4/D5 tables as rulings - the verb table becomes reviewable
   contract surface the day it is written.
2. **Next customer**: D1 rides the survival-rehearsal workplan item
   (the first driver that needs reconnect + journaling).
3. **Continuous**: every new verb row lands in the section 2 table in
   the same change as its brigadier branch (the reload-parity
   discipline, applied to the console layer).
