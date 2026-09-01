"""Macro read-models over the capability matrix: per-update diffs and
per-domain reports.

Both fold committed-artifact mirrors already in the DB (receipts,
case runs, links). They never touch source files, so a rebuilt DB
produces the same report.

Honest limit baked into the shapes: gametest logs list failures per
scenario but never passes, so green evidence is run-granular, not
per-capability.
"""
from __future__ import annotations

import datetime as _dt
import json
import subprocess
from pathlib import Path
from typing import Optional

from mcbot.capability.db import get_connection, init_db
from mcbot.capability.queries import overview
from mcbot.capability.ref_inventory import inventory_coverage
from mcbot.capability.repository import CapabilityRepository


def _since_ts(since: str) -> str:
    """Normalize a YYYY-MM-DD (or full ISO timestamp) to a comparable
    ISO string; date-only becomes midnight."""
    if len(since) == 10:
        return since + "T00:00:00"
    return since


def diff_since(since: str, *, db_path: Optional[Path] = None) -> dict:
    """Everything that changed since a date, one dict.

    - runs: receipt counts, green/red split, scenario growth, red-run
      details with failed scenarios (matched to case ids)
    - faces_added: capabilities whose created_at falls in the window
      (weak for the seed batch - all seeded rows share one timestamp)
    - current: today's overview for context
    """
    init_db(db_path)
    ts = _since_ts(since)
    with get_connection(db_path) as conn:
        runs_rows = conn.execute(
            """
            SELECT run_id, finished_at, git_rev, total, failed, green
            FROM test_receipts WHERE finished_at >= ?
            ORDER BY finished_at
            """,
            (ts,),
        ).fetchall()
        red_ids = [r["id"] for r in [
            dict(x) for x in conn.execute(
                "SELECT id FROM test_receipts WHERE finished_at >= ? AND green = 0",
                (ts,),
            ).fetchall()
        ]]
        red_details = []
        for rid in red_ids:
            run = dict(conn.execute(
                "SELECT run_id, finished_at, git_rev, total, failed FROM test_receipts WHERE id = ?",
                (rid,),
            ).fetchone())
            run["scenarios"] = [
                dict(x) for x in conn.execute(
                    """
                    SELECT test_case_id, details FROM test_case_runs
                    WHERE receipt_id = ? ORDER BY id
                    """,
                    (rid,),
                ).fetchall()
            ]
            red_details.append(run)
        faces_added = [
            dict(r) for r in conn.execute(
                "SELECT id, name, category, created_at FROM capabilities "
                "WHERE created_at >= ? ORDER BY created_at",
                (ts,),
            ).fetchall()
        ]
    runs = [dict(r) for r in runs_rows]
    return {
        "since": since,
        "runs": {
            "count": len(runs),
            "green": sum(1 for r in runs if r["green"]),
            "red": sum(1 for r in runs if not r["green"]),
            "first_total": runs[0]["total"] if runs else None,
            "last_total": runs[-1]["total"] if runs else None,
        },
        "red_details": red_details,
        "faces_added": faces_added,
        "current": overview(db_path),
    }


def harness_axis(db_path: Optional[Path] = None) -> dict:
    """The face -> boundary-D path axis, inverted for display, plus
    wire-surface evidence (boundary_d receipts + BD verdict counts).

    Mapped faces carry curated paths (seed.HARNESS_PATHS); pathless
    faces split nowhere automatically - internal reflex/sense faces
    are pathless by design, the rest are review candidates; the CLI
    lists them by category for that review."""
    init_db(db_path)
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT id, category, implementation_status, harness_paths "
            "FROM capabilities ORDER BY category, id"
        ).fetchall()
        by_path: dict[str, list[str]] = {}
        pathless: dict[str, list[str]] = {}
        for r in rows:
            paths = json.loads(r["harness_paths"] or "[]")
            if paths:
                for p in paths:
                    by_path.setdefault(p, []).append(r["id"])
            else:
                pathless.setdefault(r["category"], []).append(r["id"])
        wire = conn.execute(
            """
            SELECT COUNT(*) as runs, COALESCE(SUM(green), 0) as green_runs,
                   MAX(finished_at) as last_run
            FROM test_receipts WHERE test_type = 'boundary_d'
            """
        ).fetchone()
        verdicts = {
            r["result"]: r["c"]
            for r in conn.execute(
                "SELECT result, COUNT(*) as c FROM test_case_runs "
                "WHERE test_case_id LIKE 'BD-%' GROUP BY result"
            ).fetchall()
        }
    total = len(rows)
    mapped = total - sum(len(v) for v in pathless.values())
    return {
        "total_faces": total,
        "mapped_faces": mapped,
        "by_path": dict(sorted(by_path.items())),
        "pathless_by_category": dict(sorted(pathless.items())),
        "wire": dict(wire) if wire else None,
        "wire_verdicts": verdicts,
    }


