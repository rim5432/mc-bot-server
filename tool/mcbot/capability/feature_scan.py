"""Java source scanner for @Feature annotations.

Scans ``src/main/java`` for ``@Feature(...)`` annotations, parses their
fields, resolves the enclosing method/class/field, and upserts each as
a row in the features table. The scanner OWNS the feature lifecycle:
re-scanning is idempotent on the feature id, and features whose
annotation vanished from source are pruned.

Annotation parsing is regex-based (not a full Java parser) because the
annotation shape is constrained: string literals only, no nested
annotations, no array members. The regex tolerates whitespace, line
breaks, and trailing commas so formatting drift does not break discovery.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Optional

from mcbot.capability.db import init_db
from mcbot.capability.feature_repository import FeatureRepository
from mcbot.capability.models import Feature
from mcbot.capability.repository import CapabilityRepository

JAVA_SRC_ROOT = Path("src/main/java")

# ---------------------------------------------------------------------------
# Annotation extraction
# ---------------------------------------------------------------------------

# Match @Feature( ... ) including nested parens inside string literals.
# We use a non-greedy match up to the closing paren that is at the same
# nesting depth as the opening paren. Since @Feature only contains
# string literals (no nested annotations), a simple balanced-paren
# scanner after the @Feature token is sufficient.
_FEATURE_START_RE = re.compile(r"@Feature\s*\(", re.MULTILINE)

# Match a key = "value" pair inside the annotation body. Values are
# double-quoted strings; we capture the key and the unquoted value.
_ANNOTATION_FIELD_RE = re.compile(
    r'(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"',
    re.DOTALL,
)

# Match string concatenation: "foo" + "bar" -> merge into "foobar"
# before field parsing so multi-line annotation arguments are handled.
_STRING_CONCAT_RE = re.compile(r'"\s*\+\s*"')

# Match the method/class/field that an annotation precedes. We look
# backward from the annotation start to find the nearest declaration.
_METHOD_RE = re.compile(
    r"(?:public|private|protected|static|final|synchronized|abstract|native|default)\s+"
    r"(?:[\w<>\[\],\s]+?\s+)+"
    r"(\w+)\s*\(",
)
_CLASS_RE = re.compile(
    r"(?:public|private|protected|abstract|final|sealed|non-sealed)\s+"
    r"(?:class|interface|enum|record)\s+(\w+)",
)
_FIELD_RE = re.compile(
    r"(?:public|private|protected|static|final|volatile|transient)\s+"
    r"(?:[\w<>\[\],\s]+?\s+)+"
    r"(\w+)\s*[=;]",
)


def _extract_annotation_body(content: str, start: int) -> tuple[str, int]:
    """Extract the body of @Feature(...) starting at ``start``.

    ``start`` points at the '@' of @Feature. Returns (body, end_pos)
    where end_pos is the index after the closing paren. Returns
    ("", start) if the annotation is malformed.
    """
    # Find the opening paren
    paren_start = content.find("(", start)
    if paren_start == -1:
        return "", start

    depth = 0
    in_string = False
    escape = False
    for i in range(paren_start, len(content)):
        ch = content[i]
        if escape:
            escape = False
            continue
        if ch == "\\":
            escape = True
            continue
        if ch == '"':
            in_string = not in_string
            continue
        if in_string:
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return content[paren_start + 1 : i], i + 1
    return "", start


def _parse_annotation_fields(body: str) -> dict[str, str]:
    """Parse key = "value" pairs from an annotation body string.

    Multi-line string concatenations ("foo" + "bar") are merged before
    parsing so Spotless-formatted annotations are handled correctly.
    """
    merged = _STRING_CONCAT_RE.sub("", body)
    fields: dict[str, str] = {}
    for match in _ANNOTATION_FIELD_RE.finditer(merged):
        key = match.group(1)
        value = match.group(2)
        # Unescape Java string escapes
        value = value.replace('\\"', '"').replace("\\\\", "\\")
        fields[key] = value
    return fields


def _resolve_enclosing(
    content: str, annotation_start: int
) -> tuple[str, str]:
    """Resolve (kind, name) of the method/class/field the annotation decorates.

    Looks forward from the annotation end for the nearest declaration.
    Returns ("method", methodName), ("class", className),
    ("field", fieldName), or ("", "") if nothing matches.
    """
    # Look at the 500 chars after the annotation start — enough to
    # cross the annotation body and reach the declaration, but not so
    # far that we grab an unrelated declaration further down.
    window = content[annotation_start : annotation_start + 800]

    # Try method first (most common target)
    method_match = _METHOD_RE.search(window)
    if method_match:
        return "method", method_match.group(1)

    # Try class/interface/enum/record
    class_match = _CLASS_RE.search(window)
    if class_match:
        return "class", class_match.group(1)

    # Try field
    field_match = _FIELD_RE.search(window)
    if field_match:
        return "field", field_match.group(1)

    return "", ""


def _line_number(content: str, pos: int) -> int:
    """1-based line number for a character position."""
    return content.count("\n", 0, pos) + 1


# ---------------------------------------------------------------------------
# Scanner
# ---------------------------------------------------------------------------

def scan_features(
    src_root: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
    strict: bool = False,
) -> dict:
    """Scan all Java source files for @Feature annotations.

    Returns a summary dict. Idempotent on feature ids. Annotations
    whose ``face`` does not match a seeded capability are collected as
    ``invalid_annotations`` (strict mode) and skipped.

    In strict mode, the return includes ``invalid_annotations`` and
    ``orphan_features`` lists; ``strict_failures`` is the count of
    problems that should fail a CI gate.
    """
    init_db(db_path)
    repo = FeatureRepository(db_path)
    cap_repo = CapabilityRepository(db_path)
    root = src_root or JAVA_SRC_ROOT

    if not root.exists():
        return {"error": f"java source root not found: {root}"}

    inserted = 0
    updated = 0
    total_annotations = 0
    seen_ids: set[str] = set()
    invalid_annotations: list[dict] = []
    scanned_files = 0

    for java_file in sorted(root.rglob("*.java")):
        scanned_files += 1
        try:
            content = java_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        rel_path = str(java_file).replace("\\", "/")

        for match in _FEATURE_START_RE.finditer(content):
            total_annotations += 1
            ann_start = match.start()

            body, _end = _extract_annotation_body(content, ann_start)
            if not body:
                invalid_annotations.append({
                    "file": rel_path,
                    "line": _line_number(content, ann_start),
                    "reason": "malformed annotation body",
                })
                continue

            fields = _parse_annotation_fields(body)
            feature_id = fields.get("id", "")
            face_id = fields.get("face", "")

            if not feature_id or not face_id:
                invalid_annotations.append({
                    "file": rel_path,
                    "line": _line_number(content, ann_start),
                    "reason": "missing required id or face field",
                    "fields": list(fields.keys()),
                })
                continue

            # Validate that the parent face exists in the seed table
            if not cap_repo.get(face_id):
                invalid_annotations.append({
                    "file": rel_path,
                    "line": _line_number(content, ann_start),
                    "feature_id": feature_id,
                    "declared_face": face_id,
                    "reason": "face not found in capability seed table",
                })
                continue

            kind, enclosing_name = _resolve_enclosing(content, ann_start)
            source_method = enclosing_name if kind == "method" else ""
            if kind == "class":
                source_method = f"<class:{enclosing_name}>"
            elif kind == "field":
                source_method = f"<field:{enclosing_name}>"

            feature = Feature(
                id=feature_id,
                face=face_id,
                description=fields.get("description", ""),
                vanilla_ref=fields.get("vanillaRef", ""),
                deviation=fields.get("deviation", ""),
                source_file=rel_path,
                source_method=source_method,
                source_line=_line_number(content, ann_start),
            )

            existing = repo.get(feature_id)
            repo.upsert(feature)
            seen_ids.add(feature_id)
            if existing:
                updated += 1
            else:
                inserted += 1

    # Prune features whose annotation no longer exists in source. A
    # scan that saw zero Java files is a misfire (wrong root or broken
    # pattern), not an empty inventory — never prune on it. Zero
    # annotations across real files is a legitimate empty inventory.
    pruned = repo.prune(seen_ids) if scanned_files else 0

    result = {
        "scanned_files": scanned_files,
        "total_annotations": total_annotations,
        "inserted": inserted,
        "updated": updated,
        "pruned": pruned,
        "total_features": repo.count(),
    }
    if strict:
        result["invalid_annotations"] = invalid_annotations
        result["strict_failures"] = len(invalid_annotations)
    return result
