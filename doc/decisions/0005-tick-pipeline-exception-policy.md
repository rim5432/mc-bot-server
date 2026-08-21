---
title: ADR-0005 Tick Pipeline Exception Policy
last_verified: 2026-08-21
covers:
  - doc/decisions/0004-tick-pipeline-actor-channels.md
---

# ADR-0005 Tick Pipeline Exception Policy

- Status: accepted (2026-08)
- Amends: [ADR-0004](./0004-tick-pipeline-actor-channels.md) (closes the
  exception-handling gap in D1's tick pipeline)
- Depends on: [ADR-0004](./0004-tick-pipeline-actor-channels.md),
  [ADR-0002](./0002-capability-model-task-arbiter.md) §2 (winner-take-all
  arbiter), [ADR-0003](./0003-reflex-layer-preemption.md) §3
  (InterruptionContext on preemption)

## Context

ADR-0004 D1 defines the four-stage tick pipeline
(`reflex -> arbiter -> behaviors -> actor.flush`) and ADR-0004 D2
defines the four-channel Actor. Neither says what happens when any of
the four stages throws a `RuntimeException`. The MC 1.20.1 server's
main thread wraps an uncaught exception in a `ReportedException` and
trips the watchdog at 60 s, **terminating the entire server**, not just
the bot. The same mechanism will be true for every Forge / Fabric
loader we might port to: a thrown `RuntimeException` from a tick
callback becomes a server-wide crash.

The failure mode is documented in
[numen-notes.md §14](../reference/numen-notes.md#14-tick-crash-capture-one-task-failure--server-crash)
(see `NumenPlayer.reportTickFailure` and
`AbstractCompanionTask.crashed`). Numen's class doc states the rule:

> "Tick errors are not caught above, MC wraps them in `ReportedException`
> and crashes the entire server main loop -- the watchdog will then
> force-kill the server 60 seconds later. The most common thrower is
> not us: our companion sits in the player list as a 'player', and
> other mods that receive its lifecycle events do not know this
> 'player' has no real connection. We cannot plug every variant of
> this -- we can only stop the same exception from killing the
> server."

Baritone's issue #3565 documents a parallel structural problem:
"input authority must be centralized or processes step on each other's
paws." The exception policy is a second structural problem in the same
family. The crash also leaks physical output: a partial flush of the
Actor channels can leave the bot still moving toward a stale
destination while the exception is being unwound.

The freeze-order constraint from [ADR-0003](./0003-reflex-layer-preemption.md) §3
already defines `InterruptionContext` as the shape we record on
preemption; we reuse that shape here so the harness and the recovery
path see a uniform structure.

## Decision

### D1. `BotController.onTick()` wraps the full four-stage pipeline in a single try-catch

The catch boundary is the outermost frame of `BotController.onTick()`.
It covers the entire ADR-0004 D1 sequence in one try block. Catching
narrower (per stage) leaves the seams between stages uncaught, which is
where the original problem lives. Catching broader (across multiple
ticks) loses the `InterruptionContext` shape and makes the latch
debuggability worse.

The catch is `RuntimeException`. We do not catch `Error` -- a
`OutOfMemoryError` or similar is the JVM telling us the process is
done and we should let it die. We do not catch `InterruptedException` --
tick cancellation is a separate concern that the scheduler owns.

### D2. First exception: latch the crashed state, clear actor intents, snapshot, log

On the first `RuntimeException` caught in `onTick()`:

1. Set the `crashed` latch to `true` (sticky until reset -- see D5).
   The latch is per-bot (one `BotController` per bot body).
2. Capture an `InterruptionContext` snapshot -- tick, bot pose,
   identity of the currently active process / behavior / reflex,
   exception class, message, and stack trace -- reusing the shape from
   ADR-0003 §3 so downstream code does not need a second
   context type.
3. Call `Actor.clearAllIntents()` exactly once, with no dependence on
   any subsystem that might itself be the source of the crash. This
   ensures the bot stops all physical output on the very next tick,
   regardless of which stage threw. The clear runs before the report
   is emitted, so even a crash inside the outbox cannot leave the
   bot emitting stale intents.
4. Emit the crash event through both reporting channels (see D4).
   The emission is best-effort per channel; the catch frame does not
   re-throw on either side failing.

The latch and the `InterruptionContext` snapshot are the only state
that survives the catch. Nothing else about the in-flight tick is
preserved -- a partial directive, a half-emitted Actor claim, a
behavior mid-update -- all discarded. The `MinimalReflex` (D3) starts
fresh on the next tick.

### D3. Crashed state skips all mission logic AND the full reflex rule table; only `MinimalReflex` runs

After the latch is set, every subsequent tick:

- Skip the full ADR-0004 D1 pipeline (reflex, arbiter, behaviors, actor).
- Skip the full reflex rule table, including rules the original stage
  would have run.
- Run **only** `MinimalReflex` -- a separate, hard-coded reflex class
  with no dependency on `ThreatBlackboard`, no dependency on the rule
  table, and no dependency on any derived computation that could itself
  be the source of the crash. `MinimalReflex` is a separate class
  with no shared mutable state with `ReflexLayer`; it is the single
  behaviour path allowed in the crashed state.

`MinimalReflex` is intentionally trivial -- a few `if` statements for
"health below threshold -> halt" and "standing in lava -> jump" -- so
the probability of `MinimalReflex` itself throwing is negligible. If
`MinimalReflex` ever does throw, the same D2 latch fires again and the
report goes out the same dual channels; the system degrades to "the
server keeps running, the bot is in a state we can document", which
is the absolute floor we are willing to accept.

The crash source is the crash source. We do not let it run again.

### D4. Crash reporting is dual-channel; either channel succeeding is enough

The crash event is reported through two independent channels. Both
fire on every crash. Either one succeeding is treated as "reported";
both are best-effort and share no dependency with each other or with
the system that crashed.

| Channel | Destination | What can fail |
|---|---|---|
| **Primary** | `EventOutbox` (structure-preserving JSON, replayable, visible to the harness) | The outbox itself may be the source of the crash (e.g. exception during JSON serialization, queue write, or `take` from a poisoned state) |
| **Fallback** | `System.err.println` + synchronous append to a dedicated log file in the run's runtime directory (pure JVM, no MC types, no subsystem imports) | Only disk-full / permission-denied / stdout-closed -- all of which mean the server is in deeper trouble than the bot |

Both fire on every crash; one succeeding is sufficient. The fallback
exists precisely so that a crash inside the outbox still leaves a
paper trail.

### D5. Recovery: explicit harness reset vs. passive respawn are not equivalent

The crashed latch can be cleared two ways. The two are not equivalent
and the bot tracks the difference.

**5a. Harness explicit reset command.** The harness sends a reset
command through the boundary-D seam (the command vocabulary may grow;
seam semantics are frozen per `boundaries.md` Boundary D). Reset fully
clears:

- the `crashed` latch,
- the `crashCounter` (see 5b),
- the `InterruptionContext` snapshot.

The next tick runs the full ADR-0004 D1 pipeline.

**5b. Bot death + respawn.** Death is a passive event. On respawn:

- The `crashed` latch **is** cleared. The world assumptions the crash
  context referred to no longer hold; an `InterruptionContext` from a
  previous life is meaningless.
- The `crashCounter` is **preserved** across respawns. A bot that
  crashed 5 times before dying is still "a bot that crashed 5 times"
  after respawn. The harness reads the counter (via `statusSnapshot()`)
  to decide whether the bot is in a pathological state and should be
  retired or investigated.

A bot that is killed by a mob and respawns is **not** "recovered from
its crash" -- the crash is still in the counter. This is intentional:
silent recovery hides bugs. The counter makes a recurring crash
visible to the harness even if the latch has been cleared by an
external event.

## Consequences

**Positive**

- The server never terminates because of a bot-logic exception.
  Third-party mods that throw on our bot's lifecycle events are caught
  by the same harness; the worst case is "the bot stops acting and the
  exception is in the log".
- The bot stops all physical output on the very next tick after the
  crash, regardless of which stage threw. No more "bot keeps digging
  into lava because the dig intent was emitted before the throw".
- The crash source is physically isolated (`MinimalReflex` shares no
  state with the crashing code). The bot cannot crash-loop on its own
  exception.
- Crash reports are dual-channel redundant. Even a crash inside the
  outbox leaves a paper trail on disk.
- Recovery semantics are unambiguous. A harness reset fully clears; a
  respawn clears the latch but preserves the counter for diagnosis.

**Negative**

- `BotController` carries a `crashed` state machine and a crash
  counter. One-time cost; the state has two transitions in and two
  out.
- `MinimalReflex` is hard-coded. Adding new "safe-while-crashed"
  behaviours beyond the lava / low-health pair requires code changes,
  not configuration. This is the price of "no shared dependency with
  anything that might be crashing".
- The fallback log file is duplicated information if the outbox also
  succeeds. We accept the duplication as the cost of guaranteed
  reporting.
- The harness gains a new vocabulary item (`reset`). It is the only
  addition to boundary D we expect for the exception policy; further
  exception-related commands must justify their seam width against the
  `boundaries.md` Boundary D freeze rule.
- The stage-0 gate grows a third test (see Workplan impact). The test
  is small (mock outbox + mock actor + a lambda that throws from one
  of the four stages) but it is now part of "done" for Stage 0.

## Workplan impact

- The Stage 0 `BotController tick ordering` workplan item (the M-sized
  one at the bottom of Stage 0) is **expanded by this ADR**: the
  implementation now also includes the exception latch (D2), the
  `MinimalReflex` isolation (D3), the dual-channel reporting (D4),
  and the two recovery paths (D5). Still S/M-sized -- the latch is a
  boolean, the counter an int, the two recovery paths are trivial
  commands.
- The Stage 0 gate (the ordering test + the resume-validation test) is
  **extended** with a third required test: an exception thrown from
  any of the four stages must result in (a) the latch set, (b) the
  next tick's `MinimalReflex` only, (c) both reporting channels having
  received the event, (d) no `ReportedException` reaching the MC main
  thread. This is the only new test for Stage 0; the rest of the gate
  is unchanged.
- The `statusSnapshot()` boundary-D verb (per `boundaries.md`
  Boundary D) gains one read-only field: the `crashCounter`. No other
  shape change.

## Notes

The four-stage pipeline is defined in
[ADR-0004 D1](./0004-tick-pipeline-actor-channels.md#d1-single-tick-entry-fixed-order);
the Actor four-channel model in
[ADR-0004 D2](./0004-tick-pipeline-actor-channels.md#d2-actor--four-channels-with-per-tick-claims);
`InterruptionContext` shape in
[ADR-0003 §3](./0003-reflex-layer-preemption.md#3-preemption-captures-interruptioncontext-resume-revalidates-the-world).
This ADR adds no new boundaries; it only closes a gap in the existing
ones.