def evidence_for_faces(db_path: Optional[Path] = None) -> dict:
    """Derived evidence axis: how the newest engine run treated each face.

    Pure read-model over receipts + links - computed on read, never
    stored, never a transition. Within one receipt a linked impl case
    either FAILED (recorded - Forge logs only failures) or was
    executed and passed. A face's evidence in the newest receipt is
    therefore RED when any linked impl failed there, GREEN when none
    did - but only if the run actually postdates every linked impl
    case: a case scanned after the run has no row in it, and
    absence-of-failure for a scenario the run never executed is not
    a pass. Faces whose newest run predates a linked impl (or with
    no linked impls at all) are UNTESTED; ``pending_impls`` counts
    the impls the newest run could not have executed, so the next
    action is visible (write a gametest vs run the engine).

    Returns {face_id: {state, red_runs, last_red_at, last_green_at,
    pending_impls}}.
    """
    init_db(db_path)
    with get_connection(db_path) as conn:
        newest = conn.execute(
            "SELECT id, finished_at FROM test_receipts "
            "WHERE test_type = 'runGameTest' ORDER BY id DESC LIMIT 1"
        ).fetchone()
        impl_stats = {
            r["capability_id"]: dict(r)
            for r in conn.execute(
                "SELECT capability_id, COUNT(*) as impls, "
                "SUM(CASE WHEN :fin IS NULL OR created_at > :fin THEN 1 ELSE 0 END) as pending "
                "FROM qa_test_cases "
                "WHERE kind = 'impl' AND capability_id IS NOT NULL "
                "GROUP BY capability_id",
                {"fin": newest["finished_at"] if newest else None},
            ).fetchall()
        }
        red_stats: dict[str, dict] = {}
        if newest:
            for r in conn.execute(
                """
                SELECT c.capability_id as face,
                       COUNT(DISTINCT r.id) as red_runs,
                       MAX(r.finished_at) as last_red_at,
                       MAX(CASE WHEN r.id = :newest_id THEN 1 ELSE 0 END) as failed_newest
                FROM test_case_runs cr
                JOIN qa_test_cases c ON c.id = cr.test_case_id
                JOIN test_receipts r ON r.id = cr.receipt_id
                WHERE c.kind = 'impl' AND c.capability_id IS NOT NULL
                  AND r.test_type = 'runGameTest'
                GROUP BY c.capability_id
                """,
                {"newest_id": newest["id"]},
            ).fetchall():
                red_stats[r["face"]] = dict(r)
    result: dict[str, dict] = {}
    faces = [r["id"] for r in _all_face_ids(db_path)]
    for face in faces:
        stats = impl_stats.get(face)
        if not stats:
            result[face] = {"state": "untested", "red_runs": 0,
                            "last_red_at": None, "last_green_at": None,
                            "pending_impls": 0}
            continue
        reds = red_stats.get(face)
        pending = stats["pending"]
        if reds and reds["failed_newest"]:
            result[face] = {"state": "red", "red_runs": reds["red_runs"],
                            "last_red_at": reds["last_red_at"], "last_green_at": None,
                            "pending_impls": pending}
        elif pending == 0:
            result[face] = {"state": "green", "red_runs": reds["red_runs"] if reds else 0,
                            "last_red_at": reds["last_red_at"] if reds else None,
                            "last_green_at": newest["finished_at"], "pending_impls": 0}
        else:
            result[face] = {"state": "untested", "red_runs": reds["red_runs"] if reds else 0,
                            "last_red_at": reds["last_red_at"] if reds else None,
                            "last_green_at": None, "pending_impls": pending}
    return result


