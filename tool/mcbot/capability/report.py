"""Macro read-models over the capability matrix: per-update diffs and
per-domain reports.

Both fold committed-artifact mirrors already in the DB (receipts,
case runs, links) plus the transitions audit table. They never touch
source files, so a rebuilt DB produces the same report.

Honest limits baked into the shapes:
- gametest logs list failures per scenario but never passes, so
  green evidence is run-granular, not per-capability;
- transitions only exist from 2026-08-31 onward (the table's birth);
  older history lives in git (receipts) and is reported as such.
"""
from __future__ import annotations

import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.queries import overview

TRANSITIONS_BORN = "2026-08-31"


def _since_ts(since: str) -> str:
    """Normalize a YYYY-MM-DD (or full ISO timestamp) to a comparable
    ISO string; date-only becomes midnight."""
    if len(since) == 10:
        return since + "T00:00:00"
    return since


def diff_since(since: str, *, db_path: Optional[Path] = None) -> dict:
    """Everything that changed since a date, one dict.

    - transitions: status flips in the window (with capability name)
    - runs: receipt counts, green/red split, scenario growth, red-run
      details with failed scenarios (matched to case ids)
    - faces_added: capabilities whose created_at falls in the window
      (weak for the seed batch - all seeded rows share one timestamp)
    - current: today's overview for context
    """
    init_db(db_path)
    ts = _since_ts(since)
    with get_connection(db_path) as conn:
        transitions = [
            dict(r) for r in conn.execute(
                """
                SELECT t.capability_id, c.name, t.old_status, t.new_status,
                       t.source, t.changed_at
                FROM capability_status_transitions t
                LEFT JOIN capabilities c ON c.id = t.capability_id
                WHERE t.changed_at >= ?
                ORDER BY t.changed_at
                """,
                (ts,),
            ).fetchall()
        ]
        runs_rows = conn.execute(
            """
            SELECT run_id, finished_at, git_rev, total, failed, green
            FROM test_receipts WHERE finished_at >= ?
            ORDER BY finished_at
            """,
            (ts,),
        ).fetchall()
        red_ids = [r["id"] for r in [
            dict(x) for x in conn.execute(
                "SELECT id FROM test_receipts WHERE finished_at >= ? AND green = 0",
                (ts,),
            ).fetchall()
        ]]
        red_details = []
        for rid in red_ids:
            run = dict(conn.execute(
                "SELECT run_id, finished_at, git_rev, total, failed FROM test_receipts WHERE id = ?",
                (rid,),
            ).fetchone())
            run["scenarios"] = [
                dict(x) for x in conn.execute(
                    """
                    SELECT test_case_id, details FROM test_case_runs
                    WHERE receipt_id = ? ORDER BY id
                    """,
                    (rid,),
                ).fetchall()
            ]
            red_details.append(run)
        faces_added = [
            dict(r) for r in conn.execute(
                "SELECT id, name, category, created_at FROM capabilities "
                "WHERE created_at >= ? ORDER BY created_at",
                (ts,),
            ).fetchall()
        ]
    runs = [dict(r) for r in runs_rows]
    return {
        "since": since,
        "transitions": transitions,
        "transitions_note": (
            f"transitions recorded from {TRANSITIONS_BORN} onward only "
            "(table is new); older history lives in git receipts"
        ),
        "runs": {
            "count": len(runs),
            "green": sum(1 for r in runs if r["green"]),
            "red": sum(1 for r in runs if not r["green"]),
            "first_total": runs[0]["total"] if runs else None,
            "last_total": runs[-1]["total"] if runs else None,
        },
        "red_details": red_details,
        "faces_added": faces_added,
        "current": overview(db_path),
    }


def domain_report(category: str, *, db_path: Optional[Path] = None) -> Optional[dict]:
    """One capability domain: per-face evidence, coverage, deficiencies.

    Per face: status, verified_at, deviation flag, linked QA cases,
    failure history (from test_case_runs via linked cases). Domain
    level: green-run streak since the last run that failed a scenario
    in this domain, plus faces flagged NO-COVERAGE (zero linked cases)
    and UNVERIFIED-EVIDENCE (no failure history at all is normal for
    green domains - absence of evidence is not marked; only missing
    QA coverage is)."""
    init_db(db_path)
    with get_connection(db_path) as conn:
        caps = [
            dict(r) for r in conn.execute(
                "SELECT id, name, implementation_status, verified_at, "
                "deviation, updated_at FROM capabilities WHERE category = ? ORDER BY id",
                (category,),
            ).fetchall()
        ]
        if not caps:
            return None
        faces = []
        for cap in caps:
            cases = [
                dict(r) for r in conn.execute(
                    """
                    SELECT id, title, test_type FROM qa_test_cases
                    WHERE capability_id = ? ORDER BY id
                    """,
                    (cap["id"],),
                ).fetchall()
            ]
            failures = [
                dict(r) for r in conn.execute(
                    """
                    SELECT r.run_id, r.finished_at, r.git_rev, cr.test_case_id, cr.details
                    FROM test_case_runs cr
                    JOIN test_receipts r ON r.id = cr.receipt_id
                    WHERE cr.test_case_id IN (
                        SELECT id FROM qa_test_cases WHERE capability_id = ?
                    )
                    ORDER BY r.finished_at DESC
                    """,
                    (cap["id"],),
                ).fetchall()
            ]
            faces.append({
                **cap,
                "has_deviation": bool(cap["deviation"]),
                "cases": [c["id"] for c in cases],
                "case_count": len(cases),
                "no_coverage": len(cases) == 0,
                "failures": failures,
            })
        # last receipt that failed a scenario belonging to this domain
        last_red = conn.execute(
            """
            SELECT r.run_id, r.finished_at, r.git_rev
            FROM test_receipts r
            WHERE r.green = 0 AND EXISTS (
                SELECT 1 FROM test_case_runs cr
                JOIN qa_test_cases c ON c.id = cr.test_case_id
                WHERE cr.receipt_id = r.id AND c.capability_id IN (
                    SELECT id FROM capabilities WHERE category = ?
                )
            )
            ORDER BY r.finished_at DESC LIMIT 1
            """,
            (category,),
        ).fetchone()
        green_streak = 0
        if last_red:
            row = conn.execute(
                "SELECT COUNT(*) as c FROM test_receipts WHERE green = 1 AND finished_at > ?",
                (last_red["finished_at"],),
            ).fetchone()
            green_streak = row["c"]
    return {
        "category": category,
        "faces": faces,
        "faces_no_coverage": [f["id"] for f in faces if f["no_coverage"]],
        "faces_with_deviation": [f["id"] for f in faces if f["has_deviation"]],
        "last_red_in_domain": dict(last_red) if last_red else None,
        "green_streak_since": green_streak,
    }
