"""QA test case import and capability auto-linking.

Reads the manual CSV tracking sheets (ranged-survival, mechanics,
etc.) and loads them into the ``qa_test_cases`` table. Each case is
auto-linked to a capability face via a two-stage matcher:

1. **Module map** — the CSV ``模块`` column maps to a capability
   category (combat→combat, dig→digging, fishing→perception, ...).
2. **Keyword rules** — ordered keyword patterns matched against the
   case title + requirement; the first hit wins. Cases that match
   nothing are left ``capability_id = NULL`` and surfaced by
   ``capability unlinked`` for manual triage.

Manual links override auto-links and are never overwritten on
re-import (idempotent on the case id).
"""
from __future__ import annotations

import csv
import datetime as _dt
from pathlib import Path
from typing import Optional

from mcbot.capability.db import init_db
from mcbot.capability.models import QATestCase
from mcbot.capability.repository import CapabilityRepository

# Modules that are NOT player-behavior faces — skip auto-linking entirely.
# These are meta / infrastructure / process concerns, not vanilla mechanics
# the bot mirrors. They stay capability_id = NULL for manual triage.
EXCLUDED_MODULES: set[str] = {
    "hygiene", "rescue", "infra", "meta", "process", "tooling",
    "build", "ci", "docs", "test",
}

# ---------------------------------------------------------------------------
# Module → capability category map (coarse stage)
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Keyword → capability_id rules (fine stage, ordered — first hit wins)
# Each rule: (list of keyword substrings, capability_id)
# A rule fires when ALL of its keywords appear in the haystack
# (title + requirement + description, lowercased).
# ---------------------------------------------------------------------------
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
    (["附魔", "enchant"], "dig.enchantment_loot"),
    (["match_tool", "掉落"], "dig.enchantment_loot"),

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
    (["窒息", "suffocation"], "vitals.suffocation"),
    (["窒息", "eye block"], "vitals.suffocation"),
    (["mlg", "水桶", "fall"], "vitals.mlg_water"),
    (["跌落", "fall damage"], "vitals.mlg_water"),

    # perception
    (["钓鱼", "fishing", "浮漂"], "perception.projectiles"),
    (["咬钩", "dip"], "perception.projectiles"),
    (["抛竿", "收竿"], "perception.projectiles"),
    (["投射物", "projectile"], "perception.projectiles"),
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


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


def match_capability(
    title: str,
    requirement: str,
    description: str,
    module: str,
    repo: CapabilityRepository,
) -> Optional[str]:
    """Two-stage matcher: module map → keyword rules.

    Returns the capability_id or None if nothing matches.
    """
    # Excluded modules are never auto-linked (meta / infra concerns,
    # not player-behavior faces). They stay NULL for manual triage.
    if module.lower() in EXCLUDED_MODULES:
        return None

    haystack = f"{title} {requirement} {description}".lower()

    # Stage 2: keyword rules (fine-grained, first hit wins).
    # A rule fires when ANY of its keywords appears in the haystack
    # (synonyms grouped together; Chinese and English variants coexist).
    for keywords, cap_id in KEYWORD_RULES:
        if any(kw.lower() in haystack for kw in keywords):
            # verify the capability actually exists
            if repo.get(cap_id):
                return cap_id

    # Stage 1 fallback: module → category, pick the first capability
    # in that category if exactly one exists; otherwise None (ambiguous)
    category = MODULE_CATEGORY_MAP.get(module.lower())
    if category:
        caps_in_cat = repo.list(category=category)
        if len(caps_in_cat) == 1:
            return caps_in_cat[0].id

    return None


