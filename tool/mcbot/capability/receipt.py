"""Test receipt persistence into the capability database.

After every ``runGameTest`` run, the engine log is parsed by
``mcbot.engine.write_engine_receipt`` into a JSON file. This module
takes that same log and writes a structured row into ``test_receipts``
plus one row per failed scenario into ``test_case_runs``, so the
capability matrix can show per-face test history without re-parsing
logs.

The JSON file remains the canonical receipt (H-R5 currency); the DB
rows are a queryable mirror.
"""
from __future__ import annotations

import datetime as _dt
import re
import sys
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db

# Reuse the same parsing patterns as engine.write_engine_receipt so
# the two sources never disagree.
_RE_TOTAL = re.compile(r"(\d+) GAME TESTS COMPLETE")
_RE_FAILED_COUNT = re.compile(r"(\d+) required tests failed")
_RE_FAILED_NAME = re.compile(r"^\s*- ([a-z0-9_]+)\s*$", re.M)


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

    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None

    m_total = _RE_TOTAL.search(text)
    if not m_total:
        return None
    total = int(m_total.group(1))

    m_fail = _RE_FAILED_COUNT.search(text)
    failed_count = int(m_fail.group(1)) if m_fail else 0

    failed_names = _RE_FAILED_NAME.findall(text)
    if len(failed_names) > failed_count:
        failed_names = failed_names[:failed_count]

    green = failed_count == 0
    now = _now_iso()

    # Best-effort git rev (mirrors engine._git_head_short)
    git_rev = _git_head_short()

    with get_connection(db_path) as conn:
        run_id = f"gametest-{_dt.datetime.now().strftime('%Y%m%d-%H%M%S')}"
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
                now,
                now,
                git_rev,
                total,
                total - failed_count,  # passed (approximate; Forge doesn't list passes)
                failed_count,
                1 if green else 0,
                str(log_path),
                '{"receipt_path": ' + (f'"{receipt_path}"' if receipt_path else 'null') + '}',
                now,
            ),
        )
        receipt_id = cur.lastrowid

        # One row per failed scenario. Passed scenarios are not
        # individually logged by Forge gametest, so we record only
        # failures (the receipt row carries the total).
        for test_name in failed_names:
            # Try to match to a known qa_test_case by id pattern.
            case_id = _match_case_id(conn, test_name)
            conn.execute(
                """
                INSERT INTO test_case_runs (
                    test_case_id, receipt_id, result, duration_ms,
                    details, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    case_id,
                    receipt_id,
                    "failed",
                    None,
                    f'{{"log_name": "{test_name}"}}',
                    now,
                ),
            )

        conn.commit()

    print(
        f"[mcbot] test receipt #{receipt_id} recorded "
        f"(total={total}, failed={failed_count}, {'GREEN' if green else 'RED'})"
    )
    return receipt_id


def _match_case_id(conn, test_name: str) -> Optional[str]:
    """Try to map a Forge-reported test name to a qa_test_cases.id.

    Forge reports names like ``mcbotserver.BotCombatGameTests.refusesRangedItCannotAnswer``
    or bare ``methodName``. We try:
      1. exact match against qa_test_cases.id
      2. GT-<Class>-<method> pattern reconstruction
      3. LIKE match on method name suffix
    """
    # Strip package prefix if present
    short = test_name.split(".")[-1] if "." in test_name else test_name

    # 1. exact match
    row = conn.execute(
        "SELECT id FROM qa_test_cases WHERE id = ?", (test_name,)
    ).fetchone()
    if row:
        return row["id"]

    # 2. Try to reconstruct GT-<Class>-<method>
    # Forge name may be "ClassName.methodName" or "pkg.ClassName.methodName"
    parts = test_name.split(".")
    if len(parts) >= 2:
        class_name = parts[-2]
        method_name = parts[-1]
        candidate = f"GT-{class_name}-{method_name}"
        row = conn.execute(
            "SELECT id FROM qa_test_cases WHERE id = ?", (candidate,)
        ).fetchone()
        if row:
            return row["id"]

    # 3. LIKE match on method name suffix (GT-*-methodName)
    row = conn.execute(
        "SELECT id FROM qa_test_cases WHERE id LIKE ? ORDER BY id LIMIT 1",
        (f"GT-%-{short}",),
    ).fetchone()
    if row:
        return row["id"]

    return None


def _git_head_short() -> Optional[str]:
    """Best-effort current commit id; None outside a repo."""
    import subprocess
    from mcbot.paths import PROJECT_ROOT
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        return out.stdout.strip() if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


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
