---
title: Interaction model executable queue - the composition primitives the canonical doc promises
last_verified: 2026-09-02
covers:
  - doc/architecture/harness-interaction.md
  - doc/architecture/boundaries.md
  - tool/harness/mc.py
  - tool/harness/test_mc.py
status: open (filed 2026-08-27; the executable arm of boundaries.md decision 33 and the harness-interaction.md canonical doc; heir of the archived issue 0012 menu-surface implementation queue)
related:
  - doc/architecture/issues/0011-harness-surface-convergence.md
  - doc/architecture/issues/archive/0012-work-block-usability-and-harness-menu-surface.md
  - doc/architecture/issues/0013-world-interaction-layer.md
  - doc/architecture/issues/0014-mineprocess-composite-mining-task.md
---

# Issue 0015: Interaction model executable queue

## 1. Problem

The interaction model is now law (boundaries.md decision 33) and
canon (doc/architecture/harness-interaction.md). Parts of the law
describe behavior the harness does not deliver yet. The widest gap:
`mc wait` exits 0 for BOTH TASK_COMPLETED and TASK_FAILED
(mc.py:675-677), so `mc wait $id && next` chains past failures -
the Unix composition primitive is broken at exactly the joint the
model calls the verdict. This issue owns the executable queue that
closes the gaps; design rationale lives in the canonical doc, not
here.

## 2. Near-term queue (harness-side only, zero wire change)

| # | Item | Contract |
|---|---|---|
| 1 | `wait` exit codes | `0` only on TASK_COMPLETED; `1` on every other terminal kind - FAILED, REJECTED, CANCELLED, DROPPED (ruled with the fix: success is the only zero, so `wait &&` halts on every non-completion); verdict event JSON still on stdout; `124` wait timeout (GNU convention). All five terminal kinds mock-pinned in WaitTest. |
| 2 | Stream discipline | SHIPPED: emit_json indents at a tty, one line piped; all 13 indent answers swapped; pinned by ResidueQueueTest. |
| 3 | `events --only PREFIX` | SHIPPED: rides every events wire call via only_suffix (drain, re-anchor, follow); pinned. |
| 4 | `ls /` + `mc help` | SHIPPED: ls / prints the nine canonical roots; mc help [verb] carries usage+example; pinned. |

Each item ships with mock tests; none touches the wire.

## 3. Write nounification endgame (EXECUTED 2026-08-28)

User released the trigger. Landed beyond the ruled minimum: not
just `write /blocks/<x,y,z> <blockid>` = place (sync receipt) and
`write /blocks/<x,y,z> air` = dig (job receipt), but the whole
`/actions/` drawer retired - `/use` and `/sleep` join the block
path as sub-resources, `sneak`/`hotbar`/`held/use` move under
`/player`, `/tasks/dig` retires into `write air`. `read` is
re-homed to documents (books, item information - `/items`, its own
future slice) with station snapshots moving to `cat` as complete
transactions; `ls` is universal (every root answers or
typed-rejects, `ls /player` self-describes the fields). The ruled
migration window collapsed to zero: rg-verified the only consumers
were the CLI and its tests. One coordinated break; canonical doc
section 2 tables and section 10 record the landed shape.

## 4. Living-water triggers (standing)

Every new task verb, new path root, or new consumer kind re-opens
an audit of the canonical doc's sections 2-10 here. The model
itself re-opens when a flow cannot be expressed without breaking a
clause - the clause then changes in the canonical doc first, with
evidence attached, and the ledger summary follows.

## 5. Deferred with reopen

- Event-ring capacity under motion: sustained walking pushes
  STATE_PUSH per cell crossing and evicts older events; gap
  signals fire per contract but task events can churn out
  mid-walk. Device-side; owned by 0014 section 6.
- Bot death mid-mission orphans in-flight tasks (no terminal
  event, pipeline stops). Device-side; owned by 0014 section 6.
- `ls /tasks` as a real listing needs a list-tasks wire verb;
  today the CLI derives only the current task from status.
- FUSE mount as a second frontend: owned by archived 0012 section 6.

## 6. Verification criteria

- Offline: test_mc.py pins wait exit codes for all five terminal
  kinds, stream separation, --only passthrough, ls / output shape.
- Live: a skill chains two tasks through `wait &&` and the chain
  halts correctly on an induced failure (mine a nonexistent block
  id, then assert the second leg never submits).

