# AGENTS.md — mc-bot-server Specification

> Mandatory specification for every AI agent and human collaborator
> in this repository: a checklist of hard constraints, not a design
> mirror. Where docs conflict, `doc/architecture/boundaries.md` and
> `doc/decisions/*.md` win for what they freeze, then this file, then
> reference notes.
>
> Project goal: a **mechanical bot device** for Minecraft 1.20.1
> (Forge) with a clean LLM-harness separation - the mod is the device
> layer, an external harness drives it through boundary D. Cold-start
> reading order lives in [doc/guide/onboarding.md](doc/guide/onboarding.md).

## 0. Project Identity

| Item | Value | Source of truth |
|------|-------|------------------|
| modid | `mcbotserver` | `gradle.properties` `mod_id`; never hardcode |
| mod name | `MC Bot Server` | `gradle.properties` `mod_name` |
| license | MIT | `mod_license`; `LICENSE` at repo root |
| version | `0.1.0` (dev) | `mod_version` |
| Java package | `com.mcbot.mcbotserver.<module>` | `mod_group_id`; `<module>` single level (§1.1) |
| Java / MC / Forge | 17 / 1.20.1 / 47.4.10 | `build.gradle` toolchain, `minecraft_version`, `forge_version` (ADR-0001) |
| Mappings / Gradle | Parchment 2023.09.03 / 8.x wrapper | `parchment_*`, `gradle-wrapper.properties` |

### 0.1 Key Paths

| Purpose | Path |
|---------|------|
| Repo | `D:/mc-bot-server/` |
| Mod entry class | `src/main/java/com/mcbot/mcbotserver/McBotServer.java` |
| Build wiring / properties | `build.gradle`, `gradle.properties` |
| Workflow toolbox | `tool/mcbot_tool.py`; harness reference implementation in `tool/harness/mc.py` |
| Doc root | `doc/` (architecture / decisions / guide / reference); index auto-generated as `doc/README.md` via `doc index` |
| Live contract | `doc/architecture/boundaries.md`; verdicts in `doc/architecture/ledger.md` |
| Implementation checklist | `doc/guide/workplan.md` (authoritative, dependency-ordered) |
| Reference notes (read-only) | `doc/reference/{baritone,numen,disclosure-patterns}-notes.md` |
| Decompiled MC/Forge source | `D:/mc-decompiled/forge-1.20.1-47.4.10/` |

Reference routing: confirm any `net.minecraft.*` /
`net.minecraftforge.*` signature against the decompiled tree, which
is read-only - never copy from it, never let it shape `api/` + `core/`.
Project-side naming follows this repo's Parchment version when the two
disagree. Own sources first, reference notes second, decompiled tree
last.

### 0.2 Tool Rules

- Content search is `rg` (ripgrep). Fallback chain: `grep -rl`,
  then `findstr /s /i /m`. Never let a failed search block the task.
- ALL build / test / run / doc commands go through
  `python tool/mcbot_tool.py ...`, never direct `./gradlew` or bare
  `gradle` - direct calls bypass the cross-process lock other agents
  rely on. The commands every session needs:

```bash
python tool/mcbot_tool.py build compile   # verify before any commit
python tool/mcbot_tool.py test            # no commit on red
python tool/mcbot_tool.py status          # locks + live runs + docs health
python tool/mcbot_tool.py doc check       # doc rot gate
```

- Lock etiquette: everything writing `build/` serializes on one
  global lock; each long-running game task holds its own
  `run.<task>` lock until MC exits. A second caller of the same
  namespace fails fast with `BUSY: holder pid=...`; retry dead
  holders (auto takeover) or `lock clear`. Never kill another
  agent's processes to grab a lock. Compiling while a run is alive
  can fail on Windows file locks - stop the run or wait it out.
- Read-only subcommands (`tasks`, `deps`, `status`, `log *`,
  `lock status`, `proc list`, `doc *`) need no lock.
- Everything else - full subcommand table, flag passthrough,
  failure-symptom table, background-run pattern: `tool/README.md`.

### 0.3 Doc Governance

Every doc carries front-matter (`title`, `last_verified`, `covers:`)
and is audited by `doc check` - broken front-matter or drifted covers
block the PR. All repository markdown is **English-only: zero CJK
characters**, comments included (see §1.4.1); citations from
non-English sources are translated, never reproduced.

Edit policy by subtree:

- `boundaries.md` - live contract for boundaries A/B/C/D; signatures
  frozen, vocabulary may grow; its decision index resolves every
  `decision N` citation.
- `ledger.md` - append-only verdicts. Entries extend only forward;
  amendments annotate both entries; absorbing an issue means
  archiving it in the same commit.
- `harness-interaction.md` - canonical interaction model; clause
  changes land there first, the ledger summary follows; new task
  verb / path root / consumer kind reopens its audit.
- `decisions/0001..0005` - frozen ADRs under the current freeze
  order; new architectural verdicts become ledger entries instead.
- `workplan.md` - items reorder by dependency, never by date; do not
  start an item before its blockers are done.
- `reference/*-notes.md` - read-only; updates require a new doc plus
  archiving the old one.
- `issues/NNNN-slug.md` - filenames use 4-digit zero-padded numbers,
  globally unique, never reused. Status vocabulary:
  `open` / `parked` / `resolved`. Bodies hold problem / ruling /
  contract / deferred-with-reopen only - progress narratives belong
  to commits and the ledger. Resolution moves the file to
  `issues/archive/` with `superseded_by:` pointing at the absorbing
  record; an exceptional design outcome promotes to an ADR behind a
  pointer stub. Each issue cross-references a function-map row or
  boundary section in `covers:`.

