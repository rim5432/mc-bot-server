"""QA test-case import (CSV) and case-link management.

The CSV is the single home for test SPECIFICATIONS (TC-*): the
10-column English format carries an explicit ``capability_id`` per
case - the author declares the behavior domain at write time, no
keyword guessing. Rows with a blank/unknown capability_id land on
the unlinked list for human triage. Re-importing is authoritative
for spec rows: the CSV wins over anything a verb changed in the DB
(manual triage on a spec row belongs in the CSV, not the DB).

gametest METHODS (GT-*) are a different lifecycle (kind='impl'):
scanned from source by gametest_scan, which owns those rows
including pruning. The keyword tables at the bottom of this module
exist only for that scanner's auto-link fallback.

CSV format::

    case_id,capability_id,title,priority,test_type,status,
    preconditions,steps,expected_result,notes

``notes`` is a JSON object (risk_refs, fixture, block_reason, ...);
a non-JSON value is preserved verbatim under notes._raw so nothing
is silently lost.
"""
from __future__ import annotations

import csv
import datetime as _dt
import json
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.models import QATestCase
from mcbot.capability.repository import CapabilityRepository

CSV_COLUMNS = [
    "case_id", "capability_id", "title", "priority", "test_type", "status",
    "preconditions", "steps", "expected_result", "notes",
]


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


