---
title: Onboarding Guide
last_verified: 2026-08-28
covers:
  - doc/architecture/overview.md
  - doc/architecture/boundaries.md
  - doc/guide/workplan.md
related:
  - AGENTS.md
  - doc/architecture/ledger.md
  - doc/architecture/harness-interaction.md
---

# Onboarding Guide

Cold-start reading order; each step assumes the ones before it.

1. Root `AGENTS.md` - the hard-constraint checklist (~5 minutes).
   This is the law you code under, not the design you are joining.
2. [architecture/overview.md](../architecture/overview.md) - the
   30-second picture of device vs harness.
3. [architecture/glossary.md](../architecture/glossary.md) - the
   loaded terms, pinned, so no synonym drift starts here.
4. [architecture/boundaries.md](../architecture/boundaries.md) - the
   four boundary contracts and the D protocol; the decision INDEX at
   its end tells you which entry number owns what.
5. [decisions/0001..0005](../decisions/) - frozen ADRs. Read for
   rationale, but trust their CURRENT shape from the ledger:
   e.g. ADR-0002 predates the fifth INTERACT channel (ledger 25).
6. [architecture/harness-interaction.md](../architecture/harness-interaction.md)
   - the canonical model of how a harness drives the bot; its
   anti-patterns list is what your skills/CLI will be reviewed
   against.
7. [guide/workplan.md](workplan.md) - find the first item whose
   blockers are all checked off; that is your next work.
8. On demand: [architecture/ledger.md](../architecture/ledger.md)
   full verdict texts (use the number you got from boundaries'
   index), [architecture/code-health.md](../architecture/code-health.md)
   the rule registry plus health/refactor round queue,
   [reference/toolchain.md](../reference/toolchain.md) the lint-stack
   versions and gate postures, the `reference/*-notes.md` design
   distillations, [guide/build-and-run.md](build-and-run.md) plus
   `tool/README.md` the moment you actually run something, and the
   decompiled MC/Forge tree under
   `D:/mc-decompiled/forge-1.20.1-47.4.10/` for vanilla API
   confirmation (read-only).

A new agent should be able to write code against the contract within
one day of this order - the Stage 0 gate in the workplan is the
acceptance test for that day.