Workflow: edit covered code -> update the covering doc in the same
change -> `doc check` -> `doc touch <name>` on what you re-verified.
Unsure which doc to update? Run `doc check` first.

### 0.4 Boundaries Quick Reference

The four boundaries are the only contract that matters; full texts in
`doc/architecture/boundaries.md`.

| ID | Rule |
|---|---|
| **A** | `WorldView` read-only; `Actor` emits intents only; `core/` has zero MC imports |
| **B** | Process side-effect-free -> `Directive{Goal, Overrides}`; Behavior monopolizes `Actor` via per-channel claims; feedback closes via `ExecutionReport` |
| **C** | Reflex ticks first; a non-null reflex skips the mission; preemption carries `InterruptionContext`; resume revalidates world |
| **D** | Bot is harness-blind; wire = `submit`/`cancel` + event stream (`statusSnapshot(sinceEventId)`) + state snapshot |

Rule of thumb: if a change seems to require opening an ADR or
amending a frozen boundary, stop and file a workplan follow-up - the
Stage reviews lift freezes. Harness-side changes (mc.py, skills, any
boundary-D consumer) review against
[harness-interaction.md](doc/architecture/harness-interaction.md)
with boundary severity: its anti-patterns are reject reasons, not
advice.

## 1. Code Style

### 1.1 Packages and Classes

- Package prefix `com.mcbot.mcbotserver.<module>` - `<module>` is ONE
  level (`api`, `core`, `pathing`, `tick`, `mixin`, ...). Nesting a
  module under another is a namespace defect
  (`core.api` is illegal).
- Layers: `api/` = pure interfaces + DTOs, zero MC imports (the
  layer a non-JVM harness could bind to); `core/` = implementations,
  still zero MC imports; ONLY `adapter/` (and mixin-only code under
  `mixin/`) may import `net.minecraft.*` / `net.minecraftforge.*`.
- Class names describe behavior (`BotController`), not pattern
  (`BotControllerImpl` banned unless the strategy IS the contract -
  `LoadedOnlyView` vs `CachedNavView`). Mixin packages match the
  `package` field of the mixin JSON.
- Capability / handler / channel classes use the three-part layout
  separated by `// =====` dividers: static attach surface ->
  interface -> default impl -> provider/adapter.

### 1.2 Naming

- Constants `UPPER_SNAKE_CASE`; everything else lowerCamelCase,
  methods verb-led; no decompile residue (`var0`, `m_262824_`).
- Data carriers are `record`s; sum types are sealed interfaces with
  permitted records. Reach for `enum` only when values carry no
  fields.
- Formatting: canonical form comes from Spotless with
  `palantir-java-format` (`python tool/mcbot_tool.py gradle
  spotlessApply`; lint-gated width check at 120 via checkstyle) -
  never hand-reformat. Still review-enforced: `final` everywhere
  applicable, trailing commas on multi-line lists.
- Imports explicit, no wildcards below 20 classes from one package;
  ordering is whatever the formatter emits (single source of truth).

### 1.4 Comments (English-only, intent-first)

- Code says what/how, comments say why, Git records evolution. A
  comment that no longer matches its code is a defect fixed in the
  same commit; if *what* needs explaining, refactor instead. One
  concept, one word, everywhere.
- **English only** in every comment and Javadoc (**1.4.1**) -
  exceptions are data, not comments: game-content strings verbatim
  (`Component.literal("Stuck")`) and byte-fidelity registry keys.
- Class-level Javadoc states purpose plus implemented boundaries/ADRs.
  Public/protected members get Javadoc with tag order `@param` ->
  `@return` -> `@throws`, nullability stated (`never null` /
  `may return null`), first sentence third-person ending in a period.
- Frozen-boundary write site: `// contract: see ADR-NNNN` or
  `// contract: see boundaries.md §X` - the **1.4.3.1** marker
  convention, presence-gated by `BoundaryContractMarkerTest`
  (inventory pinned; growing it is a review checkpoint). A change to
  a frozen contract updates the ADR/boundary doc FIRST, then the
  marker - never the reverse.
- Method called from `BotController.onTick()`:
  `// invariant: see ADR-0005`.
- Off-thread entry or thread-crossing capture:
  `// runs on <thread>; do not access <off-thread state>`.
- Stub: `// TODO <subsystem>: <what remains> (Ref: <issue>)` -
  silent stubs are bugs, ownerless TODOs are noise.
- Deliberate deviation from Baritone/Numen gets one line saying what
  differs and why, so nobody "fixes" it back.
- Forbidden: restating code, commented-out code, author/date
  graffiti, emoji/slang.

## 3. Workflow

### 3.1 Workplan Discipline

An item ships when its layer-1 (offline JUnit) test proves the
contract - plus a gametest where real engine behavior is the
contract. A checked-off item that regresses is un-checked and requeued
at the bottom of its stage.

### 3.2 Per-Class Procedure

Read the owning contract first -> write the Javadoc skeleton before
bodies (the design surface) -> write the failing test (offline against
mocks whenever possible) -> implement no more than the test requires ->
`build compile` + `test` green, no commit on red -> update the
covering doc, markers included, then `doc check`.

### 3.3 Commits

Conventional Commits, one concern per commit, every commit compiles
and passes tests (history stays switchable). Repo-specific rules on
top of the public spec:

- Comment/Javadoc-only rounds are `docs(javadoc)`; tooling/tests/
  config land in `chore`/`test`, never inside `docs` or `fix`.
- A boundary change is `refactor(boundary-<letter>)` and updates the
  matching `boundaries.md` rows in the SAME commit.
- The build result line goes in the body, e.g.
  `BUILD: compileJava + 4 L1 tests green`.
