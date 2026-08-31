"""Gametest source scanner and capability auto-linking.

Scans ``src/main/java/.../gametest/`` for ``@GameTest``-annotated
methods, registers each as a QA test case (test_type='gametest',
source='gametest_scan'), and auto-links to a capability face via:

1. **Class map** — the test class name maps to a capability category
   (BotCombatGameTests→combat, BotDiggingGameTests→digging, ...).
2. **Method keywords** — the camelCase method name is split into
   tokens, lowercased, and matched against the same keyword rule
   table the CSV importer uses. Methods that match nothing fall back
   to the class's category if that category has exactly one
   capability; otherwise they stay unlinked.

Re-scanning is idempotent on the generated case id
(``GT-<ClassName>-<methodName>``); existing manual links are
preserved.
"""
from __future__ import annotations

import datetime as _dt
import re
from pathlib import Path
from typing import Optional

from mcbot.capability.db import init_db
from mcbot.capability.qa_import import (
    EXCLUDED_MODULES,
    KEYWORD_RULES,
    MODULE_CATEGORY_MAP,
    _get_case,
    _insert_case,
    _update_case,
)
from mcbot.capability.models import QATestCase
from mcbot.capability.repository import CapabilityRepository

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


def _split_camel(name: str) -> str:
    """Convert camelCase/PascalCase to lowercase space-separated tokens."""
    tokens = _CAMEL_SPLIT_RE.findall(name)
    return " ".join(t.lower() for t in tokens)


def _method_to_capability(
    method_name: str,
    class_name: str,
    repo: CapabilityRepository,
) -> Optional[str]:
    """Match a gametest method to a capability via keywords, then class fallback."""
    haystack = _split_camel(method_name)

    # Stage 1: keyword rules (same table as CSV import)
    for keywords, cap_id in KEYWORD_RULES:
        if any(kw.lower() in haystack for kw in keywords):
            if repo.get(cap_id):
                return cap_id

    # Stage 2: class → category fallback (only if exactly one capability
    # exists in that category — otherwise ambiguous, leave unlinked)
    category = CLASS_CATEGORY_MAP.get(class_name)
    if category:
        caps_in_cat = repo.list(category=category)
        if len(caps_in_cat) == 1:
            return caps_in_cat[0].id

    return None


def scan_gametests(
    src_root: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Scan all gametest source files and register @GameTest methods.

    Returns a summary dict. Idempotent on generated case ids.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)
    root = src_root or GAMETEST_SRC

    if not root.exists():
        return {"error": f"gametest source dir not found: {root}"}

    inserted = 0
    updated = 0
    auto_linked = 0
    unlinked = 0
    skipped_classes = 0
    total_methods = 0

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
            title = _split_camel(method_name).title()
            description = (
                f"Gametest scenario in {class_name}.java: {method_name}. "
                f"Auto-discovered from source by gametest scanner."
            )

            # Auto-link (preserve existing manual links)
            existing = _get_case(db_path, case_id)
            cap_id = None
            if existing and existing.capability_id:
                cap_id = existing.capability_id
            else:
                cap_id = _method_to_capability(method_name, class_name, repo)
                if cap_id:
                    auto_linked += 1
                else:
                    unlinked += 1

            now = _dt.datetime.now().isoformat(timespec="seconds")

            if existing:
                _update_case(
                    db_path, case_id,
                    capability_id=cap_id, title=title, module=category,
                    test_type="gametest", description=description,
                    updated_at=now,
                )
                updated += 1
            else:
                _insert_case(
                    db_path,
                    QATestCase(
                        id=case_id, capability_id=cap_id, title=title,
                        requirement="", priority="", module=category,
                        test_type="gametest", description=description,
                        steps="", expected_result="", related_risk="",
                        test_data="", status="not_executed", block_reason="",
                        created_at=now, updated_at=now,
                    ),
                )
                inserted += 1

    # Count total cases in DB
    from mcbot.capability.qa_import import _count_cases
    total = _count_cases(db_path)

    return {
        "scanned_files": len(list(root.glob("*.java"))),
        "skipped_classes": skipped_classes,
        "total_methods": total_methods,
        "inserted": inserted,
        "updated": updated,
        "auto_linked": auto_linked,
        "unlinked": unlinked,
        "total_cases": total,
    }
