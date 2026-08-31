"""Machine-generated vanilla player-action inventory.

The reference baseline is ENUMERATED from the decompiled tree, never
typed by a human: every class under ``net/minecraft/world/item`` that
overrides a player-action method (``use`` / ``useOn`` /
``releaseUsing`` / ``finishUsingItem``) becomes one entry carrying
its real file anchor. Completeness is by construction within the
declared scope - a gap list over this denominator is falsifiable; a
handwritten list never was.

Scope boundary (stated, not hidden): this enumerates CLASS-SPECIFIC
action overrides. Actions mediated entirely by the base ``Item``
interface without an override (e.g. plain food eating) are outside
this inventory; the capability faces carry their own verified
vanilla_ref for those. Base classes Item/ItemStack declare the
interface and are excluded.

Pipeline:
  capability ref-generate  -> qa-results/vanilla-reference/inventory.json
                              (committed; regenerate on MC bump)
  capability ref-import    -> reference_actions table (migration v5);
                              mapped_face from face-map.json - the one
                              curated layer, a small relation over the
                              complete base, same status as seed
                              HARNESS_PATHS

The decompiled tree is read-only input (AGENTS.md §0.1).
"""
from __future__ import annotations

import datetime as _dt
import json
import re
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.engine import git_head_short
from mcbot.paths import PROJECT_ROOT

DECOMPILED_ROOT = Path("D:/mc-decompiled/forge-1.20.1-47.4.10")
REFERENCE_DIR = PROJECT_ROOT / "qa-results" / "vanilla-reference"
INVENTORY_PATH = REFERENCE_DIR / "inventory.json"
FACE_MAP_PATH = REFERENCE_DIR / "face-map.json"

SCOPE = ("net/minecraft/world/item classes overriding "
         "use / useOn / releaseUsing / finishUsingItem (base Item/ItemStack excluded)")

# Player-action method signatures (mojmap 1.20.1). A class overriding
# any of them is part of the player's item-action surface.
_SIGNATURES: dict[str, re.Pattern] = {
    "use": re.compile(r"InteractionResultHolder<ItemStack> use\("),
    "useOn": re.compile(r"public InteractionResult useOn\("),
    "releaseUsing": re.compile(r"public void releaseUsing\("),
    "finishUsingItem": re.compile(r"public ItemStack finishUsingItem\("),
}
_BASE_CLASSES = {"Item", "ItemStack"}


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def generate_inventory(root: Optional[Path] = None) -> dict:
    """Walk the decompiled item tree and enumerate the action surface.

    Returns the inventory payload; every entry carries the methods
    found and the source file (relative to the decompiled root) -
    the anchor is by construction, there is nothing to hand-cite."""
    base = root or DECOMPILED_ROOT
    item_dir = base / "net" / "minecraft" / "world" / "item"
    classes = []
    for path in sorted(item_dir.rglob("*.java")):
        if path.stem in _BASE_CLASSES:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        methods = [m for m, rx in _SIGNATURES.items() if rx.search(text)]
        if methods:
            classes.append({
                "class": path.stem,
                "methods": methods,
                "file": path.relative_to(base).as_posix(),
            })
    return {
        "schema": 1,
        "scope": SCOPE,
        "generated_at": _now_iso(),
        "git_rev": git_head_short(),
        "classes": classes,
    }


def write_inventory(root: Optional[Path] = None) -> Path:
    payload = generate_inventory(root)
    REFERENCE_DIR.mkdir(parents=True, exist_ok=True)
    INVENTORY_PATH.write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8", newline="\n")
    return INVENTORY_PATH


def load_face_map() -> dict:
    """The curated class -> face relation over the complete base."""
    if not FACE_MAP_PATH.exists():
        return {}
    data = json.loads(FACE_MAP_PATH.read_text(encoding="utf-8"))
    return data.get("map", {})


def import_inventory(
    inventory_path: Optional[Path] = None,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Load the generated inventory (+ face map) into reference_actions.

    Idempotent on class name; the JSON is authoritative - rows whose
    class left the inventory are pruned (the inventory owns this
    table's lifecycle, scanner pattern)."""
    init_db(db_path)
    path = inventory_path or INVENTORY_PATH
    payload = json.loads(path.read_text(encoding="utf-8"))
    face_map = load_face_map()
    now = _now_iso()
    seen: set[str] = set()
    inserted = updated = 0
    with get_connection(db_path) as conn:
        for entry in payload.get("classes", []):
            cls = entry["class"]
            seen.add(cls)
            mapped = face_map.get(cls, {})
            face_id = mapped.get("face") if isinstance(mapped, dict) else mapped
            note = mapped.get("note", "") if isinstance(mapped, dict) else ""
            existing = conn.execute(
                "SELECT methods, mapped_face, note FROM reference_actions "
                "WHERE class_name = ?", (cls,)
            ).fetchone()
            if existing:
                if (existing["methods"] != json.dumps(entry["methods"])
                        or existing["mapped_face"] != face_id
                        or existing["note"] != note):
                    conn.execute(
                        "UPDATE reference_actions SET methods = ?, file = ?, "
                        "mapped_face = ?, note = ?, updated_at = ? WHERE class_name = ?",
                        (json.dumps(entry["methods"]), entry["file"], face_id,
                         note, now, cls),
                    )
                    updated += 1
            else:
                conn.execute(
                    """
                    INSERT INTO reference_actions (
                        class_name, methods, file, mapped_face, note,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (cls, json.dumps(entry["methods"]), entry["file"],
                     face_id, note, now, now),
                )
                inserted += 1
        placeholders = ", ".join("?" for _ in seen) if seen else "NULL"
        pruned = conn.execute(
            f"DELETE FROM reference_actions WHERE class_name NOT IN ({placeholders})",
            tuple(seen),
        ).rowcount
        conn.commit()
    return {
        "inventory_entries": len(payload.get("classes", [])),
        "inserted": inserted,
        "updated": updated,
        "pruned": pruned,
        "mapped": sum(1 for c in payload.get("classes", []) if c["class"] in face_map),
    }


def inventory_coverage(db_path: Optional[Path] = None) -> Optional[dict]:
    """Action surface x face map: the falsifiable coverage read-model.

    unmapped entries are engine actions with no capability face -
    each one is a real gap with a real anchor. mapped entries roll up
    by the face's category. Returns None when nothing is imported."""
    init_db(db_path)
    with get_connection(db_path) as conn:
        rows = conn.execute(
            """
            SELECT a.class_name, a.methods, a.file, a.mapped_face, a.note,
                   c.category as face_category,
                   c.implementation_status as face_status
            FROM reference_actions a
            LEFT JOIN capabilities c ON c.id = a.mapped_face
            ORDER BY a.class_name
            """
        ).fetchall()
        if not rows:
            return None
        entries = [dict(r) for r in rows]
    mapped = [e for e in entries if e["mapped_face"]]
    unmapped = [e for e in entries if not e["mapped_face"]]
    # a mapping pointing at a face that no longer exists is broken,
    # not coverage - surface it instead of counting it
    broken = [e for e in mapped if not e["face_category"]]
    by_category: dict[str, int] = {}
    for e in mapped:
        if e["face_category"]:
            by_category[e["face_category"]] = by_category.get(e["face_category"], 0) + 1
    return {
        "scope": SCOPE,
        "total": len(entries),
        "mapped": len(mapped) - len(broken),
        "unmapped": len(unmapped),
        "broken_mappings": [e["class_name"] for e in broken],
        "by_category": dict(sorted(by_category.items())),
        "entries": entries,
    }
