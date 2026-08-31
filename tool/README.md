# tool/

Central development toolbox for mc-bot-server.

**Main entry point**: [`mcbot_tool.py`](./mcbot_tool.py) — a Python CLI
that unifies build / test / static analysis / logs / process management /
multi-agent concurrency gating.

> Multi-agent collaboration rules live in the root `AGENTS.md`
> (§0.2 mandatory rules). This README is the human-facing quick
> reference; the authoritative gate postures live in
> [`doc/guide/build-and-run.md`](../doc/guide/build-and-run.md).

## Quick start — the daily loop

```bash
# Compile Java (fast sanity check, seconds)
python tool/mcbot_tool.py build compile

# Run tests — rides the hard style gates (checkstyle + spotless)
python tool/mcbot_tool.py test

# Full static-analysis verdict: PMD + CPD red walls, SpotBugs,
# Error Prone / NullAway on the -Plint compiles
python tool/mcbot_tool.py lint

# Package mod jar (includes reobf required by legacy Forge)
python tool/mcbot_tool.py build jar

# Launch MC client / dedicated server / gameTest server (long-running;
# each holds its own run.<task> lock until MC exits)
python tool/mcbot_tool.py build runClient
python tool/mcbot_tool.py build runServer
python tool/mcbot_tool.py build runGameTest
```

Passthrough works everywhere flags are needed:

```bash
python tool/mcbot_tool.py build compile -- --info --stacktrace
python tool/mcbot_tool.py test -- --tests "*ReflexChainGateTest*"
```

## Subcommand map

| Command | What it does |
|---|---|
| `build <sub>` | `compile` / `jar` / `build` / `clean` / `sync`, or launch `runClient` / `runServer` / `runData` / `runGameTest` — all behind the concurrency gate |
| `test` | JUnit suite; **rides checkstyleMain/Test + spotlessCheck as hard gates** |
| `lint` | one-command static-analysis verdict (`gradle qualityCheck -Plint --continue`): compiles both source sets, runs PMD + CPD red walls and the SpotBugs dashboard |
| `gradle <args>` | any other gradle invocation with the same lock (opt-in analyzer rounds: `-Plint pmdMain ...`) |
| `status` | one-page view: locks, live processes, last log path, engine-receipt currency (newest gametest receipt vs later adapter/gametest commits) |
| `log tail\|cat\|list` | recent output / full log by task fragment / log inventory |
| `lock status\|clear` | who holds what; force-clear a dead holder |
| `proc list\|killdaemon` | java/gradle process census; daemon kill requires `--yes` |
| `doc ...` | documentation health: `list` / `check` / `touch` / `new` / `index` |
| `capability ...` | convergence matrix over the bot's behavior faces (SQLite, see next section) |
| `tasks` / `deps` | raw gradle task & dependency listings |

Gate postures (what fails where, and why) are pinned by
`LintPostureGateTest` and documented in the build-and-run guide —
a posture change is a ruling, made in those three places at once.

## Capability matrix (`capability ...`)

One SQLite DB (`tool/.runtime/mcbot.db`, WAL) folds the bot's
player-behavior faces against test specs, gametest methods, and
engine receipts. **Doctrine: one fact class, one home, one write
path — the DB is a disposable join cache.** Committed artifacts are
the truth; gates never read the DB (CI has no `.runtime`), they
parse committed text.

| Fact class | Home (committed) | Collector |
|---|---|---|
| capability catalog + harness path axis | seed (from `player-behavior-RE.md` + `HARNESS_PATHS`) | `init` (refreshes catalog, never statuses) |
| test SPECIFICATIONS (TC-*) + their links | `qa-results/ranged-survival/qa-test-cases-ranged-survival.csv` (10-col, explicit `capability_id`) | `qa-import` |
| test IMPLEMENTATIONS (GT-*) | gametest source | `scan-gametest` (owns lifecycle incl. pruning) |
| engine run evidence | `qa-results/engine-runs/*.json` (H-R5 currency) | auto-record + `backfill` |
| wire-surface evidence (BD-*) | `qa-results/boundary-d/receipt-*.json` | `backfill` (kind='wire' rows; PASS/RED-CONFIRMED green, FAIL red) |
| status rulings + non-CSV links | `qa-results/capability-state.json` | write-through via `set`/`link`; `restore` for rebuilds |

