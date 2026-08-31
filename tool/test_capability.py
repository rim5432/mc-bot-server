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
from mcbot.capability.gametest_scan import scan_gametests
from mcbot.capability.models import Capability
from mcbot.capability.qa_import import import_csv, link_case
from mcbot.capability.report import diff_since, domain_report, harness_axis
from mcbot.capability.repository import CapabilityRepository
from mcbot.capability.seed import seed_database
from mcbot.capability.state_export import export_state, restore_state
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
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, created_at, updated_at) "
                "VALUES ('GT-BotLocomotionGameTests-climbsLadderToPlatform', 't', 'impl', "
                "'gametest', 'not_executed', 'x', 'x')"
            )
            conn.commit()
        self._receipt("20260830-100000", failed_count=1,
                      failed=["climbsladdertoplatform"], green=False)
        self._receipt("20260830-110000")

        first = backfill_receipts(self.dir, db_path=self.db, boundary_d_dir=self.dir)
        eng = first["engine_runs"]
        self.assertEqual(eng["inserted"], 2)
        self.assertEqual(eng["case_rows"], 1)

        second = backfill_receipts(self.dir, db_path=self.db, boundary_d_dir=self.dir)
        self.assertEqual(second["engine_runs"]["inserted"], 0)
        self.assertEqual(second["engine_runs"]["skipped"], 2)

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


class StatusTransitionTest(unittest.TestCase):
    """update_status appends transitions; unchanged statuses do not."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db = Path(self._tmp.name) / "test.db"
        init_db(self.db)
        self.repo = CapabilityRepository(self.db)
        now = "2026-08-31T12:00:00"
        self.repo.upsert(Capability(
            id="combat.melee", name="Melee", category="combat",
            implementation_status="gap", created_at=now, updated_at=now,
        ))

    def tearDown(self):
        self._tmp.cleanup()

    def _transitions(self) -> list[dict]:
        with get_connection(self.db) as conn:
            return [
                dict(r) for r in conn.execute(
                    "SELECT * FROM capability_status_transitions ORDER BY id"
                ).fetchall()
            ]

    def test_change_records_one_transition_with_source(self):
        self.repo.update_status("combat.melee", "partial", source="manual")
        rows = self._transitions()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["old_status"], "gap")
        self.assertEqual(rows[0]["new_status"], "partial")
        self.assertEqual(rows[0]["source"], "manual")

    def test_same_status_records_nothing(self):
        self.repo.update_status("combat.melee", "gap")
        self.assertEqual(self._transitions(), [])

    def test_unknown_id_returns_false(self):
        self.assertFalse(self.repo.update_status("nope.nope", "shipped"))


class ReportTest(unittest.TestCase):
    """diff_since + domain_report fold receipts, case runs, transitions."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"
        init_db(self.db)
        now = "2026-08-31T12:00:00"
        CapabilityRepository(self.db).upsert(Capability(
            id="motion.sprint", name="Sprint", category="motion",
            implementation_status="partial", created_at=now, updated_at=now,
        ))
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, created_at, updated_at) "
                "VALUES ('GT-BotLocomotionGameTests-walksToBlock', 't', 'impl', "
                "'gametest', 'not_executed', 'x', 'x')"
            )
            conn.execute(
                "INSERT INTO test_receipts (run_id, test_type, finished_at, git_rev, "
                "total, passed, failed, green, created_at) VALUES "
                "('gametest-20260830-1','runGameTest','2026-08-30T10:00:00','r1',52,50,2,0,'x'),"
                "('gametest-20260831-1','runGameTest','2026-08-31T11:00:00','r2',52,52,0,1,'x')"
            )
            conn.execute(
                "INSERT INTO test_case_runs (test_case_id, receipt_id, result, created_at) "
                "VALUES ('GT-BotLocomotionGameTests-walksToBlock', 1, 'failed', 'x')"
            )
            conn.commit()
        # link the case so the failure is attributed to motion.sprint
        with get_connection(self.db) as conn:
            conn.execute(
                "UPDATE qa_test_cases SET capability_id = 'motion.sprint' "
                "WHERE id = 'GT-BotLocomotionGameTests-walksToBlock'"
            )
            conn.commit()

    def tearDown(self):
        self._tmp.cleanup()

    def test_diff_since_folds_runs_and_reds(self):
        d = diff_since("2026-08-30", db_path=self.db)
        self.assertEqual(d["runs"]["count"], 2)
        self.assertEqual(d["runs"]["red"], 1)
        self.assertEqual(len(d["red_details"]), 1)
        self.assertEqual(d["red_details"][0]["scenarios"][0]["test_case_id"],
                         "GT-BotLocomotionGameTests-walksToBlock")

    def test_diff_excludes_older_window(self):
        d = diff_since("2026-08-31", db_path=self.db)
        self.assertEqual(d["runs"]["count"], 1)
        self.assertEqual(d["runs"]["green"], 1)

    def test_domain_report_flags_and_streak(self):
        rep = domain_report("motion", db_path=self.db)
        face = rep["faces"][0]
        self.assertEqual(face["id"], "motion.sprint")
        self.assertEqual(face["case_count"], 1)
        self.assertEqual(face["impl_count"], 1)
        self.assertEqual(face["spec_count"], 0)
        self.assertIn(face["id"], rep["faces_no_spec"])
        self.assertNotIn(face["id"], rep["faces_no_impl"])
        self.assertEqual(len(face["failures"]), 1)
        self.assertIsNotNone(rep["last_red_in_domain"])
        self.assertEqual(rep["green_streak_since"], 1)

    def test_domain_report_unknown_category(self):
        self.assertIsNone(domain_report("nope", db_path=self.db))


