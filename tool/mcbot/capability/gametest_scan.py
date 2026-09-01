"""Gametest source scanner and capability auto-linking.

Scans ``src/main/java/.../gametest/`` for ``@GameTest``-annotated
methods, registers each as an implementation case (kind='impl',
test_type='gametest'), and auto-links to a capability face via:

1. **Class map** — the test class name maps to a capability category
   (BotCombatGameTests→combat, BotDiggingGameTests→digging, ...).
2. **Method keywords** — the camelCase method name is split into
   tokens, lowercased, and matched against the keyword rule table
   in qa_import (scanner-only fallback; specs never pass through it).

The scanner OWNS the impl lifecycle: re-scanning is idempotent on
the generated case id (``GT-<ClassName>-<methodName>``), existing
links are preserved (with their link_source - manual triage sticks),
and impl rows whose method vanished from source are pruned. Spec
(TC-*) rows are never touched.
"""
from __future__ import annotations

import datetime as _dt
import re
from pathlib import Path
from typing import Optional

from mcbot.capability.db import init_db, get_connection
from mcbot.capability.qa_import import (
    KEYWORD_RULES,
    _get_case,
    _insert_case,
    _update_case,
)
from mcbot.capability.models import QATestCase
from mcbot.capability.repository import CapabilityRepository
from mcbot.capability.feature_repository import FeatureRepository

GAMETEST_SRC = Path(
    "src/main/java/com/mcbot/mcbotserver/gametest"
)

# ---------------------------------------------------------------------------
# Test class → capability category map
# ---------------------------------------------------------------------------
CLASS_CATEGORY_MAP: dict[str, str] = {
    "BotCombatGameTests": "combat",
    "BotCraftingGameTests": "inventory",
    "BotDiggingGameTests": "digging",
    "BotFishingGameTests": "perception",
    "BotHazardReflexGameTests": "vitals",
    "BotInteractionGameTests": "interaction",
    "BotInventoryGameTests": "inventory",
    "BotLocomotionGameTests": "motion",
}

# Classes that are not behavior-face tests (framework, integration,
# crash-recovery). Skipped entirely.
EXCLUDED_CLASSES: set[str] = {
    "GametestRig",
    "CrashRecoveryGameTests",
    "GauntletGameTests",
    "ProductionWiringGameTests",
}

# Regex: @GameTest annotation (possibly with params) followed by a
# method signature. We capture the method name.
_GAMETEST_METHOD_RE = re.compile(
    r"@GameTest(?:\([^)]*\))?\s*"        # @GameTest or @GameTest(...)
    r"(?:@\w+(?:\([^)]*\))?\s*)*"        # other annotations (0+)
    r"(?:public\s+|static\s+|final\s+)*"  # modifiers (public static void)
    r"void\s+(\w+)\s*\(",                  # void methodName(
    re.MULTILINE,
)

_CLASS_RE = re.compile(r"public\s+(?:final\s+)?class\s+(\w+)")

# Split camelCase / PascalCase into tokens:
#   equipmentMirrorFeedsVanillaAttributes → equipment mirror feeds vanilla attributes
#   MLGWaterBucket → mlg water bucket
_CAMEL_SPLIT_RE = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|\d+")

# Explicit capability declaration in a comment above a @GameTest method:
#   // capability: combat.bow_draw
#   // capability: none          (intentionally unlinked - e.g. test infra,
#                                 non-behavior mechanics; not a strict failure)
# This is the highest-priority link source (annotated), above keyword
# matching and class fallback. The终局 is @GameTest(capability=...) but
# that requires Java source changes; comments are non-invasive and work
# with the current Forge @GameTest annotation signature.
_CAPABILITY_COMMENT_RE = re.compile(
    r"//\s*capability\s*:\s*([a-z_]+\.[a-z_]+|none)",
    re.IGNORECASE,
)

# Explicit feature declaration in a comment above a @GameTest method:
#   // feature: combat.melee.crit_hit
# This is the highest-resolution link: it pins the test to a single
# atomic feature within a face. When present, the capability_id is
# derived from the feature's parent face (looked up in the features
# table), so the test is linked at both granularities simultaneously.
_FEATURE_COMMENT_RE = re.compile(
    r"//\s*feature\s*:\s*([a-z_]+\.[a-z_]+\.[a-z_]+)",
    re.IGNORECASE,
)


def _extract_capability_comment(content: str, method_start: int) -> Optional[str]:
    """Walk backward from the method start to find a // capability: <id>
    comment in the immediately preceding lines (within 5 lines, so a
    stray comment elsewhere in the file does not hijack the link)."""
    lines_before = content[:method_start].splitlines()
    for line in reversed(lines_before[-6:]):  # 5 lines + the @GameTest line
        m = _CAPABILITY_COMMENT_RE.search(line)
        if m:
            return m.group(1).lower()
        # Stop walking if we hit a blank line or code (not a comment)
        stripped = line.strip()
        if stripped and not stripped.startswith("//") and not stripped.startswith("@"):
            break
    return None


