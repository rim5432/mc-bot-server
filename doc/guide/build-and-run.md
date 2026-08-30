---
title: Build & Run Guide
last_verified: 2026-08-30
covers:
  - tool/mcbot_tool.py
---

# Build & Run Guide

All build/test/run entry points go through `tool/mcbot_tool.py`.
Never call `gradlew.bat` or `gradle` directly — it bypasses the
cross-process lock that multi-agent collaboration depends on.
Full hard-constraint rules live in the root `AGENTS.md` (§0.2);
this page is the quick path, and `tool/README.md` carries the full
subcommand table.

## Everyday commands

```bash
python tool/mcbot_tool.py build compile   # fast compile check (seconds)
python tool/mcbot_tool.py build jar       # produce mod jar in build/libs/
python tool/mcbot_tool.py test            # JUnit tests
python tool/mcbot_tool.py status          # lock + processes + last log
```

## Static analysis

Two postures, split at the 2026-08-27 promotion (user ruling:
hygiene outweighs ~10s of build time):

1. **Hard gates ride the default `test` flow.** `checkstyleMain`,
   `checkstyleTest` and `spotlessCheck` run on every
   `python tool/mcbot_tool.py test` and fail the build on
   violation - no opt-in, no memory required. Block-verified by
   probe (an injected unused import fails `test` in 19s).
2. **Everything else is one command.** PMD, CPD, SpotBugs (and
   Error Prone on the `-Plint` compiles) run behind `-Plint` through
   the consolidated `qualityCheck` task - one invocation, one verdict:

```bash
python tool/mcbot_tool.py lint   # = gradle qualityCheck -Plint --continue
```

`LINT_TASKS` in tool/mcbot_tool.py mirrors this section; change both
in the same commit.

Postures after the 2026-08-27 promotion:

| Suite | Posture | Notes |
|---|---|---|
| Checkstyle | **hard gate, default `test` flow** | naming, imports, Javadoc, modifier order, EmptyBlock; 2026-08-27 added TodoComment, IllegalCatch (narrowed), IllegalThrows, MissingDeprecated, JavadocVariable (public constants), RegexpSinglelineJava (bans printStackTrace() and System.exit()) |
| Spotless check | **hard gate, default `test` flow** | `palantirJavaFormat` output == ecosystem standard (4-space / 120 cols / K&R); `spotlessApply` runs ungated any time |
| PMD main + test | **RED WALL, `-Plint` fails on violation** | zero findings held since the 2026-08-27 paydown (17 -> 0 via per-channel/per-kind/stage extractions); only project-documented `@SuppressWarnings("PMD.TooManyMethods")` exemptions remain on MineProcess and BotController
| CPD | **RED WALL, `-Plint` fails at >=140 tokens** | main-side duplication cleared 2026-08-27 (CommandResponse, submitCommand, CommandHandlerGuards, BindingInventory.toView sharing, GametestRig.fillPool); the threshold sits above the largest surviving test-side copy (131), so only new main-scale duplication fires - sub-threshold dups stay periodic manual review |
| SpotBugs (api+core scope only) | **advisory dashboard** (`ignoreFailures`) | `onlyAnalyze` limits to engine-free packages, which need no MC auxclasspath; first pass flagged ThreatBlackboard dead fields, EI2 exposures |
| Error Prone (on `-Plint` compiles) | **11 checks at ERROR + NullAway, rest warnings** | core pinned 2.42.0 (last JDK17 runtime); ReferenceEquality OFF; promoted guards: StreamResourceLeak, JdkObsolete, DefaultCharset, StringCharset, MissingOverride, EqualsIncompatibleType, InterruptedExceptionSwallowed, StringCaseLocaleUsage, SystemOut, NonApiType — zero-violation baseline; NullAway enforces @Nullable/@NonNull on api.. (jsr305), 6 annotated nullable sites, off for test code |
| JaCoCo coverage | **manual lens** (`gradle jacocoTestReport`) | offline-layer truth only: adapter/gametest/client read 0% by design (engine-covered), core layers 86-97%; watch `core/command`, `api/interrupt`, `api/state` |
| PIT mutation | **manual lens** (`gradle pitest`) | baseline 2026-08-27: 34.2% overall kill, ~82% within covered code; weakest gates: PathingBehavior 36 survivors, MineProcess 31, BasicMoves 14 |

Deliberate deferrals recorded in the configs themselves: import ORDER
is whatever the formatter emits (single source of truth), and
identity-comparison checks are absent from both PMD and Error Prone
sets (see BytecodeArchitectureGateTest sibling comments). JavadocMethod
tag completeness left its deferral on 2026-08-27 - all 35 live gaps
fixed at root and the rule now runs with the rest of checkstyle.

