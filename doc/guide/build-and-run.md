---
title: Build & Run Guide
last_verified: 2026-08-21
covers:
  - tool/mcbot_tool.py
---

# Build & Run Guide

All build/test/run entry points go through `tool/mcbot_tool.py`.
Never call `gradlew.bat` or `gradle` directly — it bypasses the
cross-process lock that multi-agent collaboration depends on.
Full rules live in the root `AGENTS.md` (§0.2 mandatory rules,
§3.5 full tool reference); this page is the quick path.

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

`runClient` / `runServer` hold the lock until the game window closes.
Launch them in the background and follow logs instead of blocking:

```powershell
Start-Process python -ArgumentList 'tool\mcbot_tool.py','build','runClient' -WindowStyle Hidden
python tool\mcbot_tool.py log tail
```

Do not pipe them through `| Out-String` in a foreground console.

## First launch after a clean

The first `runClient` after `build clean` re-runs NeoForm decompilation
(roughly 5 extra minutes). `compileJava` / `jar` stay fast because the
artifact task is shared and cached.

Related: [Toolchain Reference](../reference/toolchain.md)