Vocabulary everywhere: **faces** (capabilities) / **specs** (TC) /
**impls** (GT) / **runs** (receipts). Two status axes per face:
`implementation_status` is a DECLARED human ruling (renders
`shipped*` — receipts cannot judge vanilla parity), and the
**evidence axis** (GREEN / RED / UNTESTED) is DERIVED from receipts
on every read, never stored. "Honest convergence" = how many
declared-shipped faces carry green evidence.

| Command | What it does |
|---|---|
| `capability init` / `db-status` | seed the faces / show table health |
| `capability overview` / `list` / `status <id>` / `gaps` | macro counts, filtered lists, one face's detail, gap inventory |
| `capability set <id> --status ... --verify` | flip a status (audited transition) — regenerates the state file in the same command |
| `capability qa-import <csv>` | import spec rows; the CSV is authoritative for spec links (re-import overwrites verb edits; blank/unknown ids → unlinked) |
| `capability scan-gametest` | sync impl rows from source: insert/update/prune; manual links and their `link_source` survive |
| `capability link <case> <cap>` | manual triage (impls mainly; spec links belong in the CSV) — write-through |
| `capability unlinked` | split by kind: unlinked specs vs unlinked impls are two different problems |
| `capability backfill` | mirror committed receipts into the DB (idempotent): engine-runs (gametest; re-parses surviving logs for failed names lost to the pre-6583cd4 regex bug) + boundary-d wire runs |
| `capability paths` | face → boundary-D path axis (18/35 mapped, curated in seed), pathless review list, wire-run evidence streak |
| `capability ref-generate` / `ref-import` / `ref-coverage` | the vanilla action baseline: **machine-enumerated** from the decompiled tree (every item class overriding use/useOn/releaseUsing/finishUsingItem — 55 classes, file-anchored, complete by construction) → `qa-results/vanilla-reference/inventory.json` → DB; face-map.json is the one curated layer (class → face); coverage = mapped/unmapped with a falsifiable denominator (currently 14/55 mapped, 41 unmapped engine actions) |
| `capability diff [--since YYYY-MM-DD]` | what changed: status transitions, run green/red split + scenario growth, RED details with per-scenario failures, new faces |
| `capability domain <category>` | per-face evidence: status, SPECS/IMPLS counts, NO-SPEC / NO-IMPL / DEVIATION flags, failure history, domain green streak |
| `capability restore` | apply the committed overlay after a rebuild |

Rebuild recipe after losing `.runtime`:
`capability init` → `qa-import` each CSV → `scan-gametest` →
`backfill` → `restore`.

Known honest limits: green evidence is run-granular (Forge logs
failures per scenario, never passes — a face absent from a run's
failure list passed); the newest engine run decides a face's
evidence state, and no false greens are claimed by walking back past
a red newest; per-face staleness needs source_paths (0/35 curated
today — deferred until the catalog grows them); the transitions
table was born 2026-08-31; impl auto-links are keyword guesses —
audit with `capability status <id>` before trusting per-face
rollups.

Implementation lives in `tool/mcbot/` (split from the old
monolithic `mcbot_tool.py`): `paths/config/gradle/lock/proc/engine/
docs/cli` plus the `capability/` subpackage. Tests:
`python tool/test_capability.py`.

## When a build fails

```bash
python tool/mcbot_tool.py log tail -n 100   # recent output
python tool/mcbot_tool.py log cat compile   # full log by task fragment
python tool/mcbot_tool.py status            # lock + processes + last log
```

## When the lock or daemon misbehaves

```bash
python tool/mcbot_tool.py lock status       # BUSY pid=... alive=? cmd=...
python tool/mcbot_tool.py lock clear        # holder confirmed dead only
python tool/mcbot_tool.py proc list         # every java/gradle process
python tool/mcbot_tool.py proc killdaemon --yes   # destructive
```

Lock namespaces: everything writing `build/` serializes on the global
`build` lock; long-running game launches hold their own `run.<task>`
locks instead, so a dedicated server and a dev client coexist (they
only read build outputs).

## Background runs with live logs

Never pipe a long-running launch through a foreground console
(`... | Out-String` can stall the child on console buffers). Use
`Start-Process`:

