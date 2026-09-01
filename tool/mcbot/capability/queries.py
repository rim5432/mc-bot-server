"""Aggregate queries for the capability matrix.

These build on the repository's raw CRUD to produce the summary
shapes the CLI renders: per-category status breakdowns, gap
inventories, and coverage rollups. Keeping SQL here (rather than in
the CLI) means the same queries can back a future HTTP API or a
status dashboard without duplicating logic.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

from mcbot.capability.models import Capability
from mcbot.capability.repository import CapabilityRepository


def overview(db_path: Optional[Path] = None) -> dict:
    """Macro overview: total counts + per-category status breakdown.

    Returns::

        {
          "total": 42,
          "by_status": {"shipped": 20, "partial": 5, "gap": 12, "deferred": 5},
          "categories": {
            "digging": {"total": 8, "shipped": 5, "partial": 1, "gap": 2, "deferred": 0},
            ...
          }
        }
    """
    repo = CapabilityRepository(db_path)
    all_caps = repo.list()
    by_status = {"shipped": 0, "partial": 0, "gap": 0, "deferred": 0}
    categories: dict[str, dict] = {}

    for cap in all_caps:
        by_status[cap.implementation_status] = by_status.get(cap.implementation_status, 0) + 1
        if cap.category not in categories:
            categories[cap.category] = {
                "total": 0, "shipped": 0, "partial": 0, "gap": 0, "deferred": 0,
            }
        categories[cap.category]["total"] += 1
        categories[cap.category][cap.implementation_status] += 1

    return {
        "total": len(all_caps),
        "by_status": by_status,
        "categories": dict(sorted(categories.items())),
    }


def gaps(db_path: Optional[Path] = None) -> list[Capability]:
    """All capabilities with status gap or deferred, sorted by category."""
    repo = CapabilityRepository(db_path)
    return [c for c in repo.list() if c.implementation_status in ("gap", "deferred")]
