#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Unit tests for the mcbot capability matrix and engine log parsing.

Run: python tool/test_capability.py

Uses throwaway SQLite databases (the ``db_path`` parameter flows
through every capability API) — no touching tool/.runtime/mcbot.db,
no touching qa-results/.
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from mcbot.capability.backfill import backfill_receipts
from mcbot.capability.db import get_connection, init_db
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


class BackfillTest(unittest.TestCase):
    """Receipt backfill: idempotent, preserves timestamps, links cases."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"

    def tearDown(self):
        self._tmp.cleanup()

    def _receipt(self, name: str, **over) -> None:
        data = {
            "schema": 1, "task": "runGameTest", "log": "", "git_rev": "abc1234",
            "finished_at": "2026-08-30T10:00:00", "scenarios_total": 52,
            "failed_count": 0, "failed": [], "green": True,
        }
        data.update(over)
        (self.dir / f"gametest-{name}.json").write_text(
            json.dumps(data), encoding="utf-8", newline="\n"
        )

    def test_idempotent_and_links_cases(self):
        init_db(self.db)
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, status, created_at, updated_at) "
                "VALUES ('GT-BotLocomotionGameTests-climbsLadderToPlatform', 't', "
                "'not_executed', 'x', 'x')"
            )
            conn.commit()
        self._receipt("20260830-100000", failed_count=1,
                      failed=["climbsladdertoplatform"], green=False)
        self._receipt("20260830-110000")

        first = backfill_receipts(self.dir, db_path=self.db)
        self.assertEqual(first["inserted"], 2)
        self.assertEqual(first["case_rows"], 1)

        second = backfill_receipts(self.dir, db_path=self.db)
        self.assertEqual(second["inserted"], 0)
        self.assertEqual(second["skipped"], 2)

        with get_connection(self.db) as conn:
            # the lowercase structure name must fold onto the camelCase
            # GT id via the ASCII case-insensitive LIKE fallback
            row = conn.execute("SELECT test_case_id FROM test_case_runs").fetchone()
            self.assertEqual(row["test_case_id"], "GT-BotLocomotionGameTests-climbsLadderToPlatform")
            # historical finish time is preserved, not stamped with now
            row = conn.execute(
                "SELECT finished_at, green FROM test_receipts "
                "WHERE run_id = 'gametest-20260830-100000'"
            ).fetchone()
            self.assertEqual(row["finished_at"], "2026-08-30T10:00:00")
            self.assertEqual(row["green"], 0)


if __name__ == "__main__":
    unittest.main()
