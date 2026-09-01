"""Durable overlay for capability-matrix state.

The SQLite DB under ``tool/.runtime/`` is a disposable cache:
gitignored, rebuildable. Catalog comes from seed, spec cases from the
QA CSV, impl cases from the gametest scanner, receipts from
backfill. What is NOT reconstructible is the manual residue: status
rulings, deviation edits, and non-CSV case links. This module is the
single home for exactly that residue — ``qa-results/capability-state.json``
(committed, diffable) — maintained WRITE-THROUGH: ``capability set``
and ``capability link`` regenerate the file in the same command, so
no manual fact ever exists only in the DB.

Scope (schema 2): all capability statuses + every case link whose
link_source is not 'csv' (CSV-owned spec links live in the CSV - one
home per fact class; manual overrides on spec rows are lost at the
next import by design).

Rebuild recipe (README documents it too):
  capability init -> qa-import <csv>... -> scan-gametest
  -> backfill -> restore

Restore semantics: a differing status goes through update_status;
identical statuses are no-ops. Links replay with their recorded
link_source (missing -> 'manual', schema-1 tolerance). A stored empty
deviation / null verified_at never erases current values (the overlay
only carries positive facts).
"""
from __future__ import annotations

import datetime as _dt
import json
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.repository import CapabilityRepository
from mcbot.paths import PROJECT_ROOT

DEFAULT_STATE_PATH = PROJECT_ROOT / "qa-results" / "capability-state.json"


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def export_state(
    out_path: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Regenerate the state overlay (statuses + non-CSV links).

    Called write-through by the mutating verbs; returns counts and
    the written path."""
    init_db(db_path)
    path = out_path or DEFAULT_STATE_PATH
    with get_connection(db_path) as conn:
        statuses = [
            {
                "id": r["id"],
                "status": r["implementation_status"],
                "verified_at": r["verified_at"],
                "deviation": r["deviation"] or "",
            }
            for r in conn.execute(
                "SELECT id, implementation_status, verified_at, deviation "
                "FROM capabilities ORDER BY id"
            ).fetchall()
        ]
        links = [
            {
                "case_id": r["id"],
                "capability_id": r["capability_id"],
                "link_source": r["link_source"] or "manual",
            }
            for r in conn.execute(
                "SELECT id, capability_id, link_source FROM qa_test_cases "
                "WHERE capability_id IS NOT NULL "
                "AND (link_source IS NULL OR link_source != 'csv') "
                "ORDER BY id"
            ).fetchall()
        ]
    payload = {
        "schema": 2,
        "exported_at": _now_iso(),
        "statuses": statuses,
        "links": links,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8", newline="\n")
    return {"path": path, "statuses": len(statuses), "links": len(links)}


def write_state_through(db_path: Optional[Path] = None) -> dict:
    """Regenerate the overlay at its default path after a mutation."""
    return export_state(DEFAULT_STATE_PATH, db_path=db_path)


def restore_state(
    in_path: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Apply the overlay onto the DB (after init/import/scan/backfill).

    Returns applied/unchanged counters plus faces or cases the overlay
    references that no longer exist (kept as warnings, not errors)."""
    init_db(db_path)
    path = in_path or DEFAULT_STATE_PATH
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise ValueError(f"cannot read overlay {path}: {e}") from e

    repo = CapabilityRepository(db_path)
    statuses_applied = statuses_unchanged = missing_faces = 0
    for row in payload.get("statuses", []):
        cap = repo.get(row["id"])
        if cap is None:
            missing_faces += 1
            continue
        if cap.implementation_status == row["status"]:
            statuses_unchanged += 1
            continue
        repo.update_status(
            row["id"], row["status"],
            deviation=row.get("deviation") or None,
            verified_at=row.get("verified_at") or None,
        )
        statuses_applied += 1

    links_applied = links_unchanged = missing_cases = 0
    with get_connection(db_path) as conn:
        for row in payload.get("links", []):
            stored_cap = row["capability_id"]
            stored_src = row.get("link_source") or "manual"
            current = conn.execute(
                "SELECT capability_id, link_source FROM qa_test_cases WHERE id = ?",
                (row["case_id"],),
            ).fetchone()
            if current is None:
                missing_cases += 1
                continue
            if current["capability_id"] == stored_cap and (
                current["link_source"] or "manual"
            ) == stored_src:
                links_unchanged += 1
                continue
            conn.execute(
                "UPDATE qa_test_cases SET capability_id = ?, link_source = ? WHERE id = ?",
                (stored_cap, stored_src, row["case_id"]),
            )
            links_applied += 1

    return {
        "statuses_applied": statuses_applied,
        "statuses_unchanged": statuses_unchanged,
        "missing_faces": missing_faces,
        "links_applied": links_applied,
        "links_unchanged": links_unchanged,
        "missing_cases": missing_cases,
    }
