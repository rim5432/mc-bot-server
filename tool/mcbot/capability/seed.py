"""Capability face catalog, loaded from faces.yaml.

The catalog is data-driven: every face (id, name, category, axis,
description, implementation_status, vanilla_ref, deviation,
source_paths, harness_paths) lives in ``faces.yaml`` next to this
module. Edit the YAML, then run ``capability init`` to refresh the DB.

Seeding is idempotent: ``seed_database()`` skips any id that already
exists, so re-running after manual edits won't clobber statuses or
verified_at (those live in the state overlay). CATALOG fields refresh:
harness_paths and source_paths from the YAML always re-apply, so a new
mapping lands on the next init.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional

import yaml

from mcbot.capability.db import init_db
from mcbot.capability.models import Capability
from mcbot.capability.repository import CapabilityRepository

VERIFIED_AT = "2026-08-29"

_FACES_YAML = Path(__file__).with_name("faces.yaml")


def _load_catalog() -> tuple[list[Capability], dict[str, list[str]], dict[str, list[str]]]:
    """Load faces.yaml and return (caps, harness_paths, source_paths).

    The two derived dicts exist for backward compatibility with callers
    that import HARNESS_PATHS / SOURCE_PATHS directly; the canonical
    data is the per-face entries in the YAML.
    """
    raw = yaml.safe_load(_FACES_YAML.read_text(encoding="utf-8"))
    caps: list[Capability] = []
    harness: dict[str, list[str]] = {}
    sources: dict[str, list[str]] = {}
    for entry in raw["faces"]:
        cap = Capability(
            id=entry["id"],
            name=entry["name"],
            category=entry["category"],
            axis=entry.get("axis", ""),
            description=entry.get("description", ""),
            implementation_status=entry.get("implementation_status", "gap"),
            vanilla_ref=entry.get("vanilla_ref", ""),
            deviation=entry.get("deviation", ""),
            source_paths=entry.get("source_paths", []),
        )
        caps.append(cap)
        if entry.get("harness_paths"):
            harness[cap.id] = list(entry["harness_paths"])
        if entry.get("source_paths"):
            sources[cap.id] = list(entry["source_paths"])
    return caps, harness, sources


SEED_CAPABILITIES, HARNESS_PATHS, SOURCE_PATHS = _load_catalog()


def seed_database(db_path: Optional[Path] = None) -> dict:
    """Initialize the DB and insert any seed capabilities not yet present.

    Idempotent on statuses - existing rows keep their status /
    verified_at (those live in the state overlay) - but CATALOG
    fields refresh: harness_paths and source_paths from the YAML
    always re-apply, so a new mapping lands on the next init.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)
    inserted = 0
    skipped = 0
    for cap in SEED_CAPABILITIES:
        existing = repo.get(cap.id)
        if existing:
            skipped += 1
            hpaths = HARNESS_PATHS.get(cap.id, [])
            spaths = SOURCE_PATHS.get(cap.id, [])
            changed = False
            if existing.harness_paths != hpaths:
                existing.harness_paths = hpaths
                changed = True
            if existing.source_paths != spaths:
                existing.source_paths = spaths
                changed = True
            if existing.axis != cap.axis:
                existing.axis = cap.axis
                changed = True
            if changed:
                repo.upsert(existing)
            continue
        cap.verified_at = cap.verified_at or VERIFIED_AT
        cap.harness_paths = HARNESS_PATHS.get(cap.id, [])
        cap.source_paths = SOURCE_PATHS.get(cap.id, [])
        repo.upsert(cap)
        inserted += 1
    total = len(repo.list())
    return {"inserted": inserted, "skipped": skipped, "total": total}
