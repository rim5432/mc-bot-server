"""Test receipt persistence into the capability database.

After every ``runGameTest`` run, the engine log is parsed by
``mcbot.engine.parse_run_log`` (the single parse shared with the JSON
receipt writer). This module persists that verdict as a structured row
into ``test_receipts`` plus one row per failed scenario (required and
optional) into ``test_case_runs``, so the capability matrix can show
per-face test history without re-parsing logs.

The JSON file remains the canonical receipt (H-R5 currency); the DB
rows are a queryable mirror.
"""
from __future__ import annotations

import datetime as _dt
import json
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.engine import git_head_short, parse_run_log


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def record_test_run(
    log_path: Path,
    *,
    receipt_path: Optional[Path] = None,
    task_name: str = "runGameTest",
    db_path: Optional[Path] = None,
) -> Optional[int]:
    """Parse a finished runGameTest log and write receipt + per-test
    rows into the capability database.

    Returns the receipt_id, or None when the log holds no verdict
    (crash, interrupt, wrong task — same gate as write_engine_receipt).
    """
    init_db(db_path)

    verdict = parse_run_log(log_path)
    if verdict is None:
        return None

    now = _now_iso()
    with get_connection(db_path) as conn:
        receipt_id = _insert_receipt(
            conn,
            run_id=f"gametest-{_dt.datetime.now().strftime('%Y%m%d-%H%M%S')}",
            task_name=task_name,
            finished_at=now,
            git_rev=git_head_short(),
            total=verdict["total"],
            failed_count=verdict["failed_count"],
            optional_count=verdict["optional_failed_count"],
            green=verdict["green"],
            log_path=str(log_path),
            details={"receipt_path": str(receipt_path) if receipt_path else None},
            created_at=now,
        )
        _insert_case_failures(
            conn, receipt_id,
            required=verdict["failed"],
            optional=verdict["failed_optional"],
            created_at=now,
        )
        conn.commit()

    print(
        f"[mcbot] test receipt #{receipt_id} recorded "
        f"(total={verdict['total']}, failed={verdict['failed_count']}, "
        f"{'GREEN' if verdict['green'] else 'RED'})"
    )
    return receipt_id


def _insert_receipt(
    conn,
    *,
    run_id: str,
    task_name: str,
    finished_at: str,
    git_rev: Optional[str],
    total: int,
    failed_count: int,
    optional_count: int,
    green: bool,
    log_path: str,
    details: dict,
    created_at: str,
) -> int:
    """Insert one test_receipts row; returns the new receipt id."""
    cur = conn.execute(
        """
        INSERT INTO test_receipts (
            run_id, test_type, started_at, finished_at, git_rev,
            total, passed, failed, green, log_path, details, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            run_id,
            task_name,
            None,
            finished_at,
            git_rev,
            total,
            total - failed_count,  # approximate; Forge does not list passes
            failed_count,
            1 if green else 0,
            log_path,
            json.dumps(details),
            created_at,
        ),
    )
    return cur.lastrowid


def _insert_case_failures(
    conn,
    receipt_id: int,
    *,
    required: list[str],
    optional: list[str],
    created_at: str,
) -> int:
    """Insert one test_case_runs row per failed scenario, matched to a
    qa_test_case when possible. Forge logs only failures, so passes are
    represented by the receipt row's totals. Returns rows written."""
    written = 0
    for log_name, kind in [(n, "failed") for n in required] + [(n, "failed_optional") for n in optional]:
        case_id = _match_case_id(conn, log_name)
        conn.execute(
            """
            INSERT OR IGNORE INTO test_case_runs (
                test_case_id, receipt_id, result, duration_ms,
                details, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                case_id,
                receipt_id,
                "failed",
                None,
                json.dumps({"log_name": log_name, "kind": kind}),
                created_at,
            ),
        )
        written += 1
    return written


def _match_case_id(conn, test_name: str) -> Optional[str]:
    """Try to map a Forge-reported test name to a qa_test_cases.id.

    Forge reports bare lowercase structure names
    (``climbsladdertoplatform``) or dotted
    ``mcbotserver.BotCombatGameTests.refusesRangedItCannotAnswer``.
    We try:
      1. exact match against qa_test_cases.id
      2. GT-<Class>-<method> pattern reconstruction (dotted names)
      3. LIKE match on the name suffix — SQLite LIKE is ASCII
         case-insensitive, so the lowercase structure name matches a
         camelCase GT id (``GT-%-climbsladdertoplatform`` hits
         ``GT-BotLocomotionGameTests-climbsLadderToPlatform``)
    """
    short = test_name.split(".")[-1] if "." in test_name else test_name

    row = conn.execute(
        "SELECT id FROM qa_test_cases WHERE id = ?", (test_name,)
    ).fetchone()
    if row:
        return row["id"]

    parts = test_name.split(".")
    if len(parts) >= 2:
        candidate = f"GT-{parts[-2]}-{parts[-1]}"
        row = conn.execute(
            "SELECT id FROM qa_test_cases WHERE id = ?", (candidate,)
        ).fetchone()
        if row:
            return row["id"]

    row = conn.execute(
        "SELECT id FROM qa_test_cases WHERE id LIKE ? ORDER BY id LIMIT 1",
        (f"GT-%-{short}",),
    ).fetchone()
    return row["id"] if row else None


def latest_receipt_summary(db_path: Optional[Path] = None) -> Optional[dict]:
    """Return the most recent test receipt as a dict, or None."""
    init_db(db_path)
    with get_connection(db_path) as conn:
        row = conn.execute(
            """
            SELECT id, run_id, test_type, finished_at, total,
                   failed, green, git_rev
            FROM test_receipts
            ORDER BY id DESC
            LIMIT 1
            """
        ).fetchone()
    if not row:
        return None
    d = dict(row)
    # Normalize field names for callers
    d["scenarios_total"] = d.pop("total")
    d["failed_count"] = d.pop("failed")
    d["task_name"] = d.pop("test_type")
    return d