God-class paydown progress (PMD dashboard): 31 -> 17 findings after the
first extraction pass (`BindingActor.flush` per-channel appliers,
`BindingMenu.roleOf` per-kind mappers; BindingMenu WMC 63->54,
TCC 0%->28%). The remaining 17 are design work, not mechanical splits:
MenuPlanner's four planner methods (CC 13-23), BotController.runPipeline
CC=23 behind ADR-0005 invariants, AStar compute CC=19, plus GodClass x3.

Bytecode purity + dependency direction are plain JUnit gates under
`architecture/` (run by `test`): see `BytecodeArchitectureGateTest`.
Contract-marker and invariant-marker conventions are source-scan
gates: `BoundaryContractMarkerTest` (boundary-interface implementers
carry `contract: see ADR-NNNN`) and `InvariantMarkerGateTest`
(BotController.onTick call-chain methods carry `invariant: see ADR-0005`),
both pinned-list with anchors.

Top-level module acyclicity is an ArchUnit slice rule
(`NO_MODULE_CYCLES` in `BytecodeArchitectureGateTest`): api, core,
adapter, client, gametest must not form cycles. Subpackage cycles
inside a module are gated too (`NO_SUBPACKAGE_CYCLES`, same class).
Both rules used to carry documented exceptions that have since been
paid off: `api.behavior ↔ api.process` dissolved when `ExecutionReport`
moved to `api.process` (produce where consumed), and the
`adapter ↔ adapter.entity ↔ adapter.rescue` parent-child smell dissolved
when `BindingInventory` moved to `adapter.inventory`. There are no
cycle exceptions left; a new one is a design regression.

## Continuous integration

The public mirror at `github.com/rim5432/mc-bot-server` runs the
offline gate on every push and PR (`.github/workflows/ci.yml`):
`compileJava` + the default `test` flow, meaning offline JUnit plus
the checkstyle/spotlessCheck hard gates. CI invokes `gradlew`
directly on purpose - the toolbox lock exists to serialize
concurrent agents on this one checkout, which a fresh CI runner
does not have. Engine gametests stay local-only
(`build runGameTest`); GitHub-hosted runners cannot boot the game.
CI is the enforcement mirror of this page, not a second toolchain:
if CI fails on a commit that passed locally, suspect uncommitted
local state, not CI drift.

## When a build fails

```bash
python tool/mcbot_tool.py log tail -n 100      # recent output
python tool/mcbot_tool.py log cat compile      # full log by task fragment
```

Failed runs auto-print the last 30 lines already.

## Long-running game launches

Locks are namespaced: everything that writes `build/` serializes on
the global `build` lock, while each game task holds its own
`run.<task>` lock — a dedicated server and a dev client can be alive
at the same time (they only read build outputs). A run holds its
lock until the game window closes; a compile while a run is alive
may fail on Windows jar file locks, so stop the run first.
Launch runs in the background and follow logs instead of blocking:

```powershell
Start-Process python -ArgumentList 'tool\mcbot_tool.py','build','runClient' -WindowStyle Hidden
python tool\mcbot_tool.py log tail
```

Do not pipe them through `| Out-String` in a foreground console.

## Gametests

```bash
python tool/mcbot_tool.py build runGameTest   # runs the suite, auto-exits
```

Exit code = number of failed required tests (0 on green). The server
runs headless and as fast as the CPU allows - a full boot plus suite is
roughly 30-60s.

Every finished run writes a receipt to
`qa-results/engine-runs/gametest-<stamp>.json` (scenario total, failed
list, git rev, GREEN flag). Receipts are the currency record behind
H-R5's verification discipline: `status` renders the newest one plus
how many later commits touched `adapter/` or `gametest/` since - a
non-zero count with a verified claim pending means rerun before the
claim. Receipt writing is telemetry; a receipt failure warns and
never blocks the run.

**Structure templates**: MC 1.20.1 ships no empty template. The gametest
framework resolves `mcbotserver:<name>` through StructureTemplateManager
first, then falls back to `gameteststructures/<name>.snbt` relative to
the server working directory (`run/`). Our template lives at repo-root
`gameteststructures/empty16x8x16.snbt`; copy it to
`run/gameteststructures/` once after a fresh clone:

```powershell
New-Item -ItemType Directory -Force run\gameteststructures | Out-Null
Copy-Item gameteststructures\*.snbt run\gameteststructures\
```

SNBT gotcha: `size` must be an untyped list (`[16, 8, 16]`), not an
IntArrayTag (`[I; 16, 8, 16]`) - StructureTemplate.load reads it with
`getList("size", 3)` and silently gets ZERO from the typed form, which
crashes every test with "Failed to load structure".

## First launch after a clean

The first `runClient` after `build clean` re-runs NeoForm decompilation
(roughly 5 extra minutes). `compileJava` / `jar` stay fast because the
artifact task is shared and cached.

Related: [Toolchain Reference](../reference/toolchain.md)
