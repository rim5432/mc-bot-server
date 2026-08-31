"""Repository layer: CRUD with short transactions.

Every write method opens its own connection, does its work, and
commits — no long-lived connections, no leaked transactions. The
``updated_at`` timestamp is bumped on every write so the audit trail
is honest.

``source_paths`` is stored as a JSON array string; the repository
serializes/deserializes transparently so callers work with plain
``list[str]``.
"""
from __future__ import annotations

import json
import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection
from mcbot.capability.models import Capability

VALID_STATUSES = {"shipped", "partial", "gap", "deferred"}


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def _row_to_capability(row) -> Capability:
    return Capability(
        id=row["id"],
        name=row["name"],
        category=row["category"],
        axis=row["axis"] if "axis" in row.keys() else "",
        description=row["description"] or "",
        implementation_status=row["implementation_status"],
        vanilla_ref=row["vanilla_ref"] or "",
        deviation=row["deviation"] or "",
        source_paths=json.loads(row["source_paths"] or "[]"),
        harness_paths=json.loads(row["harness_paths"] or "[]"),
        verified_at=row["verified_at"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


# ---------------------------------------------------------------------------
# CapabilityRepository
# ---------------------------------------------------------------------------
class CapabilityRepository:
    """CRUD for the capabilities table."""

    def __init__(self, db_path: Optional[Path] = None):
        self._db_path = db_path

    def get(self, capability_id: str) -> Optional[Capability]:
        with get_connection(self._db_path) as conn:
            row = conn.execute(
                "SELECT * FROM capabilities WHERE id = ?", (capability_id,)
            ).fetchone()
        return _row_to_capability(row) if row else None

    def list(
        self,
        category: Optional[str] = None,
        status: Optional[str] = None,
    ) -> list[Capability]:
        query = "SELECT * FROM capabilities WHERE 1=1"
        params: list = []
        if category:
            query += " AND category = ?"
            params.append(category)
        if status:
            query += " AND implementation_status = ?"
            params.append(status)
        query += " ORDER BY category, id"
        with get_connection(self._db_path) as conn:
            rows = conn.execute(query, params).fetchall()
        return [_row_to_capability(r) for r in rows]

    def upsert(self, cap: Capability) -> None:
        """Insert or update. ``created_at`` set on first insert only."""
        if cap.implementation_status not in VALID_STATUSES:
            raise ValueError(
                f"invalid implementation_status '{cap.implementation_status}'; "
                f"must be one of {sorted(VALID_STATUSES)}"
            )
        now = _now_iso()
        source_paths_json = json.dumps(cap.source_paths)
        harness_paths_json = json.dumps(cap.harness_paths)
        with get_connection(self._db_path) as conn:
            existing = conn.execute(
                "SELECT created_at FROM capabilities WHERE id = ?", (cap.id,)
            ).fetchone()
            if existing:
                conn.execute(
                    """
                    UPDATE capabilities SET
                        name = ?, category = ?, axis = ?, description = ?,
                        implementation_status = ?, vanilla_ref = ?, deviation = ?,
                        source_paths = ?, harness_paths = ?, verified_at = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        cap.name, cap.category, cap.axis, cap.description,
                        cap.implementation_status, cap.vanilla_ref, cap.deviation,
                        source_paths_json, harness_paths_json, cap.verified_at, now, cap.id,
                    ),
                )
            else:
                conn.execute(
                    """
                    INSERT INTO capabilities (
                        id, name, category, axis, description, implementation_status,
                        vanilla_ref, deviation, source_paths, harness_paths, verified_at,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        cap.id, cap.name, cap.category, cap.axis, cap.description,
                        cap.implementation_status, cap.vanilla_ref, cap.deviation,
                        source_paths_json, harness_paths_json, cap.verified_at, now, now,
                    ),
                )
            conn.commit()

    def update_status(
        self,
        capability_id: str,
        status: str,
        *,
        deviation: Optional[str] = None,
        verified_at: Optional[str] = None,
        source: str = "manual",
        note: str = "",
    ) -> bool:
        """Update just the status (and optionally deviation / verified_at).

        A status change appends one capability_status_transitions row
        (old -> new, source, note) so ``capability diff`` can replay
        history. Unchanged statuses record nothing. ``source`` marks
        who flipped it: manual | restore | derive.

        Returns True if a row was updated, False if the id doesn't exist.
        """
        if status not in VALID_STATUSES:
            raise ValueError(
                f"invalid status '{status}'; must be one of {sorted(VALID_STATUSES)}"
            )
        now = _now_iso()
        sets = ["implementation_status = ?", "updated_at = ?"]
        params: list = [status, now]
        if deviation is not None:
            sets.append("deviation = ?")
            params.append(deviation)
        if verified_at is not None:
            sets.append("verified_at = ?")
            params.append(verified_at)
        params.append(capability_id)
        with get_connection(self._db_path) as conn:
            old = conn.execute(
                "SELECT implementation_status FROM capabilities WHERE id = ?", (capability_id,)
            ).fetchone()
            if not old:
                return False
            if old["implementation_status"] != status:
                conn.execute(
                    """
                    INSERT INTO capability_status_transitions (
                        capability_id, old_status, new_status, source, note, changed_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (capability_id, old["implementation_status"], status, source, note, now),
                )
            conn.execute(
                f"UPDATE capabilities SET {', '.join(sets)} WHERE id = ?", params
            )
            conn.commit()
            return True

    def delete(self, capability_id: str) -> bool:
        with get_connection(self._db_path) as conn:
            cur = conn.execute("DELETE FROM capabilities WHERE id = ?", (capability_id,))
            conn.commit()
        return cur.rowcount > 0

    def all_categories(self) -> list[str]:
        with get_connection(self._db_path) as conn:
            rows = conn.execute(
                "SELECT DISTINCT category FROM capabilities ORDER BY category"
            ).fetchall()
        return [r["category"] for r in rows]

    def count_by_status(self, category: Optional[str] = None) -> dict[str, int]:
        """Return {status: count} optionally filtered by category."""
        query = "SELECT implementation_status, COUNT(*) as c FROM capabilities"
        params: list = []
        if category:
            query += " WHERE category = ?"
            params.append(category)
        query += " GROUP BY implementation_status"
        with get_connection(self._db_path) as conn:
            rows = conn.execute(query, params).fetchall()
        return {r["implementation_status"]: r["c"] for r in rows}
