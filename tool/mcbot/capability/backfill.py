"""Backfill engine-run receipts into the capability database.

Reads every ``gametest-*.json`` under ``qa-results/engine-runs/``
(the H-R5 currency records) and mirrors each into ``test_receipts``
plus per-failure ``test_case_runs`` rows, so flake history and
per-domain evidence exist for runs that predate the DB wiring.

Idempotent on ``run_id`` (the JSON filename stem): re-running skips
receipts already mirrored. Receipts written before the failed-name
extraction fix carry ``failed: []`` while ``failed_count > 0``; when
the original log still exists under ``tool/.runtime/`` it is
re-parsed to recover the names.
"""
from __future__ import annotations

import datetime as _dt
import json
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.receipt import _insert_case_failures, _insert_receipt
from mcbot.engine import ENGINE_RUN_DIR, parse_run_log


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


def backfill_receipts(
    engine_run_dir: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Mirror every engine-run JSON receipt into the capability DB.

    Returns a summary dict: files seen, receipts inserted, receipts
    skipped (already mirrored), case-run rows written, receipts whose
    failed names were recovered from a surviving log, unreadable
    files."""
    init_db(db_path)
    root = engine_run_dir or ENGINE_RUN_DIR
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
