"""SQLite connection management and schema lifecycle.

WAL mode + busy_timeout gives us concurrent readers and a single
writer without application-level locking. Every connection sets
foreign_keys ON and returns Row objects for named-column access.

The migration framework is intentionally minimal: a versioned table
plus an ordered list of (version, description, ddl) tuples. New
migrations append to the list; ``migrate()`` applies any not yet
recorded in ``schema_migrations``.
"""
from __future__ import annotations

import contextlib
import json
import sqlite3
import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.paths import RUNTIME_DIR

DB_PATH = RUNTIME_DIR / "mcbot.db"
SCHEMA_VERSION = 1
BUSY_TIMEOUT_MS = 5000


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


@contextlib.contextmanager
def get_connection(db_path: Optional[Path] = None):
    """Yield a connection with WAL, busy-timeout, and foreign keys.

    Use as ``with get_connection() as conn:`` — the block is the
    transaction scope (commit on clean exit, rollback on exception)
    and the connection is always closed afterward. Closing matters on
    Windows + WAL: a leaked connection pins the db file and leaves
    -wal/-shm residue behind."""
    path = db_path or DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), timeout=BUSY_TIMEOUT_MS / 1000.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=%d" % BUSY_TIMEOUT_MS)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Schema migrations (append-only; new versions go at the end)
