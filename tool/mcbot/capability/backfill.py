"""Backfill committed receipts into the capability database.

Two receipt families fold in, idempotent on ``run_id`` (the filename
stem):

- ``qa-results/engine-runs/gametest-*.json`` (H-R5 currency records)
  -> ``test_receipts`` (test_type='runGameTest') + per-failure
  ``test_case_runs`` rows matched to GT case ids. Receipts written
  before the failed-name extraction fix carry ``failed: []`` while
  ``failed_count > 0``; when the original log still exists under
  ``tool/.runtime/`` it is re-parsed to recover the names.
- ``qa-results/boundary-d/receipt-*.json`` (harness wire-surface
  runs) -> ``test_receipts`` (test_type='boundary_d') + one
  ``test_case_runs`` row per case under the synthetic id
  ``BD-<case>`` (wire-contract evidence; deliberately not linked to
  behavior faces). Verdict vocabulary: PASS / RED-CONFIRMED
  (expected-red pin holding) / FAIL; only FAIL makes a run red.
"""
from __future__ import annotations

import datetime as _dt
import json
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.receipt import _insert_case_failures, _insert_receipt
from mcbot.engine import ENGINE_RUN_DIR, parse_run_log
from mcbot.paths import PROJECT_ROOT

BOUNDARY_D_DIR = PROJECT_ROOT / "qa-results" / "boundary-d"


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def _recover_failed_names(data: dict) -> tuple[list[str], list[str], bool]:
    """Re-parse the surviving log when the receipt lost the failed
    names to the pre-fix line-start regex. Returns (required,
    optional, recovered?)."""
    failed = list(data.get("failed") or [])
    optional = list(data.get("failed_optional") or [])
    failed_count = int(data.get("failed_count") or 0)
    if failed_count <= len(failed):
        return failed, optional, False
    log_path = data.get("log")
    if not log_path or not Path(log_path).exists():
        return failed, optional, False
    verdict = parse_run_log(Path(log_path))
    if not verdict or len(verdict["failed"]) < failed_count:
        return failed, optional, False
    return verdict["failed"], verdict["failed_optional"], True


def _backfill_engine_runs(root: Path, db_path: Optional[Path]) -> dict:
    try:
        files = sorted(root.glob("gametest-*.json"))
    except OSError:
        files = []

    inserted = skipped = case_rows = recovered = unreadable = 0
    for f in files:
        run_id = f.stem
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            unreadable += 1
            continue
        with get_connection(db_path) as conn:
            if conn.execute(
                "SELECT 1 FROM test_receipts WHERE run_id = ?", (run_id,)
            ).fetchone():
                skipped += 1
                continue
            failed, optional, did_recover = _recover_failed_names(data)
            if did_recover:
                recovered += 1
            receipt_id = _insert_receipt(
                conn,
                run_id=run_id,
                task_name=data.get("task") or "runGameTest",
                finished_at=data.get("finished_at") or _now_iso(),
                git_rev=data.get("git_rev"),
                total=int(data.get("scenarios_total") or 0),
                failed_count=int(data.get("failed_count") or 0),
                optional_count=int(
                    data.get("optional_failed_count")
                    if data.get("optional_failed_count") is not None
                    else len(optional)
                ),
                green=bool(data.get("green")),
                log_path=data.get("log") or "",
                details={"receipt_path": str(f)},
                created_at=_now_iso(),
            )
            case_rows += _insert_case_failures(
                conn, receipt_id, required=failed, optional=optional, created_at=_now_iso()
            )
            conn.commit()
            inserted += 1

    return {
        "files": len(files),
        "inserted": inserted,
        "skipped": skipped,
        "case_rows": case_rows,
        "recovered": recovered,
        "unreadable": unreadable,
    }


def _backfill_boundary_d(root: Path, db_path: Optional[Path]) -> dict:
    try:
        files = sorted(root.glob("receipt-*.json"))
    except OSError:
        files = []

    inserted = skipped = case_rows = unreadable = 0
    for f in files:
        run_id = f.stem
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            unreadable += 1
            continue
        cases = data.get("cases") or []
        if data.get("task") != "boundary-d-qa" or not cases:
            unreadable += 1
            continue
        failed = sum(1 for c in cases if c.get("verdict") not in ("PASS", "RED-CONFIRMED"))
        with get_connection(db_path) as conn:
            if conn.execute(
                "SELECT 1 FROM test_receipts WHERE run_id = ?", (run_id,)
            ).fetchone():
                skipped += 1
                continue
            receipt_id = _insert_receipt(
                conn,
                run_id=run_id,
                task_name="boundary_d",
                finished_at=data.get("finished_at") or _now_iso(),
                git_rev=None,
                total=len(cases),
                failed_count=failed,
                optional_count=0,
                green=failed == 0,
                log_path="",
                details={
                    "receipt_path": str(f),
                    "verdicts": {
                        v: sum(1 for c in cases if c.get("verdict") == v)
                        for v in sorted({c.get("verdict") for c in cases})
                    },
                },
                created_at=_now_iso(),
            )
            for c in cases:
                # wire rows: the receipt is their home; the collector
                # creates them (scanner pattern), status stores the
                # verdict verbatim (PASS / RED-CONFIRMED / FAIL)
                conn.execute(
                    """
                    INSERT OR IGNORE INTO qa_test_cases (
                        id, title, kind, test_type, status,
                        created_at, updated_at
                    ) VALUES (?, ?, 'wire', 'boundary_d', ?, ?, ?)
                    """,
                    (
                        f"BD-{c.get('id')}",
                        c.get("desc") or "",
                        c.get("verdict") or "?",
                        _now_iso(), _now_iso(),
                    ),
                )
                conn.execute(
                    """
                    INSERT OR IGNORE INTO test_case_runs (
                        test_case_id, receipt_id, result, duration_ms,
                        details, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        f"BD-{c.get('id')}",
                        receipt_id,
                        c.get("verdict") or "?",
                        int((c.get("sec") or 0) * 1000),
                        json.dumps({"desc": c.get("desc", ""), "detail": c.get("detail", "")}),
                        _now_iso(),
                    ),
                )
                case_rows += 1
            conn.commit()
            inserted += 1

    return {
        "files": len(files),
        "inserted": inserted,
        "skipped": skipped,
        "case_rows": case_rows,
        "unreadable": unreadable,
    }


def backfill_receipts(
    engine_run_dir: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
    boundary_d_dir: Optional[Path] = None,
) -> dict:
    """Mirror every committed receipt family into the capability DB.

    Returns a per-family summary dict (engine_runs / boundary_d)."""
    init_db(db_path)
    return {
        "engine_runs": _backfill_engine_runs(engine_run_dir or ENGINE_RUN_DIR, db_path),
        "boundary_d": _backfill_boundary_d(boundary_d_dir or BOUNDARY_D_DIR, db_path),
    }
