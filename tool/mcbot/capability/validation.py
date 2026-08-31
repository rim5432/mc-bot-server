"""Unified validation for all test artifact kinds (spec/impl/wire).

The parsing layer stays separated (CSV reader vs. Java source scanner vs.
boundary-d receipt parser), but everything after parsing - capability
foreign-key checks, link-source vocabulary, required-field completeness,
kind-specific rules, no_face consistency - lives here so a new test
source only writes a parser and gets validation for free.

An artifact that fails validation is still imported (the DB is the
system of record and partial data is better than lost data), but the
errors are collected and surfaced by `capability validate` and the
import summary. This matches the project's "never silently lose data"
rule: a malformed notes field is preserved under notes._raw, not
rejected.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Optional

from mcbot.capability.repository import CapabilityRepository

# ---------------------------------------------------------------------------
# Vocabulary (single source of truth; every parser and validator uses these)
# ---------------------------------------------------------------------------
VALID_KINDS = {"spec", "impl", "wire"}
VALID_LINK_SOURCES = {
    "csv",            # spec row: capability_id declared in the CSV
    "manual",         # human linked via `capability link`
    "auto",           # legacy: scanner auto-linked before confidence split
    "auto_keyword",   # impl: keyword rule matched
    "auto_class",     # impl: class->category fallback (single face in cat)
    "annotated",      # impl: explicit // capability: <id> comment above method
    "annotated_none", # impl: explicit // capability: none (intentionally unlinked)
    "no_face",        # spec: non-behavior case (code audit, test hygiene)
}
VALID_STATUSES = {"not_executed", "passed", "failed", "blocked"}
VALID_PRIORITIES = {"P0", "P1", "P2", ""}
VALID_TEST_TYPES = {"gametest", "unit", "manual", "code_audit", "boundary_d", ""}


@dataclass
class TestArtifact:
    """Uniform shape for any test artifact after parsing.

    ``source`` + ``line`` give traceability back to the origin file
    (CSV row number, Java method line, receipt filename). ``extra``
    carries kind-specific fields that don't fit the common shape but
    need validation (e.g. spec priority/test_type, impl method_name).
    """
    id: str
    kind: str
    title: str = ""
    capability_id: Optional[str] = None
    link_source: Optional[str] = None
    status: Optional[str] = None
    source: str = ""
    line: Optional[int] = None
    notes: str = "{}"
    extra: dict = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Individual validators (each returns None if OK, or an error string)
# ---------------------------------------------------------------------------

def validate_capability_fk(artifact: TestArtifact, repo: CapabilityRepository) -> Optional[str]:
    """capability_id must reference an existing face, unless the artifact
    is deliberately unlinked (no_face) or a wire contract case."""
    if artifact.capability_id is None:
        return None  # unlinked is valid (goes to triage)
    if artifact.kind == "wire":
        return None  # wire cases anchor to the wire contract, not faces
    if not repo.get(artifact.capability_id):
        return f"unknown capability_id: '{artifact.capability_id}'"
    return None


def validate_link_source(artifact: TestArtifact) -> Optional[str]:
    """link_source must be in the vocabulary; None means unlinked."""
    if artifact.link_source is None:
        return None
    if artifact.link_source not in VALID_LINK_SOURCES:
        return f"unknown link_source: '{artifact.link_source}' (valid: {sorted(VALID_LINK_SOURCES)})"
    return None


def validate_no_face_consistency(artifact: TestArtifact) -> Optional[str]:
    """no_face link_source implies capability_id is None; a no_face case
    that also carries a capability_id is contradictory."""
    if artifact.link_source == "no_face" and artifact.capability_id is not None:
        return f"link_source='no_face' but capability_id='{artifact.capability_id}' (contradictory)"
    return None


def validate_kind(artifact: TestArtifact) -> Optional[str]:
    if artifact.kind not in VALID_KINDS:
        return f"unknown kind: '{artifact.kind}' (valid: {sorted(VALID_KINDS)})"
    return None


def validate_required_fields(artifact: TestArtifact) -> list[str]:
    """id and kind are always required; title required for spec and impl
    (wire cases derive title from the receipt)."""
    errors: list[str] = []
    if not artifact.id:
        errors.append("missing required field: id")
    if not artifact.kind:
        errors.append("missing required field: kind")
    if artifact.kind in ("spec", "impl") and not artifact.title:
        errors.append("missing required field: title")
    return errors


def validate_spec_fields(artifact: TestArtifact) -> list[str]:
    """Spec-specific: priority/test_type/status vocabulary checks."""
    errors: list[str] = []
    priority = artifact.extra.get("priority", "")
    if priority not in VALID_PRIORITIES:
        errors.append(f"invalid priority: '{priority}' (valid: P0/P1/P2)")
    test_type = artifact.extra.get("test_type", "")
    if test_type not in VALID_TEST_TYPES:
        errors.append(f"invalid test_type: '{test_type}' (valid: {sorted(VALID_TEST_TYPES)})")
    if artifact.status and artifact.status not in VALID_STATUSES:
        errors.append(f"invalid status: '{artifact.status}' (valid: {sorted(VALID_STATUSES)})")
    return errors


def validate_impl_fields(artifact: TestArtifact) -> list[str]:
    """Impl-specific: method_name must be non-empty (the scanner derives
    title from it); impl rows don't carry a manual status (it's derived
    from receipts)."""
    errors: list[str] = []
    method_name = artifact.extra.get("method_name", "")
    if not method_name:
        errors.append("impl artifact missing method_name in extra")
    if artifact.status and artifact.status != "not_executed":
        errors.append(f"impl rows should not carry manual status '{artifact.status}' (derived from receipts)")
    return errors


def validate_notes_json(artifact: TestArtifact) -> Optional[str]:
    """notes must be valid JSON; a non-JSON value is preserved under
    notes._raw by the parser, so this only catches truly malformed data
    that the parser didn't wrap."""
    if not artifact.notes:
        return None
    try:
        json.loads(artifact.notes)
    except (json.JSONDecodeError, TypeError):
        return "notes is not valid JSON"
    return None