## 7. Inherited menu-surface queue (issue 0012, archived 2026-08-27)

The 0012 design is resolved - D4 promoted into the canonical model,
its wire table standing as the integration contract, rulings 1-17
executed or overturned on record - but the implementation queue
never ran past goto migration. These inherit here; shapes per the
archived D1 table and its rulings:

- [x] L  D1 menu wire verbs - open / open-inventory / snapshot /
         close / deposit / take / craft / scan / recipes -
         brigadier-direct synchronous RPC against MenuTransactions /
         RecipeCatalog, NOT CommandBus submissions. Disposition
         2026-08-27 (H-R4 convergence): landed with the Phase 2/3
         menu transactions in adapter MenuCommands; the
         [dep: 0007 Path A] note pre-dated the Phase 2 facade
         baseline (913438e). D3/D5/D2 below remain open.
- [ ] M  D3 derivation events - MENU_OPENED / MENU_CLOSED from
         MissionReporter diffing BindingActor.currentMenuKind()
         ({kind,x,y,z} composite key; same-tick open-close invisible;
         no cause field); `/bot status` gains a menu field.
                                                    [dep: D1 batch]
- [ ] S  D5 translation-layer wiring - lease auto-close (30-60s idle,
         busy tripwire), `/bot reset` clears the session, CLI
         timeout=unknown with reconcile-not-retry, close-before-open
         discipline, disk-cursor bookmark rules (peek vs advance;
         beyond-head restart signal).               [dep: D1 batch]
- [ ] S  D2 first L1 row - furnace INPUT / FUEL / OUTPUT roles +
         burn/cook progress disclosure (freeSlots already shipped
         via ledger 32).                            [dep: D1 batch]
- [x] S  Entity-ticking ticket gap found live: on a bare dedicated
         server nothing grants the bot's chunks an entity-ticking
         ticket and the body freezes until `forceload add` -
         CompanionChunkLoader-class capability candidate, or runbook
         automation; manual prep documented in shadow_compare.py.
         Pinned by receipt 2026-08-31 (qa-results/boundary-d/
         receipt-20260831-051354.json, case C1/C1b): the queue is
         server-tick driven and keeps failing tasks honestly while
         the body alone freezes; forceload wakes it. RESOLVED same
         day: adapter BotChunkTicket follows the body with a
         forceload grant (setChunkForced; a custom region ticket
         logged as granted yet never loaded its chunk - probe:
         setblock kept answering "not loaded", while forceload
         proved itself twice live), driven from BotAssembly.tickOnce
         before the pipeline, released on despawn/replace/death and
         re-granted while the body sits unloaded (UNLOADED_TO_CHUNK
         is not a death: the chunk load re-materializes it). Closed
         by receipt flip: case C1 green on the fix, frozen again on
         the disabled-ticket bisect build.
                                                        [dep: none]
- [x] S  resetAt epoch honesty - EventQueue promises a monotonic
         bot-restart marker but a fresh queue restarts it at 1;
         either seed from a persistent counter or amend the
         EventQueue doc to the beyond-head client rule (the CLI
         ships beyond-head today). Pinned by receipt 2026-08-31
         (qa-results/boundary-d/receipt-20260831-052015.json, case
         C2-post): clean stop + relaunch returned the same epoch,
         violating beyond-head. RESOLVED same day: the marker draws
         from an allocator at construction AND on every reset
         (InMemoryEventQueue epoch-allocation constructor), and the
         production allocator is EventEpochStore - a SavedData
         sequence in the overworld, one strictly-increasing grant
         per queue recreation and per /bot reset. The collision
         domain was wider than the receipt showed: every
         botdespawn+botspawn minted marker 1 too (BotAssembly news
         the queue per spawn). Crash window: grants persist on world
         save, so a crash rewinds to the last save - the client's
         id-space backstop (cursor beyond stream head) still detects
         that shape. Pinned by ResetEpochGateTest and the receipt
         flip: C2b (respawn, within one boot) and C2-post (clean
         restart) both green on the fix.                [dep: none]

Reopen triggers that live only inside the archived 0012 body
(brewing / enchant cast overrides, L2 JSON role table, cast mixin,
multi-client arbitration, full-chain acquire): their archive path is
the standing pointer - they reopen from there when fired.
