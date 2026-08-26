---
title: Build & Run Guide
last_verified: 2026-08-24
covers:
  - tool/mcbot_tool.py
---

# Build & Run Guide

All build/test/run entry points go through `tool/mcbot_tool.py`.
Never call `gradlew.bat` or `gradle` directly — it bypasses the
cross-process lock that multi-agent collaboration depends on.
Full rules live in the root `AGENTS.md` (§0.2 mandatory rules,
§3.5 command essentials); this page is the quick path, and
`tool/README.md` carries the full subcommand table.

## Everyday commands

```bash
python tool/mcbot_tool.py build compile   # fast compile check (seconds)
python tool/mcbot_tool.py build jar       # produce mod jar in build/libs/
python tool/mcbot_tool.py test            # JUnit tests
python tool/mcbot_tool.py status          # lock + processes + last log
```

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