def _extract_feature_comment(content: str, method_start: int) -> Optional[str]:
    """Walk backward from the method start to find a // feature: <id>
    comment in the immediately preceding lines. Feature links are the
    highest-resolution anchor; when present, the capability link is
    derived from the feature's parent face."""
    lines_before = content[:method_start].splitlines()
    for line in reversed(lines_before[-6:]):
        m = _FEATURE_COMMENT_RE.search(line)
        if m:
            return m.group(1).lower()
        stripped = line.strip()
        if stripped and not stripped.startswith("//") and not stripped.startswith("@"):
            break
    return None


def _split_camel(name: str) -> str:
    """Convert camelCase/PascalCase to lowercase space-separated tokens."""
    tokens = _CAMEL_SPLIT_RE.findall(name)
    return " ".join(t.lower() for t in tokens)


def _method_to_capability(
    method_name: str,
    class_name: str,
    repo: CapabilityRepository,
) -> tuple[Optional[str], Optional[str]]:
    """Match a gametest method to a capability via keywords, then class fallback.

    Returns (capability_id, link_source) where link_source distinguishes
    confidence: 'auto_keyword' (explicit keyword hit) vs 'auto_class'
    (ambiguous class-name fallback). Callers should prefer 'annotated'
    from an explicit comment declaration above either of these.
    """
    haystack = _split_camel(method_name)

    # Stage 1: keyword rules (same table as CSV import)
    for keywords, cap_id in KEYWORD_RULES:
        if any(kw.lower() in haystack for kw in keywords):
            if repo.get(cap_id):
                return cap_id, "auto_keyword"

    # Stage 2: class → category fallback (only if exactly one capability
    # exists in that category — otherwise ambiguous, leave unlinked)
    category = CLASS_CATEGORY_MAP.get(class_name)
    if category:
        caps_in_cat = repo.list(category=category)
        if len(caps_in_cat) == 1:
            return caps_in_cat[0].id, "auto_class"

    return None, None