def _all_face_ids(db_path: Optional[Path]) -> list:
    with get_connection(db_path) as conn:
        return conn.execute(
            "SELECT id FROM capabilities ORDER BY id"
        ).fetchall()


def evidence_rollup(db_path: Optional[Path] = None) -> dict:
    """Aggregate evidence counts + the honest convergence number:
    how many DECLARED-shipped faces actually carry green evidence."""
    evidence = evidence_for_faces(db_path)
    with get_connection(db_path) as conn:
        shipped = {
            r["id"] for r in conn.execute(
                "SELECT id FROM capabilities WHERE implementation_status = 'shipped'"
            ).fetchall()
        }
    counts = {"green": 0, "red": 0, "untested": 0}
    for ev in evidence.values():
        counts[ev["state"]] += 1
    counts["shipped_green"] = sum(
        1 for face, ev in evidence.items() if face in shipped and ev["state"] == "green"
    )
    counts["shipped"] = len(shipped)
    return counts


def status_suggestions(db_path: Optional[Path] = None) -> list[dict]:
    """Evidence-driven status review suggestions (never auto-applied).

    The implementation_status field is a human ruling on vanilla
    alignment - receipts cannot derive it. What receipts CAN do is
    flag faces where the declared status and the evidence disagree,
    so a human reviewer knows where to look. Suggestions are read-only;
    applying them is `capability set <id> <status>` with a reason.

    Rules:
    - shipped + RED in newest run -> recheck (possible regression)
    - shipped + UNTESTED -> add test anchor (shipped but no impl)
    - shipped + NO-SPEC -> add spec (shipped but no declared test intent)
    - partial/gap + GREEN + spec + impl -> consider promote
    - deferred faces are excluded (by design)
    """
    init_db(db_path)
    evidence = evidence_for_faces(db_path)
    with get_connection(db_path) as conn:
        caps = {
            r["id"]: r
            for r in conn.execute(
                "SELECT id, implementation_status FROM capabilities"
            ).fetchall()
        }
        counts = {
            r["capability_id"]: {"spec": r["spec"], "impl": r["impl"]}
            for r in conn.execute(
                """
                SELECT capability_id,
                       SUM(CASE WHEN kind = 'spec' THEN 1 ELSE 0 END) as spec,
                       SUM(CASE WHEN kind = 'impl' THEN 1 ELSE 0 END) as impl
                FROM qa_test_cases
                WHERE capability_id IS NOT NULL AND kind IN ('spec', 'impl')
                GROUP BY capability_id
                """
            ).fetchall()
        }
    suggestions: list[dict] = []
    for face, cap in caps.items():
        status = cap["implementation_status"]
        if status == "deferred":
            continue
        ev = evidence.get(face, {"state": "untested"})
        c = counts.get(face, {"spec": 0, "impl": 0})
        if status == "shipped":
            if ev["state"] == "red":
                suggestions.append({"face": face, "status": status, "suggestion": "recheck",
                                    "reason": "shipped but RED in newest engine run (possible regression)"})
            elif ev["state"] == "untested":
                pending = ev.get("pending_impls", 0)
                if pending:
                    suggestions.append({"face": face, "status": status, "suggestion": "run_engine",
                                        "reason": f"shipped but {pending} impl(s) scanned after the newest "
                                                  "engine run - the next runGameTest evidences or refutes them"})
                else:
                    suggestions.append({"face": face, "status": status, "suggestion": "add_test_anchor",
                                        "reason": "shipped but no impl (no automated test anchor)"})
            elif c["spec"] == 0:
                suggestions.append({"face": face, "status": status, "suggestion": "add_spec",
                                    "reason": "shipped but no spec (no declared testing intent in CSV)"})
        elif status in ("partial", "gap"):
            if ev["state"] == "green" and c["spec"] > 0 and c["impl"] > 0:
                suggestions.append({"face": face, "status": status, "suggestion": "consider_promote",
                                    "reason": f"{status} but GREEN evidence with spec({c['spec']})+impl({c['impl']})"})
    return suggestions