# ---------------------------------------------------------------------------
_MIGRATIONS: list[tuple[int, str, list[str]]] = [
    (
        1,
        "initial schema: capabilities, qa_test_cases, test_receipts, test_case_runs",
        [
            """
            CREATE TABLE capabilities (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                description TEXT DEFAULT '',
                implementation_status TEXT NOT NULL DEFAULT 'gap',
                vanilla_ref TEXT DEFAULT '',
                deviation TEXT DEFAULT '',
                source_paths TEXT DEFAULT '[]',
                verified_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE qa_test_cases (
                id TEXT PRIMARY KEY,
                capability_id TEXT,
                title TEXT NOT NULL,
                requirement TEXT DEFAULT '',
                priority TEXT DEFAULT '',
                module TEXT DEFAULT '',
                test_type TEXT DEFAULT '',
                description TEXT DEFAULT '',
                steps TEXT DEFAULT '',
                expected_result TEXT DEFAULT '',
                related_risk TEXT DEFAULT '',
                test_data TEXT DEFAULT '',
                status TEXT NOT NULL DEFAULT 'not_executed',
                block_reason TEXT DEFAULT '',
                last_run_at TEXT,
                last_receipt_id INTEGER,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (capability_id) REFERENCES capabilities(id) ON DELETE SET NULL,
                FOREIGN KEY (last_receipt_id) REFERENCES test_receipts(id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE test_receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT UNIQUE,
                test_type TEXT NOT NULL,
                started_at TEXT,
                finished_at TEXT NOT NULL,
                git_rev TEXT,
                total INTEGER,
                passed INTEGER,
                failed INTEGER,
                green INTEGER,
                log_path TEXT DEFAULT '',
                details TEXT DEFAULT '{}',
                created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE test_case_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                test_case_id TEXT NOT NULL,
                receipt_id INTEGER NOT NULL,
                result TEXT NOT NULL,
                duration_ms INTEGER,
                details TEXT DEFAULT '{}',
                created_at TEXT NOT NULL,
                FOREIGN KEY (test_case_id) REFERENCES qa_test_cases(id) ON DELETE CASCADE,
                FOREIGN KEY (receipt_id) REFERENCES test_receipts(id) ON DELETE CASCADE,
                UNIQUE(test_case_id, receipt_id)
            )
            """,
            # indexes
            "CREATE INDEX idx_capabilities_category ON capabilities(category)",
            "CREATE INDEX idx_capabilities_status ON capabilities(implementation_status)",
            "CREATE INDEX idx_qa_cases_capability ON qa_test_cases(capability_id)",
            "CREATE INDEX idx_qa_cases_status ON qa_test_cases(status)",
            "CREATE INDEX idx_receipts_type ON test_receipts(test_type)",
            "CREATE INDEX idx_receipts_finished ON test_receipts(finished_at)",
            "CREATE INDEX idx_case_runs_case ON test_case_runs(test_case_id)",
            "CREATE INDEX idx_case_runs_receipt ON test_case_runs(receipt_id)",
        ],
    ),
    (
        2,
        "capability_status_transitions: append-only audit of status changes",
        [
            """
            CREATE TABLE capability_status_transitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                capability_id TEXT NOT NULL,
                old_status TEXT,
                new_status TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'manual',
                note TEXT DEFAULT '',
                changed_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_transitions_capability ON capability_status_transitions(capability_id)",
            "CREATE INDEX idx_transitions_changed ON capability_status_transitions(changed_at)",
        ],
    ),
    (
        3,
        "qa_test_cases: spec/impl split + link provenance + notes JSON",
        [
            # kind: 'spec' = human-authored test specification (TC-*),
            # 'impl' = source-derived gametest method (GT-*). Two
            # lifecycles, one table - the discriminator keeps the
            # receipt->case->capability attribution chain a single join.
            "ALTER TABLE qa_test_cases ADD COLUMN kind TEXT NOT NULL DEFAULT 'spec'",
            # link_source: csv | manual | auto - who declared the
            # capability link. NULL while unlinked.
            "ALTER TABLE qa_test_cases ADD COLUMN link_source TEXT",
            # notes: JSON object (risk_refs, fixture, block_reason, ...)
            "ALTER TABLE qa_test_cases ADD COLUMN notes TEXT NOT NULL DEFAULT '{}'",
            "ALTER TABLE qa_test_cases ADD COLUMN preconditions TEXT NOT NULL DEFAULT ''",
            "UPDATE qa_test_cases SET kind = 'impl' WHERE id LIKE 'GT-%'",
            "UPDATE qa_test_cases SET link_source = 'auto' WHERE capability_id IS NOT NULL",
            "CREATE INDEX idx_qa_cases_kind ON qa_test_cases(kind)",
        ],
    ),
    (
        4,
        "capabilities: harness_paths - the boundary-D surface that exercises each face",
        [
            # JSON array of harness paths (harness-interaction.md is the
            # path namespace's contract; the mapping face->paths is
            # catalog data curated in seed.py). Empty = face has no
            # direct harness exercise (internal reflex/sense faces are
            # pathless by design; others are review candidates).
            "ALTER TABLE capabilities ADD COLUMN harness_paths TEXT NOT NULL DEFAULT '[]'",
        ],
    ),
    (
        5,
        "capabilities: axis - sub-domain classification for coverage analysis",
        [
            # The behavioral axis this face belongs to within its category.
            # Used by `capability domain` to group faces by sub-axis and
            # compare against the player-behavior reference baseline.
            # Empty = unclassified (legacy faces; seed refresh will fill).
            "ALTER TABLE capabilities ADD COLUMN axis TEXT NOT NULL DEFAULT ''",
            "CREATE INDEX idx_capabilities_axis ON capabilities(axis)",
        ],
    ),
    (
        6,
        "reference_actions: machine-generated vanilla item-action inventory",
        [
            # Enumerated from the decompiled tree by ref_inventory.py
            # (capability ref-generate / ref-import). Rows carry their
            # own file anchor; mapped_face comes from the curated
            # face-map.json. The inventory JSON owns this table's
            # lifecycle - import prunes rows whose class left the tree.
            """
            CREATE TABLE reference_actions (
                class_name TEXT PRIMARY KEY,
                methods TEXT NOT NULL,
                file TEXT NOT NULL,
                mapped_face TEXT,
                note TEXT DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX idx_ref_actions_face ON reference_actions(mapped_face)",
        ],
    ),
    (
        7,
        "features: annotation-declared atomic behavior units + feature_id on test cases",
        [
            # Features are the atomic unit of a capability face. They
            # are declared in Java source via @Feature and discovered by
            # the offline scanner — never hand-seeded. source_file /
            # source_method / source_line pin the annotation location so
            # drift detection can flag moved or removed annotations.
            """
            CREATE TABLE features (
                id TEXT PRIMARY KEY,
                face_id TEXT NOT NULL,
                description TEXT DEFAULT '',
                vanilla_ref TEXT DEFAULT '',
                deviation TEXT DEFAULT '',
                source_file TEXT DEFAULT '',
                source_method TEXT DEFAULT '',
                source_line INTEGER DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (face_id) REFERENCES capabilities(id) ON DELETE CASCADE
            )
            """,
            "CREATE INDEX idx_features_face ON features(face_id)",
            # A test case may link to a specific feature in addition to
            # its face-level capability_id. The feature link is the
            # higher-resolution anchor; capability_id remains for
            # face-level aggregation. NULL means the test covers the
            # face broadly without pinning a single feature.
            "ALTER TABLE qa_test_cases ADD COLUMN feature_id TEXT",
            "CREATE INDEX idx_qa_cases_feature ON qa_test_cases(feature_id)",
        ],
    ),
]


def init_db(db_path: Optional[Path] = None) -> None:
    """Create the database file and apply all pending migrations.

    Idempotent: safe to call on every CLI invocation. Migrations
    already applied are skipped via the schema_migrations table.
    """
    with get_connection(db_path) as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations ("
            "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL, description TEXT DEFAULT '')"
        )
        applied = {
            row["version"]
            for row in conn.execute("SELECT version FROM schema_migrations").fetchall()
        }
        for version, description, ddls in _MIGRATIONS:
            if version in applied:
                continue
            for ddl in ddls:
                conn.execute(ddl)
            conn.execute(
                "INSERT INTO schema_migrations(version, applied_at, description) VALUES (?, ?, ?)",
                (version, _now_iso(), description),
            )
        conn.commit()


def db_status(db_path: Optional[Path] = None) -> dict:
    """Return a small health dict: exists, schema_version, table counts."""
    path = db_path or DB_PATH
    if not path.exists():
        return {"exists": False, "schema_version": 0, "tables": {}}
    with get_connection(path) as conn:
        version_row = conn.execute(
            "SELECT MAX(version) as v FROM schema_migrations"
        ).fetchone()
        version = version_row["v"] if version_row else 0
        counts = {}
        for table in [
            "capabilities", "qa_test_cases", "test_receipts",
            "test_case_runs", "capability_status_transitions",
            "features", "reference_actions",
        ]:
            try:
                row = conn.execute(f"SELECT COUNT(*) as c FROM {table}").fetchone()
                counts[table] = row["c"]
            except sqlite3.OperationalError:
                counts[table] = -1
    return {"exists": True, "path": str(path), "schema_version": version, "tables": counts}