# ---------------------------------------------------------------------------
# Composite validator
# ---------------------------------------------------------------------------

def validate_artifact(artifact: TestArtifact, repo: CapabilityRepository) -> list[str]:
    """Run all validators appropriate to the artifact's kind.

    Returns a list of error strings (empty = valid). Never raises -
    validation errors are data, not exceptions.
    """
    errors: list[str] = []

    # Kind-agnostic checks
    kind_err = validate_kind(artifact)
    if kind_err:
        errors.append(kind_err)
        return errors  # can't validate kind-specific rules if kind is wrong

    errors.extend(validate_required_fields(artifact))

    fk_err = validate_capability_fk(artifact, repo)
    if fk_err:
        errors.append(fk_err)

    ls_err = validate_link_source(artifact)
    if ls_err:
        errors.append(ls_err)

    nf_err = validate_no_face_consistency(artifact)
    if nf_err:
        errors.append(nf_err)

    notes_err = validate_notes_json(artifact)
    if notes_err:
        errors.append(notes_err)

    # Kind-specific checks
    if artifact.kind == "spec":
        errors.extend(validate_spec_fields(artifact))
    elif artifact.kind == "impl":
        errors.extend(validate_impl_fields(artifact))

    return errors


def validate_all(artifacts: list[TestArtifact], repo: CapabilityRepository) -> list[tuple[TestArtifact, list[str]]]:
    """Validate a batch of artifacts; returns (artifact, errors) pairs
    for those with errors (empty list = all valid)."""
    results: list[tuple[TestArtifact, list[str]]] = []
    for artifact in artifacts:
        errors = validate_artifact(artifact, repo)
        if errors:
            results.append((artifact, errors))
    return results


# ---------------------------------------------------------------------------
# DB -> TestArtifact adapter (validate the live DB, not just import time)
# ---------------------------------------------------------------------------

def load_artifacts_from_db(db_path=None) -> list[TestArtifact]:
    """Read all qa_test_cases rows from the DB and map them to TestArtifact.

    This lets `capability validate` check the live state, not just
    import-time data. A row that was inserted before the vocabulary
    expanded (e.g. link_source='auto' from an older scanner) will be
    caught here even if the import path no longer produces it.
    """
    from mcbot.capability.db import get_connection, init_db
    init_db(db_path)
    artifacts: list[TestArtifact] = []
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT id, kind, title, capability_id, link_source, status, "
            "priority, test_type, notes, module FROM qa_test_cases ORDER BY id"
        ).fetchall()
    for r in rows:
        extra = {
            "priority": r["priority"] or "",
            "test_type": r["test_type"] or "",
            "module": r["module"] or "",
        }
        # impl rows carry method_name in their id (GT-<Class>-<method>);
        # parse it so the kind-specific validator can check it
        if r["kind"] == "impl" and r["id"].startswith("GT-"):
            parts = r["id"].split("-", 2)
            if len(parts) == 3:
                extra["method_name"] = parts[2]
        artifacts.append(TestArtifact(
            id=r["id"],
            kind=r["kind"] or "",
            title=r["title"] or "",
            capability_id=r["capability_id"],
            link_source=r["link_source"],
            status=r["status"],
            source="db:qa_test_cases",
            line=None,
            notes=r["notes"] or "{}",
            extra=extra,
        ))
    return artifacts


def validate_db(db_path=None) -> list[tuple[TestArtifact, list[str]]]:
    """Convenience: load all artifacts from the DB and validate them."""
    from mcbot.capability.repository import CapabilityRepository
    repo = CapabilityRepository(db_path)
    artifacts = load_artifacts_from_db(db_path)
    return validate_all(artifacts, repo)