def import_csv(
    csv_path: Path,
    *,
    source: str = "manual_csv",
    db_path: Optional[Path] = None,
) -> dict:
    """Import a QA CSV file into the qa_test_cases table.

    Idempotent on case id: re-importing updates fields but preserves
    an existing manual capability_id link. Returns a summary dict.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)

    inserted = 0
    updated = 0
    auto_linked = 0
    unlinked = 0
    skipped = 0

    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            case_id = (row.get("用例ID") or "").strip()
            if not case_id:
                skipped += 1
                continue

            title = (row.get("用例标题") or "").strip()
            requirement = (row.get("需求") or "").strip()
            priority = (row.get("优先级") or "").strip()
            module = (row.get("模块") or "").strip()
            test_type = (row.get("类型") or "").strip()
            description = (row.get("描述") or "").strip()
            steps = (row.get("步骤") or "").strip()
            expected = (row.get("预期结果") or "").strip()
            related_risk = (row.get("关联风险机制") or "").strip()
            test_data = (row.get("测试数据") or "").strip()
            status = (row.get("状态") or "not_executed").strip()
            block_reason = (row.get("阻断原因") or "").strip()

            # Auto-link (only if not already manually linked)
            existing = _get_case(db_path, case_id)
            cap_id = None
            if existing and existing.capability_id:
                cap_id = existing.capability_id  # preserve manual link
            else:
                cap_id = match_capability(title, requirement, description, module, repo)
                if cap_id:
                    auto_linked += 1
                else:
                    unlinked += 1

            now = _now_iso()
            if existing:
                _update_case(
                    db_path, case_id,
                    capability_id=cap_id, title=title, requirement=requirement,
                    priority=priority, module=module, test_type=test_type,
                    description=description, steps=steps, expected_result=expected,
                    related_risk=related_risk, test_data=test_data,
                    status=status, block_reason=block_reason, updated_at=now,
                )
                updated += 1
            else:
                _insert_case(
                    db_path,
                    QATestCase(
                        id=case_id, capability_id=cap_id, title=title,
                        requirement=requirement, priority=priority, module=module,
                        test_type=test_type, description=description, steps=steps,
                        expected_result=expected, related_risk=related_risk,
                        test_data=test_data, status=status, block_reason=block_reason,
                        created_at=now, updated_at=now,
                    ),
                )
                inserted += 1

    total = _count_cases(db_path)
    return {
        "inserted": inserted,
        "updated": updated,
        "auto_linked": auto_linked,
        "unlinked": unlinked,
        "skipped": skipped,
        "total": total,
        "source": source,
    }


# ---------------------------------------------------------------------------
# Direct DB helpers for qa_test_cases (no repository class yet)
# ---------------------------------------------------------------------------
from mcbot.capability.db import get_connection


def _get_case(db_path: Optional[Path], case_id: str) -> Optional[QATestCase]:
    with get_connection(db_path) as conn:
        row = conn.execute(
            "SELECT * FROM qa_test_cases WHERE id = ?", (case_id,)
        ).fetchone()
    if not row:
        return None
    return QATestCase(
        id=row["id"], capability_id=row["capability_id"], title=row["title"],
        requirement=row["requirement"] or "", priority=row["priority"] or "",
        module=row["module"] or "", test_type=row["test_type"] or "",
        description=row["description"] or "", steps=row["steps"] or "",
        expected_result=row["expected_result"] or "", related_risk=row["related_risk"] or "",
        test_data=row["test_data"] or "", status=row["status"],
        block_reason=row["block_reason"] or "", last_run_at=row["last_run_at"],
        last_receipt_id=row["last_receipt_id"], created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def _insert_case(db_path: Optional[Path], case: QATestCase) -> None:
    with get_connection(db_path) as conn:
        conn.execute(
            """
            INSERT INTO qa_test_cases (
                id, capability_id, title, requirement, priority, module,
                test_type, description, steps, expected_result, related_risk,
                test_data, status, block_reason, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                case.id, case.capability_id, case.title, case.requirement,
                case.priority, case.module, case.test_type, case.description,
                case.steps, case.expected_result, case.related_risk, case.test_data,
                case.status, case.block_reason, case.created_at, case.updated_at,
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


def list_unlinked(db_path: Optional[Path] = None) -> list[QATestCase]:
    """Return all QA cases with no capability link."""
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT * FROM qa_test_cases WHERE capability_id IS NULL ORDER BY id"
        ).fetchall()
    return [
        QATestCase(
            id=r["id"], capability_id=r["capability_id"], title=r["title"],
            requirement=r["requirement"] or "", priority=r["priority"] or "",
            module=r["module"] or "", test_type=r["test_type"] or "",
            description=r["description"] or "", steps=r["steps"] or "",
            expected_result=r["expected_result"] or "", related_risk=r["related_risk"] or "",
            test_data=r["test_data"] or "", status=r["status"],
            block_reason=r["block_reason"] or "", last_run_at=r["last_run_at"],
            last_receipt_id=r["last_receipt_id"], created_at=r["created_at"],
            updated_at=r["updated_at"],
        )
        for r in rows
    ]


def link_case(
    case_id: str,
    capability_id: str,
    db_path: Optional[Path] = None,
) -> bool:
    """Manually link a QA case to a capability. Returns True if updated."""
    repo = CapabilityRepository(db_path)
    if not repo.get(capability_id):
        raise ValueError(f"capability not found: {capability_id}")
    with get_connection(db_path) as conn:
        cur = conn.execute(
            "UPDATE qa_test_cases SET capability_id = ?, updated_at = ? WHERE id = ?",
            (capability_id, _now_iso(), case_id),
        )
        conn.commit()
    return cur.rowcount > 0


def cases_for_capability(
    capability_id: str,
    db_path: Optional[Path] = None,
) -> list[QATestCase]:
    """Return all QA cases linked to a capability."""
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT * FROM qa_test_cases WHERE capability_id = ? ORDER BY id",
            (capability_id,),
        ).fetchall()
    return [
        QATestCase(
            id=r["id"], capability_id=r["capability_id"], title=r["title"],
            requirement=r["requirement"] or "", priority=r["priority"] or "",
            module=r["module"] or "", test_type=r["test_type"] or "",
            description=r["description"] or "", steps=r["steps"] or "",
            expected_result=r["expected_result"] or "", related_risk=r["related_risk"] or "",
            test_data=r["test_data"] or "", status=r["status"],
            block_reason=r["block_reason"] or "", last_run_at=r["last_run_at"],
            last_receipt_id=r["last_receipt_id"], created_at=r["created_at"],
            updated_at=r["updated_at"],
        )
        for r in rows
    ]
