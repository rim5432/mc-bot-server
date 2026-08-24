# tool/

Central development toolbox for mc-bot-server.

**Main entry point**: [`mcbot_tool.py`](./mcbot_tool.py) — a Python CLI
that unifies build / test / logs / process management / multi-agent
concurrency gating.

> Multi-agent collaboration rules live in the root `AGENTS.md`
> (§0.2 mandatory rules, §3.5 full tool reference). This README is the
> human-facing quick reference.

## Quick start

```bash
# Full CLI help
python tool/mcbot_tool.py --help

# Compile Java
python tool/mcbot_tool.py build compile

# Package mod jar
python tool/mcbot_tool.py build jar

# Full build (compile + test + jar)
python tool/mcbot_tool.py build build

# Launch MC client (long-running; close the window to end)
python tool/mcbot_tool.py build runClient

# Run tests
python tool/mcbot_tool.py test

# Pass through gradle flags
python tool/mcbot_tool.py build compile -- --info --stacktrace
```

## Debug recipes

**When a build fails**:

```bash
# 1) Tail of the most recent build log
python tool/mcbot_tool.py log tail

# 2) Full log for the compile task
python tool/mcbot_tool.py log cat compile

# 3) One-page status: lock, live java processes, last log path
python tool/mcbot_tool.py status
```

**When blocked by the lock**:

```bash
# See who holds the lock
python tool/mcbot_tool.py lock status

# If the holder process is dead, force-clear
python tool/mcbot_tool.py lock clear

# Or simply re-run the build; a stale lock is taken over automatically
python tool/mcbot_tool.py build compile
```

**When the gradle daemon is stuck**:

```bash
# List all java/gradle processes
python tool/mcbot_tool.py proc list

# Kill the daemon (destructive; requires --yes)
python tool/mcbot_tool.py proc killdaemon --yes
```

## Background runs with live logs

`runClient` / `runServer` are long-running and hold the lock until MC
exits. **Never pipe them through a foreground console**
(`... | Out-String` can stall the child on console buffers). Use
`Start-Process` instead:

```powershell
$log = "D:\mc-bot-server\tool\.runtime\runclient-$(Get-Date -Format yyyyMMdd-HHmmss).log"
$proc = Start-Process `
  -FilePath "python" `
  -ArgumentList "tool/mcbot_tool.py","build","runClient" `
  -WorkingDirectory "D:\mc-bot-server" `
  -RedirectStandardOutput "$log.stdout" `
  -RedirectStandardError  "$log.stderr" `
  -NoNewWindow -PassThru

# In another PowerShell window:
Get-Content "$log.stdout" -Wait

# When done:
$proc.WaitForExit(); $proc.ExitCode
```

## Why not `./gradlew.bat`

The wrapper jar was missing `Main-Class` and exited silently, which made
direct debugging painful. `mcbot_tool.py` locates gradle itself:

1. Reads the gradle version from `gradle/wrapper/gradle-wrapper.properties`
2. Globs `~/.gradle/wrapper/dists/gradle-<ver>-bin/*/gradle-<ver>/bin/gradle.bat`
   for an already-downloaded full distribution
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
- `BUILD FAILED` with no lead → `log cat <task>`, then hand off to the coordinating agent