# Files that are shared substrate: nearly every face touches them, so
# their co-occurrence carries no overlap signal. Kept explicit rather
# than prefix-derived so a new plumbing file surfaces as (noise)
# overlap in the domain report instead of hiding silently.
_SHARED_PLUMBING = frozenset({
    "adapter/BotPlayerFacade.java",
    "adapter/entity/BotBodyEntity.java",
    "core/tick/BotController.java",
    "core/world/SnapshotWorldView.java",
    "adapter/sensing/LevelThreatSensor.java",
    "api/process/Overrides.java",
    "api/event/EventKind.java",
})

_SOURCE_PATH_PREFIX = "src/main/java/com/mcbot/mcbotserver/"


def _normalize_source_path(p: str) -> str:
    return p.replace("\\", "/").strip().removeprefix(_SOURCE_PATH_PREFIX)


def impl_overlap_map(db_path: Optional[Path] = None) -> dict[str, list[dict]]:
    """Cross-category implementation overlap between faces.

    Two faces overlap when their catalogued source_paths share a
    non-plumbing file across different categories - the computable
    form of the accepted domain-overlap relationship (e.g. hunting
    reuses combat's kill stack; see the AGENTS capability vocabulary).
    Read-model over the capabilities catalog: computed on read,
    never stored. Sharing is symmetric and implies no dependency
    direction; shared plumbing files are excluded.

    Returns {face_id: [{"face", "category", "shared": [paths]}]}
    with both sides of every cross-category pair populated.
    """
    init_db(db_path)
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT id, category, source_paths FROM capabilities ORDER BY id"
        ).fetchall()
    cat_of = {r["id"]: r["category"] for r in rows}
    face_paths: dict[str, list[str]] = {}
    by_path: dict[str, list[str]] = {}
    for r in rows:
        paths = [
            p for p in (
                _normalize_source_path(x)
                for x in json.loads(r["source_paths"] or "[]")
            )
            if p and p not in _SHARED_PLUMBING
        ]
        face_paths[r["id"]] = paths
        for p in paths:
            by_path.setdefault(p, []).append(r["id"])
    result: dict[str, list[dict]] = {}
    for face, paths in face_paths.items():
        overlaps: dict[str, set[str]] = {}
        for p in paths:
            for other in by_path.get(p, []):
                if other == face or cat_of[other] == cat_of[face]:
                    continue
                overlaps.setdefault(other, set()).add(p)
        if overlaps:
            result[face] = [
                {"face": other, "category": cat_of[other], "shared": sorted(shares)}
                for other, shares in sorted(overlaps.items())
            ]
    return result


