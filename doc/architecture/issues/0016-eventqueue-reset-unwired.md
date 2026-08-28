---
title: "0016 - EventQueue reset is unwired: boundaries.md claims resetAt wipes the queue, production never calls it"
last_verified: 2026-08-29
covers:
  - doc/architecture/boundaries.md#decision-12-idempotency-and-dedupe
  - src/main/java/com/mcbot/mcbotserver/api/event/EventQueue.java
  - src/main/java/com/mcbot/mcbotserver/core/event/InMemoryEventQueue.java
  - src/main/java/com/mcbot/mcbotserver/core/tick/BotController.java
status: open
---

# Problem

`boundaries.md` decision 12 states: "The window does NOT span bot
restarts; `resetAt` already wipes the in-memory queue and the cache
follows." A consumer census (2026-08-29, H1 trim pass) found:

- `EventQueue.reset()` / `resetAt()` have **zero production
  callers**. The only callers are the gametest crash-recovery
  scenario and offline disclosure tests.
- `BotController.reset()` (the ADR-0005 5a harness reset path)
  clears the CrashLatch only - it does not touch the event queue.
- `resetAt` on the queue record is a read accessor; nothing wires a
  wipe to a reset event.

So the contract's "reset already wipes the queue" is currently
asserted by no wiring. A harness that resets a crashed bot and then
replays its event cursor gets stale pre-crash events instead of a
fresh stream - exactly the restart-spanning dedupe the decision
says cannot happen.

# Ruling needed

Either:

1. **Wire it** (behavior change, boundary D): `BotController.reset()`
   (and `onRespawned()`?) call `events.resetAt(...)` so a harness
   reset starts a fresh stream, matching the decision text; or
2. **Amend the text** (doc change): the window is cleared lazily or
   not at all across resets, and a harness must drain by cursor
   rather than rely on a wipe - boundaries.md decision 12 and the
   harness-interaction reset rows are updated first, then this issue
   archives against the absorbing commit.

Both are one-commit changes; the ruling belongs to the boundary-D
owner because option 1 changes what a harness observes across
`/bot reset`.

# Interim note

No production path wipes the queue today, so the current wiring is
consistent with option 2's letter but not with decision 12's words.
Nothing in this issue blocks the H1 trim commit (fa82f92) that
surfaced it; the trim did not touch EventQueue members.