# ---------------------------------------------------------------------------
# Row mapping (single place; every reader below uses it)
# ---------------------------------------------------------------------------
def _row_to_case(row) -> QATestCase:
    return QATestCase(
        id=row["id"], capability_id=row["capability_id"], title=row["title"],
        requirement=row["requirement"] or "", priority=row["priority"] or "",
        module=row["module"] or "", test_type=row["test_type"] or "",
        description=row["description"] or "", preconditions=row["preconditions"] or "",
        steps=row["steps"] or "", expected_result=row["expected_result"] or "",
        related_risk=row["related_risk"] or "", test_data=row["test_data"] or "",
        notes=row["notes"] or "{}", kind=row["kind"] or "spec",
        link_source=row["link_source"], status=row["status"],
        block_reason=row["block_reason"] or "", last_run_at=row["last_run_at"],
        last_receipt_id=row["last_receipt_id"], created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def _get_case(db_path: Optional[Path], case_id: str) -> Optional[QATestCase]:
    with get_connection(db_path) as conn:
        row = conn.execute(
            "SELECT * FROM qa_test_cases WHERE id = ?", (case_id,)
        ).fetchone()
    return _row_to_case(row) if row else None


def _insert_case(db_path: Optional[Path], case: QATestCase) -> None:
    with get_connection(db_path) as conn:
        conn.execute(
            """
            INSERT INTO qa_test_cases (
                id, capability_id, title, requirement, priority, module,
                test_type, description, preconditions, steps, expected_result,
                related_risk, test_data, notes, kind, link_source, status,
                block_reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                case.id, case.capability_id, case.title, case.requirement,
                case.priority, case.module, case.test_type, case.description,
                case.preconditions, case.steps, case.expected_result,
                case.related_risk, case.test_data, case.notes, case.kind,
                case.link_source, case.status, case.block_reason,
                case.created_at, case.updated_at,
            ),
        )
        conn.commit()


def _update_case(db_path: Optional[Path], case_id: str, **fields) -> None:
    if not fields:
        return
    sets = ", ".join(f"{k} = ?" for k in fields)
    params = list(fields.values()) + [case_id]
    with get_connection(db_path) as conn:
        conn.execute(f"UPDATE qa_test_cases SET {sets} WHERE id = ?", params)
        conn.commit()


def _count_cases(db_path: Optional[Path]) -> int:
    with get_connection(db_path) as conn:
        row = conn.execute("SELECT COUNT(*) as c FROM qa_test_cases").fetchone()
    return row["c"] if row else 0


# ---------------------------------------------------------------------------
# CSV import (v2 English format, explicit capability_id)
# ---------------------------------------------------------------------------
def import_csv(
    csv_path: Path,
    *,
    db_path: Optional[Path] = None,
) -> dict:
    """Import the v2 QA CSV into qa_test_cases (kind='spec').

    Authoritative for spec rows: existing rows are updated to match
    the CSV, including the capability link (link_source='csv'). A
    blank or unknown capability_id leaves the row unlinked for the
    unlinked list. Idempotent on case id.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)

    inserted = updated = linked = unlinked = skipped = 0
    invalid_caps: list[str] = []
    bad_notes: list[str] = []

    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        if "case_id" not in fieldnames:
            raise ValueError(
                "not a v2 QA csv (missing case_id column). The legacy "
                "13-column Chinese sheet is archived under "
                "qa-results/ranged-survival/archive/; translate to the "
                "10-column format documented in this module's docstring."
            )
        for row in reader:
            case_id = (row.get("case_id") or "").strip()
            if not case_id:
                skipped += 1
                continue

            cap_id = (row.get("capability_id") or "").strip() or None
            if cap_id and not repo.get(cap_id):
                invalid_caps.append(f"{case_id}->{cap_id}")
                cap_id = None

            notes_raw = (row.get("notes") or "").strip()
            notes = "{}"
            no_face = False
            if notes_raw:
                try:
                    parsed = json.loads(notes_raw)
                    notes = notes_raw
                    no_face = bool(parsed.get("no_face", False))
                except ValueError:
                    notes = json.dumps({"_raw": notes_raw})
                    bad_notes.append(case_id)

            now = _now_iso()
            link_source = "csv" if cap_id else ("no_face" if no_face else None)
            common = dict(
                capability_id=cap_id,
                link_source=link_source,
                title=(row.get("title") or "").strip(),
                priority=(row.get("priority") or "").strip(),
                test_type=(row.get("test_type") or "").strip(),
                status=(row.get("status") or "not_executed").strip(),
                preconditions=(row.get("preconditions") or "").strip(),
                steps=(row.get("steps") or "").strip(),
                expected_result=(row.get("expected_result") or "").strip(),
                notes=notes,
                kind="spec",
                updated_at=now,
            )
            if cap_id:
                linked += 1
            else:
                unlinked += 1

            if _get_case(db_path, case_id):
                _update_case(db_path, case_id, **common)
                updated += 1
            else:
                _insert_case(db_path, QATestCase(
                    id=case_id, requirement="", module="", description="",
                    related_risk="", test_data="", block_reason="",
                    created_at=now, **common,
                ))
                inserted += 1

    return {
        "inserted": inserted,
        "updated": updated,
        "linked": linked,
        "unlinked": unlinked,
        "skipped": skipped,
        "invalid_caps": invalid_caps,
        "bad_notes": bad_notes,
        "total": _count_cases(db_path),
    }


# ---------------------------------------------------------------------------
# Link management
# ---------------------------------------------------------------------------
def list_unlinked(db_path: Optional[Path] = None) -> list[QATestCase]:
    """Cases missing a capability link, specs and impls together (the
    CLI splits them by kind - two different problems). kind='wire'
    rows (boundary-d contract cases) are excluded: they anchor to the
    wire contract, not to behavior faces, by design. link_source=
    'no_face' rows (CSV-declared non-behavior cases like code audits
    and test hygiene) are also excluded: they deliberately carry no
    face link."""
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT * FROM qa_test_cases "
            "WHERE capability_id IS NULL AND kind != 'wire' "
            "AND link_source NOT IN ('no_face', 'annotated_none') ORDER BY id"
        ).fetchall()
    return [_row_to_case(r) for r in rows]