def domain_report(category: str, *, db_path: Optional[Path] = None) -> Optional[dict]:
    """One capability domain: per-face evidence, coverage, deficiencies.

    Per face: status, verified_at, deviation flag, linked cases split
    into SPECS (kind='spec', human TC rows) and IMPLS (kind='impl',
    gametest methods) - the what-to-test vs what-actually-tests
    comparison on one axis - plus failure history from test_case_runs
    via linked cases. NO-SPEC faces carry no declared testing intent;
    NO-IMPL faces have no automated anchor. Domain level: green-run
    streak since the last run that failed a scenario in this domain.

    Statuses in the output are DECLARED human rulings - derivation
    from receipts is a separate, future layer; every consumer must
    render them as such."""
    init_db(db_path)
    evidence = evidence_for_faces(db_path)
    overlaps = impl_overlap_map(db_path)
    with get_connection(db_path) as conn:
        caps = [
            dict(r) for r in conn.execute(
                "SELECT id, name, implementation_status, verified_at, "
                "deviation, axis, updated_at FROM capabilities WHERE category = ? ORDER BY id",
                (category,),
            ).fetchall()
        ]
        if not caps:
            return None
        faces = []
        for cap in caps:
            case_rows = conn.execute(
                """
                SELECT id, kind FROM qa_test_cases
                WHERE capability_id = ? ORDER BY id
                """,
                (cap["id"],),
            ).fetchall()
            spec_count = sum(1 for r in case_rows if r["kind"] == "spec")
            impl_count = sum(1 for r in case_rows if r["kind"] != "spec")
            failures = [
                dict(r) for r in conn.execute(
                    """
                    SELECT r.run_id, r.finished_at, r.git_rev, cr.test_case_id, cr.details
                    FROM test_case_runs cr
                    JOIN test_receipts r ON r.id = cr.receipt_id
                    WHERE cr.test_case_id IN (
                        SELECT id FROM qa_test_cases WHERE capability_id = ?
                    )
                    ORDER BY r.finished_at DESC
                    """,
                    (cap["id"],),
                ).fetchall()
            ]
            faces.append({
                **cap,
                "axis": cap.get("axis") or "",
                "has_deviation": bool(cap["deviation"]),
                "cases": [r["id"] for r in case_rows],
                "case_count": len(case_rows),
                "spec_count": spec_count,
                "impl_count": impl_count,
                "no_spec": spec_count == 0,
                "no_impl": impl_count == 0,
                "failures": failures,
                "evidence": evidence.get(cap["id"], {
                    "state": "untested", "red_runs": 0,
                    "last_red_at": None, "last_green_at": None,
                    "pending_impls": 0}),
                "shares_impl_with": overlaps.get(cap["id"], []),
            })
        # last receipt that failed a scenario belonging to this domain
        last_red = conn.execute(
            """
            SELECT r.run_id, r.finished_at, r.git_rev
            FROM test_receipts r
            WHERE r.green = 0 AND EXISTS (
                SELECT 1 FROM test_case_runs cr
                JOIN qa_test_cases c ON c.id = cr.test_case_id
                WHERE cr.receipt_id = r.id AND c.capability_id IN (
                    SELECT id FROM capabilities WHERE category = ?
                )
            )
            ORDER BY r.finished_at DESC LIMIT 1
            """,
            (category,),
        ).fetchone()
        green_streak = 0
        if last_red:
            row = conn.execute(
                "SELECT COUNT(*) as c FROM test_receipts WHERE green = 1 AND finished_at > ?",
                (last_red["finished_at"],),
            ).fetchone()
            green_streak = row["c"]
    return {
        "category": category,
        "faces": faces,
        "faces_by_axis": _group_faces_by_axis(faces, category),
        "faces_no_spec": [f["id"] for f in faces if f["no_spec"]],
        "faces_no_impl": [f["id"] for f in faces if f["no_impl"]],
        "faces_with_deviation": [f["id"] for f in faces if f["has_deviation"]],
        "faces_shipped_untested": [
            f["id"] for f in faces
            if f["implementation_status"] == "shipped"
            and f["evidence"]["state"] == "untested"
        ],
        "last_red_in_domain": dict(last_red) if last_red else None,
        "green_streak_since": green_streak,
        "inventory": inventory_coverage(db_path),
    }


def _group_faces_by_axis(faces: list[dict], category: str) -> dict[str, list[dict]]:
    """Group faces by their axis field, using the canonical axis order.

    Faces with no axis (legacy rows) land under ``_unclassified``.
    """
    axes = sorted({f.get("axis") or "" for f in faces} - {""})
    grouped: dict[str, list[dict]] = {ax: [] for ax in axes}
    grouped["_unclassified"] = []
    for f in faces:
        ax = f.get("axis") or "_unclassified"
        grouped.setdefault(ax, []).append(f)
    return {k: v for k, v in grouped.items() if v}


# ---------------------------------------------------------------------------
# Source-path drift detection
# ---------------------------------------------------------------------------

# Reflex/sense faces that are pathless by design: the harness never invokes
# them directly, they fire from the tick pipeline. Listing them as coverage
# gaps would be noise. The set is explicit rather than prefix-based so a
# future perception face that DOES carry a path (e.g. a scan verb) is not
# silently excluded.
_INTERNAL_FACE_IDS = frozenset({
    "hunger.fooddata",
    "hunger.movement_exhaustion",
    "perception.death_flag",
    "perception.sleepers",
    "vitals.air_supply",
    "vitals.fire",
    "vitals.lava",
    "vitals.mlg_water",
    "vitals.powder_snow",
    "vitals.suffocation",
    "vitals.swimming",
})


def is_internal_face(face_id: str) -> bool:
    """True when this face is an internal reflex/sense with no boundary-D path by design."""
    return face_id in _INTERNAL_FACE_IDS