class StateOverlayTest(unittest.TestCase):
    """export/restore round-trip: the durable copy of manual triage."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"
        self.overlay = self.dir / "capability-state.json"
        init_db(self.db)
        now = "2026-08-31T12:00:00"
        repo = CapabilityRepository(self.db)
        repo.upsert(Capability(
            id="dig.tool_speed", name="Tool speed", category="digging",
            implementation_status="shipped", created_at=now, updated_at=now,
        ))
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, status, created_at, updated_at) "
                "VALUES ('GT-BotDiggingGameTests-digsStoneFast', 't', 'not_executed', 'x', 'x')"
            )
            conn.execute(
                "UPDATE qa_test_cases SET capability_id = 'dig.tool_speed' "
                "WHERE id = 'GT-BotDiggingGameTests-digsStoneFast'"
            )
            conn.commit()

    def tearDown(self):
        self._tmp.cleanup()

    def _wipe_and_rebuild_without_triage(self):
        """Simulate the cliff: fresh DB, seed + scan only."""
        self.db.unlink()
        init_db(self.db)
        now = "2026-08-31T13:00:00"
        CapabilityRepository(self.db).upsert(Capability(
            id="dig.tool_speed", name="Tool speed", category="digging",
            implementation_status="gap", created_at=now, updated_at=now,
        ))
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, status, created_at, updated_at) "
                "VALUES ('GT-BotDiggingGameTests-digsStoneFast', 't', 'not_executed', 'x', 'x')"
            )
            conn.commit()

    def test_round_trip_recovers_manual_state(self):
        export_state(self.overlay, db_path=self.db)
        self._wipe_and_rebuild_without_triage()

        result = restore_state(self.overlay, db_path=self.db)
        self.assertEqual(result["statuses_applied"], 1)
        self.assertEqual(result["links_applied"], 1)

        cap = CapabilityRepository(self.db).get("dig.tool_speed")
        self.assertEqual(cap.implementation_status, "shipped")
        with get_connection(self.db) as conn:
            row = conn.execute(
                "SELECT capability_id, link_source FROM qa_test_cases "
                "WHERE id = 'GT-BotDiggingGameTests-digsStoneFast'"
            ).fetchone()
            self.assertEqual(row["capability_id"], "dig.tool_speed")
            self.assertEqual(row["link_source"], "manual")
            # the restore flip is auditable as a transition
            t = conn.execute(
                "SELECT source FROM capability_status_transitions"
            ).fetchone()
            self.assertEqual(t["source"], "restore")

    def test_restore_twice_is_idempotent(self):
        export_state(self.overlay, db_path=self.db)
        restore_state(self.overlay, db_path=self.db)
        second = restore_state(self.overlay, db_path=self.db)
        self.assertEqual(second["statuses_applied"], 0)
        self.assertEqual(second["links_applied"], 0)

    def test_csv_links_stay_out_of_the_overlay(self):
        # a CSV-owned spec link (its home is the CSV, not the overlay)
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, "
                "capability_id, link_source, created_at, updated_at) VALUES "
                "('TC-DIG-001', 'csv case', 'spec', 'gametest', 'not_executed', "
                "'dig.tool_speed', 'csv', 'x', 'x')"
            )
            conn.commit()
        result = export_state(self.overlay, db_path=self.db)
        import json as _json
        payload = _json.loads(self.overlay.read_text(encoding="utf-8"))
        case_ids = [l["case_id"] for l in payload["links"]]
        self.assertNotIn("TC-DIG-001", case_ids)
        self.assertEqual(payload["schema"], 2)


class ImportCsvV2Test(unittest.TestCase):
    """The English CSV: explicit capability_id, no guessing, spec kind."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"
        init_db(self.db)
        now = "2026-08-31T12:00:00"
        CapabilityRepository(self.db).upsert(Capability(
            id="combat.bow_draw", name="Bow draw", category="combat",
            implementation_status="shipped", created_at=now, updated_at=now,
        ))

    def tearDown(self):
        self._tmp.cleanup()

    def _csv(self, text: str) -> Path:
        p = self.dir / "cases.csv"
        p.write_text(text, encoding="utf-8", newline="\n")
        return p

    def test_declared_link_and_kind(self):
        p = self._csv(
            "case_id,capability_id,title,priority,test_type,status,preconditions,"
            "steps,expected_result,notes\n"
            "TC-COMBAT-001,combat.bow_draw,Bow full charge,P0,gametest,not_executed,"
            '"bot holds bow","hold USE 20 ticks","arrow speed 3.0","{""risk_refs"": [""bow-001""]}"\n'
            "TC-COMBAT-002,,Blank anchor,P1,gametest,not_executed,,\"\",\"\",,\n"
        )
        result = import_csv(p, db_path=self.db)
        self.assertEqual(result["inserted"], 2)
        self.assertEqual(result["linked"], 1)
        self.assertEqual(result["unlinked"], 1)
        with get_connection(self.db) as conn:
            r = conn.execute(
                "SELECT capability_id, link_source, kind, notes FROM qa_test_cases "
                "WHERE id = 'TC-COMBAT-001'"
            ).fetchone()
            self.assertEqual(r["capability_id"], "combat.bow_draw")
            self.assertEqual(r["link_source"], "csv")
            self.assertEqual(r["kind"], "spec")
            self.assertIn("bow-001", r["notes"])
            r = conn.execute(
                "SELECT capability_id, link_source FROM qa_test_cases "
                "WHERE id = 'TC-COMBAT-002'"
            ).fetchone()
            self.assertIsNone(r["capability_id"])
            self.assertIsNone(r["link_source"])

    def test_reimport_is_authoritative_over_verb_edits(self):
        p = self._csv(
            "case_id,capability_id,title,priority,test_type,status,preconditions,"
            "steps,expected_result,notes\n"
            "TC-COMBAT-001,combat.bow_draw,Bow full charge,P0,gametest,not_executed,"
            ",\"\",,\"\"\n"
        )
        import_csv(p, db_path=self.db)
        # a manual verb link on a spec row...
        link_case("TC-COMBAT-001", "combat.bow_draw", db_path=self.db)
        with get_connection(self.db) as conn:
            src = conn.execute(
                "SELECT link_source FROM qa_test_cases WHERE id = 'TC-COMBAT-001'"
            ).fetchone()["link_source"]
        self.assertEqual(src, "manual")
        # ...dies at re-import: the CSV is the single home for spec links
        import_csv(p, db_path=self.db)
        with get_connection(self.db) as conn:
            r = conn.execute(
                "SELECT link_source FROM qa_test_cases WHERE id = 'TC-COMBAT-001'"
            ).fetchone()
        self.assertEqual(r["link_source"], "csv")

    def test_unknown_capability_falls_to_unlinked(self):
        p = self._csv(
            "case_id,capability_id,title,priority,test_type,status,preconditions,"
            "steps,expected_result,notes\n"
            "TC-X-001,nope.nope,Typo link,P1,gametest,not_executed,,\"\",,\"\"\n"
        )
        result = import_csv(p, db_path=self.db)
        self.assertEqual(result["unlinked"], 1)
        self.assertEqual(result["invalid_caps"], ["TC-X-001->nope.nope"])

    def test_legacy_format_rejected(self):
        p = self._csv("用例ID,需求,标题\nTC-1,弓,弓测试\n")
        with self.assertRaises(ValueError):
            import_csv(p, db_path=self.db)


