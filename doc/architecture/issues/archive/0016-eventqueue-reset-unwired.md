---
title: "0016 - EventQueue reset is unwired: boundaries.md claims resetAt wipes the queue, production never calls it"
last_verified: 2026-08-29
covers:
  - doc/architecture/boundaries.md#decision-12-idempotency-and-dedupe
  - src/main/java/com/mcbot/mcbotserver/api/event/EventQueue.java
  - src/main/java/com/mcbot/mcbotserver/core/event/InMemoryEventQueue.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java
status: resolved
superseded_by: architecture/ledger.md
---

# Problem

`boundaries.md` decision 12 states: "The window does NOT span bot
restarts; `resetAt` already wipes the in-memory queue and the cache
follows." A consumer census (2026-08-29, H1 trim pass) found:

- `EventQueue.reset()` / `resetAt()` had **zero production
  callers**. The only callers were the gametest crash-recovery
  scenario and offline disclosure tests.
- `BotController.reset()` (the ADR-0005 5a harness reset path)
  cleared the CrashLatch only - it did not touch the event queue.

So the contract's "reset already wipes the queue" was asserted by no
wiring: a harness that reset a crashed bot and replayed its cursor
got stale pre-crash events against the contract's promise.

# Ruling

The user ruled: **wire it** (option 1). 3166ebd makes
`BotController.reset()` - the harness restart seam - wipe the stream
and advance `resetAt`. `onRespawned()` deliberately does NOT wipe:
the counter survives respawn (ADR-0005 5b) for the same reason the
stream does - silent diagnostic loss hides bugs. The offline
exception-policy gate pins marker advance + wipe.

boundaries.md decision 12's text is unchanged: it describes the
now-wired behavior.
