---
title: Interaction model executable queue - the composition primitives the canonical doc promises
last_verified: 2026-08-27
covers:
  - doc/architecture/harness-interaction.md
  - doc/architecture/boundaries.md
  - tool/harness/mc.py
  - tool/harness/test_mc.py
status: open (filed 2026-08-27; the executable arm of boundaries.md decision 33 and the harness-interaction.md canonical doc)
related:
  - doc/architecture/issues/0011-harness-surface-convergence.md
  - doc/architecture/issues/0012-work-block-usability-and-harness-menu-surface.md
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
| 1 | `wait` exit codes | `0` TASK_COMPLETED, `1` TASK_FAILED (verdict event JSON still on stdout), `124` wait timeout (GNU convention, already live). Regression-test both terminal kinds. |
| 2 | Stream discipline | stdout = stable JSON only; all narration (taskId hints, re-anchor notices, suggestions) on stderr; isatty may adjust human formatting. Pin in test_mc.py by capturing streams separately. |
| 3 | `events --only PREFIX` | surface the wire's existing `[only] <kindPrefix>` narrowing (0011 D3) at the CLI; cursor-integrity kinds always pass through (server guarantees; CLI never filters). |
| 4 | `ls /` + `mc help` | root discovery lists the mounted path roots (section 2 table of the canonical doc); per-verb help with examples. |

Each item ships with mock tests; none touches the wire.

## 3. Write nounification endgame (ruled, not yet timed)

Ruled in the canonical doc section 10: `write /blocks/<x,y,z>
<blockid>` = place (sync receipt), `write /blocks/<x,y,z> air` =
dig (job receipt); `/tasks/dig` and `/actions/place` become aliases
for one migration window, then retire; path-table rows update in
the same change. Execution trigger: the second station-style noun
rewrite lands, or the user releases it. NOT in the near-term queue.

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
- FUSE mount as a second frontend: owned by 0012 section 6.

## 6. Verification criteria

- Offline: test_mc.py pins wait exit codes for both terminal kinds,
  stream separation, --only passthrough, ls / output shape.
- Live: a skill chains two tasks through `wait &&` and the chain
  halts correctly on an induced failure (mine a nonexistent block
  id, then assert the second leg never submits).