def _git_last_commit_ts(repo_root: Path, rel_path: str) -> Optional[int]:
    """Return the unix timestamp of the last commit touching rel_path, or None.

    rel_path is relative to the java source root; we resolve it against
    src/main/java/com/mcbot/mcbotserver/ before calling git.
    """
    full = repo_root / "src/main/java/com/mcbot/mcbotserver" / rel_path
    if not full.exists():
        return None
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%ct", "--", str(full)],
            cwd=repo_root, capture_output=True, text=True, timeout=10,
        )
        ts = out.stdout.strip()
        return int(ts) if ts else None
    except (subprocess.TimeoutExpired, ValueError):
        return None


def integrity_for_faces(
    db_path: Optional[Path] = None,
    *,
    repo_root: Optional[Path] = None,
) -> dict[str, dict]:
    """Per-face source integrity in one walk over the catalog.

    One `git log -1` per source path feeds two comparators (this used
    to be two near-identical functions, source_drift and
    staleness_for_faces, that each paid the full git walk —
    `capability status` paid it twice):

    1. MISSING — a catalogued source_path no longer exists on disk.
    2. DRIFT — the newest commit touching any path is newer than the
       face record's updated_at; the record may describe stale code.
       Faces with no source_paths are UNMAPPED (not an error — the
       seed may not have catalogued them yet).
    3. STALE — code changed after the face's most recent green engine
       run (a receipt where no linked impl failed). Needs impl links;
       a face with none is UNTESTED, never-green is NO_GREEN_EVER.

    Returns {face_id: {missing, state, days_drift, record_updated,
    last_code_change, stale_state, days_stale, last_green_at}} where
    state in {fresh, drift, missing, unmapped} is the record-integrity
    verdict and stale_state in {fresh, stale, untested, no_green_ever}
    the test-currency verdict.
    """
    if repo_root is None:
        repo_root = Path(__file__).resolve().parents[3]
    init_db(db_path)
    with get_connection(db_path) as conn:
        rows = conn.execute(
            "SELECT id, source_paths, updated_at FROM capabilities ORDER BY id"
        ).fetchall()
        impl_counts = {
            r["capability_id"]: r["c"]
            for r in conn.execute(
                "SELECT capability_id, COUNT(*) as c FROM qa_test_cases "
                "WHERE kind = 'impl' AND capability_id IS NOT NULL "
                "GROUP BY capability_id"
            ).fetchall()
        }
    result: dict[str, dict] = {}
    for r in rows:
        face = r["id"]
        paths = json.loads(r["source_paths"] or "[]")
        # shared plumbing: path existence + newest commit per path
        missing: list[str] = []
        last_code_ts: Optional[int] = None
        for p in paths:
            full = repo_root / "src/main/java/com/mcbot/mcbotserver" / p
            if not full.exists():
                missing.append(p)
                continue
            ts = _git_last_commit_ts(repo_root, p)
            if ts:
                last_code_ts = max(last_code_ts or 0, ts)
        last_code_dt = (
            _dt.datetime.fromtimestamp(last_code_ts, _dt.timezone.utc)
            if last_code_ts else None
        )
        # comparator 1: code vs record
        record_dt = None
        if r["updated_at"]:
            try:
                record_dt = _dt.datetime.fromisoformat(
                    r["updated_at"].replace("Z", "+00:00")
                )
                if record_dt.tzinfo is None:
                    record_dt = record_dt.replace(tzinfo=_dt.timezone.utc)
            except ValueError:
                record_dt = None
        if not paths:
            drift_state, days_drift = "unmapped", None
        elif missing:
            drift_state, days_drift = "missing", None
        elif last_code_dt and record_dt:
            days_drift = (last_code_dt - record_dt).total_seconds() / 86400
            drift_state = "drift" if days_drift > 0 else "fresh"
        else:
            days_drift = None
            drift_state = "fresh" if last_code_dt else "unmapped"
        # comparator 2: code vs last green
        last_green_str = None
        if impl_counts.get(face, 0) == 0:
            stale_state, days_stale = "untested", None
        else:
            with get_connection(db_path) as conn:
                green_row = conn.execute(
                    """
                    SELECT MAX(r.finished_at) as last_green_at
                    FROM test_receipts r
                    WHERE r.test_type = 'runGameTest'
                    AND r.id NOT IN (
                        SELECT cr.receipt_id
                        FROM test_case_runs cr
                        JOIN qa_test_cases c ON c.id = cr.test_case_id
                        WHERE c.kind = 'impl' AND c.capability_id = ?
                    )
                    """,
                    (face,),
                ).fetchone()
            last_green_str = green_row["last_green_at"] if green_row else None
            last_green_dt = None
            if last_green_str:
                try:
                    last_green_dt = _dt.datetime.fromisoformat(
                        last_green_str.replace("Z", "+00:00")
                    )
                    if last_green_dt.tzinfo is None:
                        last_green_dt = last_green_dt.replace(tzinfo=_dt.timezone.utc)
                except ValueError:
                    last_green_dt = None
            if last_code_dt and last_green_dt:
                days_stale = (last_code_dt - last_green_dt).total_seconds() / 86400
                stale_state = "stale" if days_stale > 0 else "fresh"
            else:
                days_stale = None
                stale_state = "fresh" if last_green_dt else "no_green_ever"
        result[face] = {
            "missing": missing,
            "state": drift_state,
            "days_drift": round(days_drift, 1) if days_drift is not None else None,
            "record_updated": r["updated_at"],
            "last_code_change": last_code_dt.isoformat() if last_code_dt else None,
            "stale_state": stale_state,
            "days_stale": round(days_stale, 1) if days_stale is not None else None,
            "last_green_at": last_green_str,
        }
    return result