```powershell
$log = "D:\mc-bot-server\tool\.runtime\runclient-$(Get-Date -Format yyyyMMdd-HHmmss).log"
$proc = Start-Process `
  -FilePath "python" `
  -ArgumentList "tool/mcbot_tool.py","build","runClient" `
  -WorkingDirectory "D:\mc-bot-server" `
  -RedirectStandardOutput "$log.stdout" `
  -RedirectStandardError  "$log.stderr" `
  -NoNewWindow -PassThru

# Follow progress elsewhere:
Get-Content "$log.stdout" -Wait

# When done:
$proc.WaitForExit(); $proc.ExitCode
```

## Why not `./gradlew.bat`

The wrapper jar was missing `Main-Class` and exited silently, which made
direct debugging painful. `mcbot_tool.py` locates gradle itself:

1. Reads the gradle version from `gradle/wrapper/gradle-wrapper.properties`
2. Globs `~/.gradle/wrapper/dists/gradle-<ver>-bin/*/gradle-<ver>/bin/gradle.bat`
   for an already-downloaded distribution
3. Falls back to `gradle` on `$PATH`
4. Or uses whatever `$MCBOT_GRADLE` points at

It never touches `gradlew.bat`.

## Documentation management (doc/)

Docs live under [`doc/`](../doc/README.md), organized as
`architecture/ guide/ reference/ decisions/ archive/`. Every doc carries
front-matter (`title` / `last_verified` / `covers:`); **rot detection is
git-history-driven**: if any path in `covers:` changed after the verified
date, `doc check` flags DRIFT.

```bash
python tool/mcbot_tool.py doc list            # health overview
python tool/mcbot_tool.py doc check           # rot audit (exit 1 on ERR; usable as a gate)
python tool/mcbot_tool.py doc touch toolchain # re-mark verified after editing covered code
python tool/mcbot_tool.py doc new decisions my-idea --title "My Idea"   # scaffold (ADR numbers auto-assigned)
python tool/mcbot_tool.py doc index           # regenerate the doc/README.md index
```

Daily rhythm: edit covered code → update the covering doc → `doc check`
all green → `doc touch <name>`.

## Driving the bot over RCON

[`rcon.py`](./rcon.py) is a stdlib-only Source-RCON client for driving
the bot through the `/bot` command surface (single-line JSON in and
out; see `adapter/BotCommands.java`). One-time setup:

```bash
# 1) run/server.properties: enable-rcon=true, set rcon.password
# 2) store the same credentials locally (gitignored):
python tool/rcon.py init
```

A driving session gets its own folder under `tool/sessions/`
(gitignored telemetry):

```bash
python tool/rcon.py new-session survival10          # create session dir
python tool/rcon.py --session <dir> "/botspawn"     # log a command
python tool/rcon.py poll <dir>                      # drain /bot events,
                                                    #   append events.jsonl,
                                                    #   advance cursor.txt
```

Session contents: `session.json` (metadata), `events.jsonl` (one line
per poll), `commands.jsonl` (every issued command + response),
`cursor.txt` (drain position), and a hand-written `verdict.md` when the
run concludes. Copy a verdict out of the folder explicitly if it needs
to be tracked.

## Pinning the dev server in the client's server list

The dev server listens on a custom port (see `server-port` in
`run/server.properties`, currently 25585 - NOT the 25565 default), and
the client's server list accumulates hidden direct-connect entries
where a bare `localhost` connects to the default port. Joining a
foreign saved server by mistake trips a Forge mod-mismatch crash.
After changing the port or once per world, run with the client
CLOSED:

```bash
python tool/dev_server_entry.py            # defaults to localhost:25585
python tool/dev_server_entry.py myhost:25585
```

It pins one visible `mcbot-dev` entry at slot 0, folds duplicate
loopback spellings, writes a `.bak` next to the original, and leaves
every non-loopback entry untouched.

## Troubleshooting checklist

- `gradle not found` → set `MCBOT_GRADLE` or fix PATH
- `BUSY: holder pid=...` → `lock status`; holder dead → `lock clear`
- First-build OOM → raise `-Xmx1G` to `-Xmx2G` in `gradle.properties`
- SpotBugs/PMD "SKIPPED" during a manual run → they ride `-Plint`;
  use the `lint` verb
- `BUILD FAILED` with no lead → `log cat <task>`, then hand off to the
  coordinating agent
