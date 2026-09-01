"""Repository layer for annotation-declared features.

Features are the atomic unit of a capability face. They are discovered
from Java source by the scanner and upserted here — never hand-edited.
The repository owns the CRUD; status derivation lives in report.py so
the storage layer stays dumb.
"""
from __future__ import annotations

import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection
from mcbot.capability.models import Feature


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def _row_to_feature(row) -> Feature:
    return Feature(
        id=row["id"],
        face=row["face_id"],
        description=row["description"] or "",
        vanilla_ref=row["vanilla_ref"] or "",
        deviation=row["deviation"] or "",
        source_file=row["source_file"] or "",
        source_method=row["source_method"] or "",
        source_line=row["source_line"] or 0,
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


class FeatureRepository:
    """CRUD for the features table."""

    def __init__(self, db_path: Optional[Path] = None):
        self._db_path = db_path

    def get(self, feature_id: str) -> Optional[Feature]:
        with get_connection(self._db_path) as conn:
            row = conn.execute(
                "SELECT * FROM features WHERE id = ?", (feature_id,)
            ).fetchone()
        return _row_to_feature(row) if row else None

    def list(
        self,
        face: Optional[str] = None,
    ) -> list[Feature]:
        query = "SELECT * FROM features WHERE 1=1"
        params: list = []
        if face:
            query += " AND face_id = ?"
            params.append(face)
        query += " ORDER BY face_id, id"
        with get_connection(self._db_path) as conn:
            rows = conn.execute(query, params).fetchall()
        return [_row_to_feature(r) for r in rows]

    def upsert(self, feature: Feature) -> None:
        """Insert or update. ``created_at`` set on first insert only."""
        now = _now_iso()
        with get_connection(self._db_path) as conn:
            existing = conn.execute(
                "SELECT created_at FROM features WHERE id = ?", (feature.id,)
            ).fetchone()
            if existing:
                conn.execute(
                    """
                    UPDATE features SET
                        face_id = ?, description = ?, vanilla_ref = ?,
                        deviation = ?, source_file = ?, source_method = ?,
                        source_line = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        feature.face, feature.description, feature.vanilla_ref,
                        feature.deviation, feature.source_file,
                        feature.source_method, feature.source_line, now,
                        feature.id,
                    ),
                )
            else:
                conn.execute(
                    """
                    INSERT INTO features (
                        id, face_id, description, vanilla_ref, deviation,
                        source_file, source_method, source_line,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        feature.id, feature.face, feature.description,
                        feature.vanilla_ref, feature.deviation,
                        feature.source_file, feature.source_method,
                        feature.source_line, now, now,
                    ),
                )
            conn.commit()

    def prune(self, keep_ids: set[str]) -> int:
        """Delete features whose id is not in keep_ids.

        An empty keep set means keep nothing — every row goes. The
        scanner gates this call on having actually seen Java files (a
        zero-file scan is a misconfigured root, not an empty
        inventory). Returns the number of rows deleted.
        """
        with get_connection(self._db_path) as conn:
            if keep_ids:
                placeholders = ", ".join("?" for _ in keep_ids)
                cur = conn.execute(
                    f"DELETE FROM features WHERE id NOT IN ({placeholders})",
                    tuple(keep_ids),
                )
            else:
                cur = conn.execute("DELETE FROM features")
            conn.commit()
        return cur.rowcount

    def count(self) -> int:
        with get_connection(self._db_path) as conn:
            row = conn.execute("SELECT COUNT(*) as c FROM features").fetchone()
        return row["c"] if row else 0