# ---------------------------------------------------------------------------
# Action queue — the single "what needs a human, in what order" read-model
# ---------------------------------------------------------------------------

def _suggest_face_for_item(methods: list[str]) -> str:
    """Heuristic face suggestion for an unmapped inventory item.

    Based on which player-action methods the class overrides. This is a
    suggestion rendered in the audit output, not a ruling — the human
    edits face-map.json. Kept conservative: ambiguous cases say "new face"
    rather than guessing wrong.
    """
    m = set(methods)
    if "releaseUsing" in m:
        return "combat.bow_draw (charge/release) or new face"
    if "finishUsingItem" in m:
        return "hunger.eat_chain (consumable) or new face"
    if "useOn" in m and "use" not in m:
        return "interaction.blockitem_place or right_click_order"
    if "use" in m and "useOn" not in m:
        return "interaction.right_click_order or new face"
    return "needs manual classification"


def action_queue(
    db_path: Optional[Path] = None,
    *,
    repo_root: Optional[Path] = None,
) -> dict:
    """Aggregated, priority-sorted action queue.

    Priority bands:
      P0 — shipped but no automated evidence, or shipped but RED in the
           newest run. False-positive / regression risk: the declared
           status disagrees with what the tests say.
      P1 — coverage gaps: unmapped inventory items (with heuristic face
           suggestion), pathless non-internal faces, and gap/deferred
           faces that have no clear entry point.
      P2 — hygiene: source-path drift, partial/gap faces that now carry
           green evidence and could be promoted, shipped faces with no
           declared spec.

    Every item carries an ``action`` string — a concrete command or edit
    target — so the reader can copy-paste rather than interpret. The CLI
    renders this as one table; a future HTTP API can return it as JSON.

    Returns {items, summary, legend}.
    """
    init_db(db_path)
    repo = CapabilityRepository(db_path)
    all_faces = {c.id: c for c in repo.list()}
    evidence = evidence_for_faces(db_path)
    suggestions = status_suggestions(db_path)
    integrity = integrity_for_faces(db_path, repo_root=repo_root)
    harness = harness_axis(db_path)
    inv = inventory_coverage(db_path)

    items: list[dict] = []

    # --- P0: shipped without evidence, or shipped but red ---
    for face_id, cap in all_faces.items():
        if cap.implementation_status != "shipped":
            continue
        ev = evidence.get(face_id, {"state": "untested"})
        if ev["state"] == "untested":
            items.append({
                "priority": "P0",
                "target": face_id,
                "target_kind": "face",
                "gap": "shipped+NO-IMPL",
                "action": "add @GameTest method, then `capability scan-gametest`",
                "detail": f"declared shipped {cap.verified_at or '?'}, 0 impl cases linked",
            })
        elif ev["state"] == "red":
            items.append({
                "priority": "P0",
                "target": face_id,
                "target_kind": "face",
                "gap": "shipped+RED",
                "action": "investigate newest runGameTest failure, then re-run",
                "detail": f"last red {ev.get('last_red_at') or '?'}, {ev.get('red_runs', 0)} red run(s) total",
            })

    # --- P1: unmapped inventory items ---
    if inv and inv.get("unmapped", 0) > 0:
        unmapped = [e for e in inv["entries"] if not e["mapped_face"]]
        # Show at most 12 individually; the rest roll up into a count
        for e in unmapped[:12]:
            methods = json.loads(e["methods"] or "[]")
            items.append({
                "priority": "P1",
                "target": e["class_name"],
                "target_kind": "item",
                "gap": "unmapped-in-face-map",
                "action": f"edit face-map.json -> {_suggest_face_for_item(methods)}",
                "detail": f"methods={'+'.join(methods)}  ({e['file']})",
            })
        if len(unmapped) > 12:
            items.append({
                "priority": "P1",
                "target": f"... and {len(unmapped) - 12} more items",
                "target_kind": "item",
                "gap": "unmapped-in-face-map",
                "action": "run `capability ref-coverage` for the full list",
                "detail": "",
            })

    # --- P1: pathless non-internal faces ---
    for category, faces in harness.get("pathless_by_category", {}).items():
        for face_id in faces:
            if is_internal_face(face_id):
                continue
            cap = all_faces.get(face_id)
            if cap and cap.implementation_status == "deferred":
                continue  # deferred faces are explicitly out of scope
            items.append({
                "priority": "P1",
                "target": face_id,
                "target_kind": "face",
                "gap": "PATHLESS",
                "action": "add harness_paths in seed.py HARNESS_PATHS, then `capability init`",
                "detail": f"category={category}, status={cap.implementation_status if cap else '?'}",
            })

    # --- P1: gap/deferred faces (explicit backlog) ---
    for cap in repo.list():
        if cap.implementation_status in ("gap", "deferred"):
            items.append({
                "priority": "P1",
                "target": cap.id,
                "target_kind": "face",
                "gap": cap.implementation_status.upper(),
                "action": ("implement + test, then `capability set "
                           f"{cap.id} --status partial --verify`"),
                "detail": cap.deviation[:80] if cap.deviation else "",
            })

    # --- P2: source-path drift ---
    for face_id, d in integrity.items():
        if d["state"] == "missing":
            items.append({
                "priority": "P2",
                "target": face_id,
                "target_kind": "face",
                "gap": "source-path-MISSING",
                "action": "update seed.py SOURCE_PATHS (file moved/renamed), then `capability init`",
                "detail": f"missing: {', '.join(d['missing'])}",
            })
        elif d["state"] == "drift":
            items.append({
                "priority": "P2",
                "target": face_id,
                "target_kind": "face",
                "gap": "source-DRIFT",
                "action": "re-verify the face record matches current code, then `capability set --verify`",
                "detail": f"code changed {d['days_drift']}d after record update",
            })

    # --- P2: evidence-driven status suggestions (promote / add-spec) ---
    for s in suggestions:
        if s["suggestion"] == "consider_promote":
            items.append({
                "priority": "P2",
                "target": s["face"],
                "target_kind": "face",
                "gap": f"{s['status']}+GREEN",
                "action": f"review and `capability set {s['face']} --status shipped --verify`",
                "detail": s["reason"],
            })
        elif s["suggestion"] == "add_spec":
            items.append({
                "priority": "P2",
                "target": s["face"],
                "target_kind": "face",
                "gap": "shipped+NO-SPEC",
                "action": "add a TC-* row in the QA CSV, then `capability qa-import`",
                "detail": s["reason"],
            })

    # Sort: P0 before P1 before P2, then rollup rows (target starts with "...")
    # last within a band, then by target name.
    band_order = {"P0": 0, "P1": 1, "P2": 2}
    items.sort(key=lambda x: (
        band_order.get(x["priority"], 9),
        x["target"].startswith("..."),
        x["target"],
    ))

    summary: dict[str, int] = {}
    for it in items:
        summary[it["priority"]] = summary.get(it["priority"], 0) + 1

    legend = (
        "Legend: P0=shipped-without-evidence-or-red | P1=coverage-gap "
        "(unmapped items / pathless faces / gap-deferred) | "
        "P2=hygiene (drift / promote-candidate / missing-spec)"
    )
    return {"items": items, "summary": summary, "legend": legend}