class GametestScanTest(unittest.TestCase):
    """Scanner owns the impl lifecycle: kind, link sources, prune."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"
        self.src = self.dir / "gametest"
        self.src.mkdir()
        init_db(self.db)
        now = "2026-08-31T12:00:00"
        CapabilityRepository(self.db).upsert(Capability(
            id="motion.sprint", name="Sprint", category="motion",
            implementation_status="shipped", created_at=now, updated_at=now,
        ))
        (self.src / "BotLocomotionGameTests.java").write_text(
            "public class BotLocomotionGameTests {\n"
            "    @GameTest\n"
            "    public static void sprintAwayFromDanger() {}\n"
            "}\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self._tmp.cleanup()

    def _seed_stale_and_manual(self):
        with get_connection(self.db) as conn:
            # a zombie impl row: method no longer in source
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, "
                "capability_id, link_source, created_at, updated_at) VALUES "
                "('GT-BotLocomotionGameTests-removedMethod', 'zombie', 'impl', 'gametest', "
                "'not_executed', 'motion.sprint', 'manual', 'x', 'x')"
            )
            # a spec row that must never be pruned by the scanner
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, "
                "capability_id, link_source, created_at, updated_at) VALUES "
                "('TC-MOTION-001', 'spec row', 'spec', 'gametest', 'not_executed', "
                "'motion.sprint', 'csv', 'x', 'x')"
            )
            conn.commit()

    def test_scan_kinds_links_and_prunes_only_impls(self):
        self._seed_stale_and_manual()
        result = scan_gametests(self.src, db_path=self.db)
        self.assertEqual(result["pruned"], 1)
        self.assertEqual(result["inserted"], 1)  # sprintAwayFromDanger
        with get_connection(self.db) as conn:
            # zombie gone, spec survives, new impl linked auto
            self.assertIsNone(conn.execute(
                "SELECT id FROM qa_test_cases WHERE id = "
                "'GT-BotLocomotionGameTests-removedMethod'"
            ).fetchone())
            self.assertIsNotNone(conn.execute(
                "SELECT id FROM qa_test_cases WHERE id = 'TC-MOTION-001'"
            ).fetchone())
            r = conn.execute(
                "SELECT kind, capability_id, link_source FROM qa_test_cases "
                "WHERE id = 'GT-BotLocomotionGameTests-sprintAwayFromDanger'"
            ).fetchone()
            self.assertEqual(r["kind"], "impl")
            self.assertEqual(r["capability_id"], "motion.sprint")
            self.assertEqual(r["link_source"], "auto")

    def test_empty_scan_never_prunes(self):
        # zero methods found = wrong root or broken pattern, not a
        # vanished suite; the impl table must survive the misfire
        self._seed_stale_and_manual()
        empty_src = self.dir / "empty"
        empty_src.mkdir()
        result = scan_gametests(empty_src, db_path=self.db)
        self.assertEqual(result["pruned"], 0)
        with get_connection(self.db) as conn:
            self.assertIsNotNone(conn.execute(
                "SELECT id FROM qa_test_cases WHERE id = "
                "'GT-BotLocomotionGameTests-removedMethod'"
            ).fetchone())

    def test_rescan_preserves_manual_link_and_source(self):
        scan_gametests(self.src, db_path=self.db)
        from mcbot.capability.qa_import import link_case
        # re-triaged by hand to a different face
        link_case("GT-BotLocomotionGameTests-sprintAwayFromDanger",
                  "motion.sprint", db_path=self.db)
        with get_connection(self.db) as conn:
            conn.execute(
                "UPDATE qa_test_cases SET capability_id = 'motion.sprint', "
                "link_source = 'manual' WHERE id = "
                "'GT-BotLocomotionGameTests-sprintAwayFromDanger'"
            )
            conn.commit()
        scan_gametests(self.src, db_path=self.db)
        with get_connection(self.db) as conn:
            r = conn.execute(
                "SELECT link_source FROM qa_test_cases WHERE id = "
                "'GT-BotLocomotionGameTests-sprintAwayFromDanger'"
            ).fetchone()
        self.assertEqual(r["link_source"], "manual")


class BoundaryDBackfillTest(unittest.TestCase):
    """Wire-surface receipts fold in as their own family."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.db = self.dir / "test.db"

    def tearDown(self):
        self._tmp.cleanup()

    def test_boundary_d_cases_ingested(self):
        (self.dir / "receipt-20260831-050000.json").write_text(json.dumps({
            "schema": 1, "task": "boundary-d-qa",
            "finished_at": "2026-08-31T05:00:00",
            "cases": [
                {"batch": "A", "id": "A1", "desc": "ls / answers the canonical roots",
                 "expect": "GREEN", "verdict": "PASS", "detail": "contract held", "sec": 0.2},
                {"batch": "B", "id": "B4", "desc": "ticket-gap body-freeze",
                 "expect": "RED", "verdict": "RED-CONFIRMED", "detail": "pin holds", "sec": 8.1},
                {"batch": "C", "id": "C2", "desc": "broken clause",
                 "expect": "GREEN", "verdict": "FAIL", "detail": "regressed", "sec": 1.0},
            ],
        }), encoding="utf-8", newline="\n")
        first = backfill_receipts(self.dir, db_path=self.db, boundary_d_dir=self.dir)
        bd = first["boundary_d"]
        self.assertEqual(bd["inserted"], 1)
        self.assertEqual(bd["case_rows"], 3)

        second = backfill_receipts(self.dir, db_path=self.db, boundary_d_dir=self.dir)
        self.assertEqual(second["boundary_d"]["skipped"], 1)

        with get_connection(self.db) as conn:
            r = conn.execute(
                "SELECT total, failed, green FROM test_receipts WHERE test_type = 'boundary_d'"
            ).fetchone()
            self.assertEqual(r["total"], 3)
            self.assertEqual(r["failed"], 1)  # RED-CONFIRMED is a holding pin, not a failure
            self.assertEqual(r["green"], 0)
            rows = conn.execute(
                "SELECT test_case_id, result FROM test_case_runs "
                "WHERE test_case_id LIKE 'BD-%' ORDER BY test_case_id"
            ).fetchall()
            self.assertEqual([x["test_case_id"] for x in rows], ["BD-A1", "BD-B4", "BD-C2"])
            self.assertEqual([x["result"] for x in rows], ["PASS", "RED-CONFIRMED", "FAIL"])
            # wire rows exist and stay out of the unlinked triage lists
            k = conn.execute(
                "SELECT kind, status FROM qa_test_cases WHERE id = 'BD-B4'"
            ).fetchone()
            self.assertEqual(k["kind"], "wire")
            self.assertEqual(k["status"], "RED-CONFIRMED")
            from mcbot.capability.qa_import import list_unlinked
            self.assertEqual(list_unlinked(db_path=self.db), [])


