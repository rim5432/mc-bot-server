#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Unit tests for the mcbot capability matrix and engine log parsing.

Run: python tool/test_capability.py

Uses throwaway SQLite databases (the ``db_path`` parameter flows
through every capability API) — no touching tool/.runtime/mcbot.db,
no touching qa-results/.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mcbot.engine import parse_run_log


def _write_log(lines: list[str]) -> Path:
    tmp = tempfile.NamedTemporaryFile(
        "w", suffix=".log", delete=False, encoding="utf-8", newline="\n"
    )
    tmp.write("\n".join(lines) + "\n")
    tmp.close()
    return Path(tmp.name)


class ParseRunLogTest(unittest.TestCase):
    """The single parse behind JSON receipts and the DB mirror."""

    def test_extracts_names_from_timestamped_lines(self):
        # Real shape: every log line carries the
        # "[HH:MM:SS] [thread/LEVEL] [logger]:" prefix, so a regex
        # anchored at line start never sees the dash list.
        log = _write_log([
            "[18:28:44] [Server thread/ERROR] [minecraft/LogTestReporter]: climbsladdertoplatform failed! nope",
            "[18:28:47] [Server thread/INFO] [minecraft/GameTestServer]: ========= 76 GAME TESTS COMPLETE ======================",
            "[18:28:47] [Server thread/INFO] [minecraft/GameTestServer]: 1 required tests failed :(",
            "[18:28:47] [Server thread/INFO] [minecraft/GameTestServer]:    - climbsladdertoplatform",
            "[18:28:47] [Server thread/INFO] [minecraft/GameTestServer]: 1 optional tests failed",
            "[18:28:47] [Server thread/INFO] [minecraft/GameTestServer]:    - bowchargedshotdamagesdistanttarget",
        ])
        verdict = parse_run_log(log)
        self.assertEqual(verdict["total"], 76)
        self.assertEqual(verdict["failed_count"], 1)
        self.assertEqual(verdict["failed"], ["climbsladdertoplatform"])
        self.assertEqual(verdict["failed_optional"], ["bowchargedshotdamagesdistanttarget"])
        self.assertFalse(verdict["green"])

    def test_multiple_required_before_optional(self):
        log = _write_log([
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]: ========= 79 GAME TESTS COMPLETE ======================",
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]: 2 required tests failed :(",
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]:    - bowchargedshotdamagesdistanttarget",
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]:    - equipmentmirrorfeedsattributes",
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]: 1 optional tests failed",
            "[21:02:38] [Server thread/INFO] [minecraft/GameTestServer]:    - climbsladdertoplatform",
        ])
        verdict = parse_run_log(log)
        self.assertEqual(
            verdict["failed"], ["bowchargedshotdamagesdistanttarget", "equipmentmirrorfeedsattributes"]
        )
        self.assertEqual(verdict["failed_optional"], ["climbsladdertoplatform"])
        self.assertFalse(verdict["green"])

    def test_green_run(self):
        log = _write_log([
            "[20:00:00] [Server thread/INFO] [minecraft/GameTestServer]: ========= 75 GAME TESTS COMPLETE ======================",
            "[20:00:00] [Server thread/INFO] [minecraft/GameTestServer]: All required tests completed :)",
        ])
        verdict = parse_run_log(log)
        self.assertEqual(verdict["failed_count"], 0)
        self.assertEqual(verdict["failed"], [])
        self.assertEqual(verdict["failed_optional"], [])
        self.assertTrue(verdict["green"])

    def test_no_verdict_returns_none(self):
        log = _write_log([
            "[20:00:00] [main/INFO]: starting...",
            "FAILURE: Build failed with an exception.",
        ])
        self.assertIsNone(parse_run_log(log))

    def test_missing_log_returns_none(self):
        self.assertIsNone(parse_run_log(Path("does-not-exist.log")))


if __name__ == "__main__":
    unittest.main()
