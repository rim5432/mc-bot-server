#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mcbot_tool.py - centralized dev-workflow toolbox for mc-bot-server
==================================================================

Agents and humans run build / test / logs / process management /
concurrency coordination through this one CLI.
Do not call `./gradlew ...` directly - that path has bitten before.

This file is a thin entry point. All implementation lives in the
``mcbot/`` package alongside this file:

  mcbot/paths.py    - project roots, runtime dir, lock file paths
  mcbot/config.py   - gradle task constants, gradle.properties reader
  mcbot/gradle.py   - gradle auto-discovery, hang-proof log runner
  mcbot/lock.py     - cross-process file locks, PID liveness checks
  mcbot/proc.py     - java/gradle process census
  mcbot/engine.py   - GameTest receipt writer + currency checker
  mcbot/docs.py     - documentation rot detection (pure functions)
  mcbot/cli.py      - cmd_* handlers + argparse wiring

Design notes (see tool/README.md for the full reference):
  1. hang-proof log: Popen + realtime readline dual-writes to the
     console and a file, so gradle output and the console stay truly
     in sync and a full pipe buffer cannot block.
  2. cross-process file locks (tool/.runtime/<name>.lock): tasks
     that write build/ share one global `build` lock; each long-running
     task holds its own `run.<task>` lock. A second caller in the same
     namespace fails fast instead of quietly contending.
  3. stale lock takeover: if the holding PID is dead, take over
     automatically or prompt to clear.
  4. gradle auto-discovery: $MCBOT_GRADLE > local wrapper dist > PATH.
  5. build / test default to --no-daemon to avoid daemon residue.
  6. zero external dependencies: stdlib only, across Windows / macOS / Linux.

Usage: see `python tool/mcbot_tool.py --help` or tool/README.md.
"""
from __future__ import annotations

import sys

from mcbot.cli import main

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[mcbot] interrupted", file=sys.stderr)
        sys.exit(130)