class HarnessAxisTest(unittest.TestCase):
    """Face -> path axis: seed refresh + inverted report shape."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db = Path(self._tmp.name) / "test.db"

    def tearDown(self):
        self._tmp.cleanup()

    def test_seed_maps_paths_and_refreshes_existing_rows(self):
        seed_database(self.db)
        repo = CapabilityRepository(self.db)
        cap = repo.get("motion.sprint")
        self.assertEqual(cap.harness_paths, ["/tasks/goto"])
        vitals = repo.get("vitals.lava")
        self.assertEqual(vitals.harness_paths, [])

        # catalog refresh: a wiped path on an existing row is restored
        # by the next init without touching status (overlay's home)
        cap.harness_paths = []
        cap.implementation_status = "partial"
        repo.upsert(cap)
        seed_database(self.db)
        cap = repo.get("motion.sprint")
        self.assertEqual(cap.harness_paths, ["/tasks/goto"])
        self.assertEqual(cap.implementation_status, "partial")

    def test_axis_shape_and_wire_evidence(self):
        seed_database(self.db)
        with get_connection(self.db) as conn:
            conn.execute(
                "INSERT INTO test_receipts (run_id, test_type, finished_at, "
                "total, passed, failed, green, created_at) VALUES "
                "('receipt-x','boundary_d','2026-08-31T10:00:00',2,2,0,1,'x')"
            )
            conn.execute(
                "INSERT INTO qa_test_cases (id, title, kind, test_type, status, "
                "created_at, updated_at) VALUES "
                "('BD-A1', 'wire case', 'wire', 'boundary_d', 'PASS', 'x', 'x')"
            )
            conn.execute(
                "INSERT INTO test_case_runs (test_case_id, receipt_id, result, created_at) "
                "VALUES ('BD-A1', 1, 'PASS', 'x')"
            )
            conn.commit()
        axis = harness_axis(self.db)
        self.assertGreater(axis["mapped_faces"], 0)
        self.assertIn("/tasks/goto", axis["by_path"])
        self.assertIn("motion.sprint", axis["by_path"]["/tasks/goto"])
        self.assertIn("vitals", axis["pathless_by_category"])
        self.assertEqual(axis["wire"]["runs"], 1)
        self.assertEqual(axis["wire_verdicts"], {"PASS": 1})


if __name__ == "__main__":
    unittest.main()
