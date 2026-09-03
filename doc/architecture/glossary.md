---
title: Glossary - the loaded terms, pinned before they mislead
last_verified: 2026-09-03
covers:
  - doc/architecture/boundaries.md
  - doc/architecture/overview.md
  - doc/architecture/harness-interaction.md
related:
  - doc/architecture/code-health.md
---

# Glossary of Loaded Terms

These terms carry definitions that fight their folk meanings. The
definition column is the contract; the third column is the live
misreading that already happened in external review (2026-08-27
round) and will happen again. Cite the anchor, not the name.

| Term | Definition (the contract) | Misreading it kills |
|---|---|---|
| dumb (device layer) | never interprets INTENT (overview.md Principles 1). Algorithms on-device were always the plan: A* since Stage 2 planning, decision 17b reserved the worker. | "the device grew a brain, principle 1 broke" - no: MineProcess executes a strategy; the harness authored the intent. |
| pure (process) | side-effect-free: no world mutation, no direct Actor access (boundaries.md section B). Internal state machines, budgets, skip-sets are legal. | "a stateful process erodes boundary B" - no: B forbids effects, not memory. |
| one seam (adapter) | BindingActor / BindingWorldView are the only implementation seam (ADR-0004 D5). Thickness behind the seam is unlimited by design; "Phase 0 ships mocks" was phasing, never a thinness promise. | "24 adapter classes broke the thin-adapter promise" - no: the seam count stayed one. |
| reflex bypass | reflex never enters arbiter NUMERICS (ADR-0003). Reflex-OWNED missions compete through normal register / requestControl - ledger 24-27 and 35, the ReflexMissionSeat mechanism. | "defend / rescue missions erode boundary C" - no: the numbers stay out; the seats are the recorded mechanism. |
| zero-Behavior promise | adding a task = one new Process, touching zero BEHAVIORS (boundaries.md section B table row). | quoting it as "zero controller changes" - the controller's recorded claim paths (0013 / 0014) were never the promise. |
| job / verdict | a multi-tick task; its TERMINAL EVENT is the machine-decidable verdict that harness exit codes derive from (decision 33b). | "wait exit codes are CLI sugar" - no: the exit code is the contract joint. |
| promotion trigger | abstractions generalize at the SECOND consumer or second kind, never before. Single lookup: code-health.md's abstraction-status table. | "generalize now to be safe" - preventive abstraction is the debt, not the safety. |
| noun / verb | a capability answers "which path do I read or write"; verbs are never minted (decision 33a; harness-interaction.md section 2). | "add a verb for the new operation" - the question with no path answer means the feature is shaped wrong. |
| engine speed | loops divide at per-tick feedback: the device owns engine-speed loops, the harness orchestrates between tasks (decision 33c). | "the harness should poll blocks to drive mining" - no: it polls the event stream. |
