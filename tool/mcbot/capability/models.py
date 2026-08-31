"""Data models for the capability matrix.

Plain dataclasses — no ORM, no metaclass magic. The repository layer
maps between these and SQLite rows. Keeping models dumb makes the
storage swappable and the JSON import/export trivial.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class Capability:
    """A single player-behavior capability face.

    ``implementation_status`` vocabulary is pinned: shipped | partial
    | gap | deferred. Anything else is a schema defect.
    """
    id: str
    name: str
    category: str
    description: str = ""
    implementation_status: str = "gap"  # shipped, partial, gap, deferred
    vanilla_ref: str = ""
    deviation: str = ""
    source_paths: list[str] = field(default_factory=list)
    verified_at: Optional[str] = None  # YYYY-MM-DD
    created_at: str = ""
    updated_at: str = ""


@dataclass
class QATestCase:
    """A QA test case, linked to a capability.

    Imported from the manual CSV tracking sheets or created directly.
    ``status`` vocabulary: not_executed | passed | failed | blocked.

    ``kind`` separates the two case lifecycles sharing this table:
    'spec' (human-authored TC-* test specification) vs 'impl'
    (source-derived GT-* gametest method). ``link_source`` records
    who declared the capability link: csv | manual | auto.
    """
    id: str
    capability_id: Optional[str] = None
    title: str = ""
    requirement: str = ""
    priority: str = ""  # P0, P1, P2
    module: str = ""
    test_type: str = ""  # gametest, unit, manual
    description: str = ""
    preconditions: str = ""
    steps: str = ""
    expected_result: str = ""
    related_risk: str = ""
    test_data: str = ""
    notes: str = "{}"  # JSON: risk_refs, fixture, block_reason, ...
    kind: str = "spec"
    link_source: Optional[str] = None
    status: str = "not_executed"
    block_reason: str = ""
    last_run_at: Optional[str] = None
    last_receipt_id: Optional[int] = None
    created_at: str = ""
    updated_at: str = ""


@dataclass
class TestReceipt:
    """A single test run receipt (gametest, boundary-d, qa-run).

    Unified storage for every test result the project produces. The
    ``details`` JSON blob carries type-specific fields (failed test
    names, scenario breakdown, etc.).
    """
    id: Optional[int] = None
    run_id: str = ""
    test_type: str = ""  # gametest, boundary_d, qa_run
    started_at: Optional[str] = None
    finished_at: str = ""
    git_rev: Optional[str] = None
    total: Optional[int] = None
    passed: Optional[int] = None
    failed: Optional[int] = None
    green: Optional[bool] = None
    log_path: str = ""
    details: str = ""  # JSON blob
    created_at: str = ""