def link_case(
    case_id: str,
    capability_id: str,
    db_path: Optional[Path] = None,
) -> bool:
    """Manually link a case to a capability (link_source='manual').

    For kind='spec' rows the authoritative home is the CSV - a manual
    link survives until the next CSV import overwrites it. Impls
    (GT-*) keep manual links across rescans.
    """
    repo = CapabilityRepository(db_path)
    if not repo.get(capability_id):
        raise ValueError(f"capability not found: {capability_id}")
    with get_connection(db_path) as conn:
        cur = conn.execute(
            "UPDATE qa_test_cases SET capability_id = ?, link_source = 'manual', "
            "updated_at = ? WHERE id = ?",
            (capability_id, _now_iso(), case_id),
        )
        conn.commit()
    return cur.rowcount > 0


def cases_for_capability(
    capability_id: str,
    db_path: Optional[Path] = None,
) -> list[QATestCase]:
    """All cases linked to a capability."""
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT * FROM qa_test_cases WHERE capability_id = ? ORDER BY id",
            (capability_id,),
        ).fetchall()
    return [_row_to_case(r) for r in rows]


# ---------------------------------------------------------------------------
# Auto-link tables - GAMETEST SCANNER ONLY (impl rows).
#
# TC specs never pass through these: their capability_id is declared
# in the CSV. The haystack here is English camelCase method tokens,
# so Chinese keyword variants are inert but harmless.
# ---------------------------------------------------------------------------
# Modules that are NOT player-behavior faces - skip auto-linking.
EXCLUDED_MODULES: set[str] = {
    "hygiene", "rescue", "infra", "meta", "process", "tooling",
    "build", "ci", "docs", "test",
}

MODULE_CATEGORY_MAP: dict[str, str] = {
    "combat": "combat",
    "dig": "digging",
    "digging": "digging",
    "fishing": "perception",
    "sleep": "perception",
    "hunger": "hunger",
    "food": "hunger",
    "vitals": "vitals",
    "hazard": "vitals",
    "inventory": "inventory",
    "menu": "inventory",
    "interaction": "interaction",
    "place": "interaction",
    "use": "interaction",
    "motion": "motion",
    "movement": "motion",
    "perception": "perception",
    "sensing": "perception",
}