def scan_gametests(
    src_root: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
    strict: bool = False,
) -> dict:
    """Scan all gametest source files and register @GameTest methods.

    Returns a summary dict. Idempotent on generated case ids.

    In strict mode, the return includes ``unlinked_methods`` and
    ``invalid_annotations`` lists, and ``strict_failures`` is the count
    of problems that should fail a CI gate (unlinked methods + invalid
    capability annotations). The caller decides whether to exit non-zero.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)
    feature_repo = FeatureRepository(db_path)
    root = src_root or GAMETEST_SRC

    if not root.exists():
        return {"error": f"gametest source dir not found: {root}"}

    inserted = 0
    updated = 0
    auto_linked = 0
    unlinked = 0
    skipped_classes = 0
    total_methods = 0
    seen_ids: set[str] = set()
    # Strict-mode diagnostics
    unlinked_methods: list[dict] = []
    invalid_annotations: list[dict] = []
    link_source_counts: dict[str, int] = {}

    for java_file in sorted(root.glob("*.java")):
        class_name = java_file.stem

        if class_name in EXCLUDED_CLASSES:
            skipped_classes += 1
            continue

        try:
            content = java_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        # Verify class name matches file (defensive)
        class_match = _CLASS_RE.search(content)
        if class_match:
            class_name = class_match.group(1)

        category = CLASS_CATEGORY_MAP.get(class_name, "unknown")

        for method_match in _GAMETEST_METHOD_RE.finditer(content):
            method_name = method_match.group(1)
            total_methods += 1

            case_id = f"GT-{class_name}-{method_name}"
            seen_ids.add(case_id)
            title = _split_camel(method_name).title()
            description = (
                f"Gametest scenario in {class_name}.java: {method_name}. "
                f"Auto-discovered from source by gametest scanner."
            )

            # Auto-link (preserve existing links AND their source:
            # manual triage sticks, fresh guesses are marked by confidence)
            existing = _get_case(db_path, case_id)
            link_fields: dict = {}

            # Priority 0: explicit // feature: <id> comment — the
            # highest-resolution anchor. When present, the capability
            # link is derived from the feature's parent face, so the
            # test is linked at both granularities simultaneously.
            feature_annotated = _extract_feature_comment(content, method_match.start())
            if feature_annotated:
                feature_row = feature_repo.get(feature_annotated)
                if feature_row:
                    link_fields["feature_id"] = feature_annotated
                    link_fields["capability_id"] = feature_row.face
                    link_fields["link_source"] = "feature_annotated"
                    ls = "feature_annotated"
                    auto_linked += 1
                else:
                    # Feature declared in comment but not yet scanned
                    # into the features table — collect for strict mode
                    # and fall through to capability-level linking.
                    invalid_annotations.append({
                        "case_id": case_id, "class": class_name,
                        "method": method_name, "file": java_file.name,
                        "declared_feature": feature_annotated,
                        "reason": "feature not found in features table (run feature scan first)",
                    })
                    feature_annotated = None  # fall through

            if not feature_annotated:
                if existing and existing.capability_id and not existing.feature_id:
                    link_fields["capability_id"] = existing.capability_id
                    link_fields["link_source"] = existing.link_source or "auto"
                    link_fields["feature_id"] = None
                    ls = link_fields["link_source"]
                elif existing and existing.feature_id:
                    # Preserve existing feature link (manual triage sticks)
                    link_fields["feature_id"] = existing.feature_id
                    link_fields["capability_id"] = existing.capability_id
                    link_fields["link_source"] = existing.link_source or "auto"
                    ls = link_fields["link_source"]
                else:
                    # Priority 1: explicit // capability: <id> comment above the method
                    annotated = _extract_capability_comment(content, method_match.start())
                    if annotated:
                        if annotated == "none":
                            # Intentionally unlinked (test infra, non-behavior mechanics)
                            link_fields["capability_id"] = None
                            link_fields["feature_id"] = None
                            link_fields["link_source"] = "annotated_none"
                            ls = "annotated_none"
                        elif repo.get(annotated):
                            link_fields["capability_id"] = annotated
                            link_fields["feature_id"] = None
                            link_fields["link_source"] = "annotated"
                            ls = "annotated"
                            auto_linked += 1
                        else:
                            # Annotation references a non-existent face - collect for strict mode
                            invalid_annotations.append({
                                "case_id": case_id, "class": class_name,
                                "method": method_name, "file": java_file.name,
                                "declared": annotated,
                            })
                            link_fields["capability_id"] = None
                            link_fields["feature_id"] = None
                            link_fields["link_source"] = None
                            ls = "unlinked"
                            unlinked += 1
                    else:
                        # Priority 2: keyword match, then class fallback
                        cap_id, link_src = _method_to_capability(method_name, class_name, repo)
                        link_fields["capability_id"] = cap_id
                        link_fields["feature_id"] = None
                        link_fields["link_source"] = link_src
                        ls = link_src or "unlinked"
                        if cap_id:
                            auto_linked += 1
                        else:
                            unlinked += 1
                            unlinked_methods.append({
                                "case_id": case_id, "class": class_name,
                                "method": method_name, "file": java_file.name,
                            })
            link_source_counts[ls] = link_source_counts.get(ls, 0) + 1

            now = _dt.datetime.now().isoformat(timespec="seconds")

            if existing:
                _update_case(
                    db_path, case_id,
                    title=title, module=category,
                    test_type="gametest", description=description,
                    kind="impl", updated_at=now,
                    **link_fields,
                )
                updated += 1
            else:
                _insert_case(
                    db_path,
                    QATestCase(
                        id=case_id, title=title, module=category,
                        test_type="gametest", description=description,
                        kind="impl", created_at=now, updated_at=now,
                        **link_fields,
                    ),
                )
                inserted += 1

    # Prune impl rows whose method no longer exists in source. The
    # scanner owns the impl lifecycle; specs are never pruned here.
    # An EMPTY scan never prunes: zero methods found almost always
    # means a wrong root or a broken pattern, not a vanished suite -
    # deleting the whole impl table on a misfire would be destructive.
    pruned = 0
    if seen_ids:
        with get_connection(db_path) as conn:
            placeholders = ", ".join("?" for _ in seen_ids)
            pruned = conn.execute(
                f"DELETE FROM qa_test_cases WHERE kind = 'impl' "
                f"AND id NOT IN ({placeholders})",
                tuple(seen_ids),
            ).rowcount
            conn.commit()

    # Count total cases in DB
    from mcbot.capability.qa_import import _count_cases
    total = _count_cases(db_path)

    result = {
        "scanned_files": len(list(root.glob("*.java"))),
        "skipped_classes": skipped_classes,
        "total_methods": total_methods,
        "inserted": inserted,
        "updated": updated,
        "auto_linked": auto_linked,
        "unlinked": unlinked,
        "pruned": pruned,
        "total_cases": total,
        "link_source_counts": link_source_counts,
    }
    if strict:
        result["unlinked_methods"] = unlinked_methods
        result["invalid_annotations"] = invalid_annotations
        result["strict_failures"] = len(unlinked_methods) + len(invalid_annotations)
    return result