# Ordered (keywords, capability_id) rules; a rule fires when ANY
# keyword appears in the haystack. First hit wins.
KEYWORD_RULES: list[tuple[list[str], str]] = [
    # combat
    (["弓", "蓄力", "bow"], "combat.bow_draw"),
    (["弓", "箭速"], "combat.bow_draw"),
    (["弓", "释放"], "combat.bow_draw"),
    (["远程负载", "slot"], "combat.bow_slot"),
    (["有弓", "无箭"], "combat.bow_slot"),
    (["tipped", "spectral"], "combat.bow_slot"),
    (["盾", "shield"], "combat.shield"),
    (["盾", "格挡"], "combat.shield"),
    (["盾", "举盾"], "combat.shield"),
    (["盾", "收盾"], "combat.shield"),
    (["近战", "melee"], "combat.melee"),
    (["攻击速度", "暴击"], "combat.melee"),
    (["sword", "zombie", "kill", "cooldown", "sweep", "crit", "defend", "retaliate", "survive"], "combat.melee"),
    (["视线", "line_of_sight"], "combat.line_of_sight"),
    (["视线", "lava"], "combat.line_of_sight"),
    (["视线", "熔岩"], "combat.line_of_sight"),
    (["敌意", "hostile", "acquisition"], "combat.hostile_acquisition"),
    (["仇恨", "aggro"], "combat.hostile_acquisition"),

    # digging
    (["挖掘", "dig", "破坏进度"], "dig.pacing"),
    (["挖石头", "镐"], "dig.tool_speed"),
    (["挖树叶", "剪刀"], "dig.tool_speed"),
    (["工具", "自动切换"], "dig.tool_speed"),
    (["工具", "tool_speed"], "dig.tool_speed"),
    (["shears", "leaves", "toolselector", "tool selector"], "dig.tool_speed"),
    (["附魔", "enchant"], "dig.enchantment_loot"),
    (["match_tool", "掉落"], "dig.enchantment_loot"),
    (["diamond", "ore", "xp", "orb", "drop"], "dig.enchantment_loot"),

    # hunger
    (["饥饿", "hunger", "fooddata"], "hunger.fooddata"),
    (["食物", "food_level"], "hunger.fooddata"),
    (["消耗", "exhaustion", "移动"], "hunger.movement_exhaustion"),
    (["吃", "eat", "finishusingitem"], "hunger.eat_chain"),
    (["进食", "consume"], "hunger.eat_chain"),

    # vitals
    (["熔岩", "lava", "伤害"], "vitals.lava"),
    (["空气", "air", "溺水"], "vitals.air_supply"),
    (["空气", "supply"], "vitals.air_supply"),
    (["游泳", "swim", "fluid"], "vitals.swimming"),
    (["潜水", "dive"], "vitals.swimming"),
    (["细雪", "powder", "freeze"], "vitals.powder_snow"),
    (["冰冻", "freeze"], "vitals.powder_snow"),
    (["火", "fire", "燃烧"], "vitals.fire"),
    (["burning", "extinguish", "findwater", "regenerate", "health", "regen"], "vitals.fire"),
    (["窒息", "suffocation"], "vitals.suffocation"),
    (["窒息", "eye block"], "vitals.suffocation"),
    (["mlg", "水桶", "fall"], "vitals.mlg_water"),
    (["跌落", "fall damage"], "vitals.mlg_water"),
    (["water", "trench", "pool", "deep", "cross"], "vitals.swimming"),

    # perception
    (["钓鱼", "fishing", "浮漂"], "perception.projectiles"),
    (["咬钩", "dip"], "perception.projectiles"),
    (["抛竿", "收竿"], "perception.projectiles"),
    (["投射物", "projectile"], "perception.projectiles"),
    (["hooked", "reel", "bobber", "fish"], "perception.projectiles"),
    (["死亡", "death", "死亡标记"], "perception.death_flag"),
    (["睡眠", "sleep", "起床"], "perception.sleepers"),
    (["睡觉", "bed"], "perception.sleepers"),

    # inventory
    (["物品栏", "inventory", "slot"], "inventory.shape"),
    (["41 slot", "槽位"], "inventory.shape"),
    (["装备", "equipment", "mirror"], "inventory.equipment_mirror"),
    (["护甲", "armor", "attribute"], "inventory.equipment_mirror"),
    (["菜单", "menu", "click"], "inventory.menu_clicks"),
    (["合成", "craft", "recipe"], "inventory.menu_clicks"),
    (["chest", "store", "retrieve", "pickup", "anvil", "rename", "xp", "level", "double"], "inventory.menu_clicks"),
    (["护甲分类", "armor classification"], "inventory.armor_classification"),
    (["护甲", "保护", "protection"], "inventory.armor_classification"),

    # interaction
    (["右键", "right_click", "use"], "interaction.right_click_order"),
    (["放置", "place", "blockitem"], "interaction.blockitem_place"),
    (["方块", "direction", "state"], "interaction.blockitem_place"),
    (["桶", "bucket", "rod"], "interaction.bucket_rod"),
    (["钓鱼竿", "fishing rod"], "interaction.bucket_rod"),
    (["丢弃", "drop", "q "], "interaction.drop"),
    (["容器", "container", "menuprovider"], "interaction.menu_provider_skip"),
    (["useitemon", "deviation"], "interaction.use_item_deviations"),

    # motion
    (["潜行", "sneak", "pose"], "motion.sneak"),
    (["疾跑", "sprint"], "motion.sprint"),
    (["冲刺", "sprint"], "motion.sprint"),
    (["梯子", "ladder", "vine"], "motion.ladders_vines"),
    (["藤蔓", "climbable"], "motion.ladders_vines"),
]
