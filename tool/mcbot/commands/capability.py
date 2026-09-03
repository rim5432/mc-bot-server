"""Capability matrix CLI commands.

Thin rendering layer over ``mcbot.capability.*`` modules. Moved out of
``cli.py`` to keep the dispatch file focused on wiring; all 24 ``capability``
subcommands live here plus the ``cmd_capability`` dispatch entry point.

The argparse wiring stays in ``cli.py`` — it calls ``cmd_capability(args)``
which dispatches to the per-action functions below.
"""
from __future__ import annotations

import datetime as _dt
import json
import sys
from pathlib import Path

from mcbot.capability import db as cap_db
from mcbot.capability import queries as cap_queries
from mcbot.capability.backfill import backfill_receipts
from mcbot.capability.feature_repository import FeatureRepository
from mcbot.capability.feature_scan import scan_features
from mcbot.capability.gametest_scan import scan_gametests
from mcbot.capability.qa_import import import_csv, link_case, list_unlinked
from mcbot.capability.report import (
    action_queue,
    diff_since,
    domain_report,
    evidence_for_faces,
    evidence_rollup,
    harness_axis,
    integrity_for_faces,
    is_internal_face,
    status_suggestions,
)
from mcbot.capability.repository import CapabilityRepository, VALID_STATUSES
from mcbot.capability.seed import seed_database
from mcbot.capability.state_export import restore_state, write_state_through
from mcbot.capability.validation import validate_db

def cmd_capability(args) -> int:
    dispatch = {
        "init": cmd_cap_init,
        "overview": cmd_cap_overview,
        "list": cmd_cap_list,
        "status": cmd_cap_status,
        "set": cmd_cap_set,
        "gaps": cmd_cap_gaps,
        "enginetests": cmd_cap_enginetests,
        "db-status": cmd_cap_db_status,
        "backfill": cmd_cap_backfill,
        "diff": cmd_cap_diff,
        "domain": cmd_cap_domain,
        "paths": cmd_cap_paths,
        "ref-generate": cmd_cap_ref_generate,
        "ref-import": cmd_cap_ref_import,
        "ref-coverage": cmd_cap_ref_coverage,
        "restore": cmd_cap_restore,
        "qa-import": cmd_cap_qa_import,
        "scan-gametest": cmd_cap_scan_gametest,
        "link": cmd_cap_link,
        "unlinked": cmd_cap_unlinked,
        "validate": cmd_cap_validate,
        "audit": cmd_cap_audit,
        "features": cmd_cap_features,
        "feature": cmd_cap_feature,
        "scan-features": cmd_cap_scan_features,
    }
    fn = dispatch.get(args.action)
    if fn is None:
        print(f"[mcbot] unknown capability action: {args.action}", file=sys.stderr)
        return 2
    return fn(args)


def cmd_cap_init(args) -> int:
    result = seed_database()
    print(f"[mcbot] capability db initialized")
    print(f"  inserted : {result['inserted']}")
    print(f"  skipped  : {result['skipped']} (already present)")
    print(f"  total    : {result['total']} capabilities")
    return 0


def cmd_cap_overview(args) -> int:
    ov = cap_queries.overview()
    if ov["total"] == 0:
        print("[mcbot] no capabilities yet — run `capability init` to seed")
        return 0
    ev = evidence_rollup()
    print(f"Capabilities: {ov['total']} total")
    bs = ov["by_status"]
    print(f"  shipped={bs.get('shipped', 0)}  partial={bs.get('partial', 0)}  "
          f"gap={bs.get('gap', 0)}  deferred={bs.get('deferred', 0)}")
    shipped_pct = (bs.get('shipped', 0) / ov['total'] * 100) if ov['total'] else 0
    print(f"  shipped rate: {shipped_pct:.1f}%*")
    print(f"  evidence (newest engine run): {ev['green']} green, {ev['red']} red, "
          f"{ev['untested']} untested")
    print(f"  honest convergence: {ev['shipped_green']}/{ev['shipped']} shipped faces "
          f"carry green evidence*")
    suggestions = status_suggestions()
    if suggestions:
        print(f"  review suggestions: {len(suggestions)} face(s) where declared status "
              f"disagrees with evidence:")
        for s in suggestions[:10]:
            print(f"    {s['face']:<30} [{s['status']:<8}] {s['suggestion']:<18} {s['reason']}")
        if len(suggestions) > 10:
            print(f"    ... and {len(suggestions) - 10} more")
    print("  * statuses are DECLARED (human rulings), not derived; evidence is derived "
          "from receipts on every read")
    print()
    print(f"{'CATEGORY':<16} {'TOTAL':>6} {'SHIP':>5} {'PART':>5} {'GAP':>5} {'DEF':>5}")
    print("-" * 50)
    for cat, data in ov["categories"].items():
        print(f"{cat:<16} {data['total']:>6} {data['shipped']:>5} {data['partial']:>5} "
              f"{data['gap']:>5} {data['deferred']:>5}")
    return 0


def cmd_cap_list(args) -> int:
    repo = CapabilityRepository()
    category = getattr(args, "category", None)
    status = getattr(args, "status", None)
    caps = repo.list(category=category, status=status)
    if not caps:
        filter_desc = []
        if category:
            filter_desc.append(f"category={category}")
        if status:
            filter_desc.append(f"status={status}")
        print(f"[mcbot] no capabilities matching {', '.join(filter_desc) or 'any filter'}")
        return 0
    print(f"{'ID':<32} {'STATUS':<10} {'CATEGORY':<14} NAME")
    print("-" * 100)
    for cap in caps:
        print(f"{cap.id:<32} {cap.implementation_status:<10} {cap.category:<14} {cap.name}")
    print(f"\n{len(caps)} capabilities")
    return 0


def cmd_cap_status(args) -> int:
    repo = CapabilityRepository()
    cap = repo.get(args.capability_id)
    if not cap:
        print(f"[mcbot] capability not found: {args.capability_id}", file=sys.stderr)
        print("  hint: run `capability list` to see available ids", file=sys.stderr)
        return 1
    print(f"=== {cap.id} ===")
    print(f"  name        : {cap.name}")
    print(f"  category    : {cap.category}")
    if cap.axis:
        print(f"  axis        : {cap.axis}")
    print(f"  status      : {cap.implementation_status}")
    print(f"  verified_at : {cap.verified_at or 'never'}")
    print(f"  created     : {cap.created_at}")
    print(f"  updated     : {cap.updated_at}")
    if cap.description:
        print(f"\n  description :")
        for line in cap.description.splitlines():
            print(f"    {line}")
    if cap.vanilla_ref:
        print(f"\n  vanilla_ref : {cap.vanilla_ref}")
    if cap.deviation:
        print(f"\n  deviation   :")
        for line in cap.deviation.splitlines():
            print(f"    {line}")
    if cap.source_paths:
        print(f"\n  source_paths:")
        for p in cap.source_paths:
            print(f"    - {p}")
    if cap.harness_paths:
        print(f"\n  harness     :")
        for p in cap.harness_paths:
            print(f"    - {p}")
    else:
        print(f"\n  harness     : (no direct boundary-D path)")
    ev = evidence_for_faces().get(cap.id)
    if ev:
        print(f"\n  evidence    : {ev['state'].upper()} "
              f"(newest engine run; {ev['red_runs']} red run(s) in history)")
        if ev["last_green_at"]:
            print(f"    last green: {ev['last_green_at']}")
        if ev["last_red_at"]:
            print(f"    last red  : {ev['last_red_at']}")
    ig = integrity_for_faces().get(cap.id, {})
    if ig:
        stale_label = ig["stale_state"].upper()
        if ig["stale_state"] == "stale" and ig["days_stale"] is not None:
            stale_label += f" ({ig['days_stale']:.1f}d since last green after code change)"
        print(f"\n  staleness   : {stale_label}")
        if ig["last_code_change"]:
            print(f"    code change: {ig['last_code_change']}")
        if ig["last_green_at"]:
            print(f"    last green : {ig['last_green_at']}")

    # --- record integrity panel: six axes, one glance ---
    ev = ev or {"state": "untested", "red_runs": 0}
    print()
    print("  RECORD INTEGRITY")
    # source paths
    if cap.source_paths:
        missing = ig.get("missing", [])
        if missing:
            print(f"    source_paths  : \u2717 MISSING: {', '.join(missing)}")
        else:
            print(f"    source_paths  : \u2713 ({len(cap.source_paths)} paths, all exist)")
    else:
        print(f"    source_paths  : \u2717 UNMAPPED — catalog implementation files in seed.py")
    # harness paths
    if cap.harness_paths:
        print(f"    harness_paths : \u2713 ({len(cap.harness_paths)} paths)")
    elif is_internal_face(cap.id):
        print(f"    harness_paths : \u2713 (internal reflex/sense — pathless by design)")
    else:
        print(f"    harness_paths : \u2717 PATHLESS — add boundary-D path in faces.yaml harness_paths")
    # test anchors
    if ev["state"] == "untested":
        print(f"    test anchors  : \u2717 NO-IMPL — no automated test evidence")
    elif ev["state"] == "red":
        print(f"    test anchors  : \u2717 RED in newest run ({ev.get('red_runs', 0)} red run(s) total)")
    else:
        print(f"    test anchors  : \u2713 {ev['state'].upper()}")
    # vanilla ref / deviation
    vanilla_mark = "\u2713" if cap.vanilla_ref else "\u2717 none"
    deviation_mark = "\u2713 recorded" if cap.deviation else "\u2713 none"
    print(f"    vanilla_ref   : {vanilla_mark}")
    print(f"    deviation     : {deviation_mark}")
    # code drift
    if ig.get("state") == "drift":
        print(f"    code drift    : \u2717 code changed {ig.get('days_drift', 0):.1f}d after record update")
    elif ig.get("state") == "missing":
        print(f"    code drift    : \u2717 source path missing (see above)")
    elif ig.get("state") == "fresh":
        print(f"    code drift    : \u2713 fresh")
    # NEXT action — the audit queue is the single advice engine; the
    # first queue row for this face is the highest-priority thing to do
    mine = [it for it in action_queue()["items"]
            if it["target"] == cap.id and it.get("target_kind") == "face"]
    print()
    print(f"  NEXT: {mine[0]['action']}" if mine
          else "  NEXT: record is clean — no action needed")
    return 0


def cmd_cap_set(args) -> int:
    repo = CapabilityRepository()
    if not repo.get(args.capability_id):
        print(f"[mcbot] capability not found: {args.capability_id}", file=sys.stderr)
        return 1
    if args.status and args.status not in VALID_STATUSES:
        print(f"[mcbot] invalid status: {args.status}", file=sys.stderr)
        print(f"  must be one of: {', '.join(sorted(VALID_STATUSES))}", file=sys.stderr)
        return 2
    verified_at = _dt.date.today().isoformat() if args.verify else None
    ok = repo.update_status(
        args.capability_id,
        args.status or repo.get(args.capability_id).implementation_status,
        deviation=args.deviation,
        verified_at=verified_at,
    )
    if not ok:
        print(f"[mcbot] update failed for {args.capability_id}", file=sys.stderr)
        return 1
    cap = repo.get(args.capability_id)
    print(f"[mcbot] updated {cap.id}")
    print(f"  status      : {cap.implementation_status}")
    if args.deviation is not None:
        print(f"  deviation   : {cap.deviation or '(cleared)'}")
    if verified_at:
        print(f"  verified_at : {cap.verified_at}")
    write_state_through()
    print("  state file  : qa-results/capability-state.json regenerated - commit it with your change")
    return 0


def cmd_cap_gaps(args) -> int:
    gaps = cap_queries.gaps()
    if not gaps:
        print("[mcbot] no gaps or deferred capabilities — all shipped or partial")
        return 0
    print(f"{'ID':<32} {'STATUS':<10} {'CATEGORY':<14} NAME")
    print("-" * 100)
    for cap in gaps:
        print(f"{cap.id:<32} {cap.implementation_status:<10} {cap.category:<14} {cap.name}")
    print(f"\n{len(gaps)} gap/deferred capabilities")
    return 0


def cmd_cap_enginetests(args) -> int:
    """Resolve the engine gametest classes backing one face's linked
    impl cases. The result is a WATCH LIST for the pooled engine run,
    not a filter: the 1.20.1 Forge runGameTestServer task takes no
    scenario selection (--tests is a JUnit Test-task option and fails
    task configuration), so the full pool is the only run mode and
    receipts are always full-pool. After a red pooled run, rerun and
    compare against this list to attribute flake (green on rerun) vs
    regression (same scenario red again)."""
    with cap_db.get_connection() as conn:
        rows = conn.execute(
            "SELECT id FROM qa_test_cases WHERE capability_id = ? AND id LIKE 'GT-%' ORDER BY id",
            (args.capability_id,),
        ).fetchall()
    if not rows:
        print(f"[mcbot] no linked impl cases for {args.capability_id}")
        print("  hint: `capability scan-gametest` links GT-* cases; `capability status <id>` shows anchors")
        return 1
    classes: dict = {}
    for (case_id,) in rows:
        # Case ids are GT-<ClassName>-<methodName>; java identifiers
        # carry no dash, so the first dash splits the pair.
        _, cls, method = case_id.split("-", 2)
        classes.setdefault(cls, []).append(method)
    print(f"=== enginetests: {args.capability_id} ===")
    for cls, methods in sorted(classes.items()):
        print(f"  {cls}  ({len(methods)} impl case(s))")
        for m in methods:
            print(f"    - {m}")
    print("\n  run mode: FULL POOL only - runGameTestServer (1.20.1 Forge) has no")
    print("  scenario filter, so watch these classes in the pooled receipt;")
    print("  a scoped receipt cannot exist and face evidence reads full-pool runs.")
    return 0


def cmd_cap_db_status(args) -> int:
    st = cap_db.db_status()
    if not st["exists"]:
        print("[mcbot] capability database does not exist yet")
        print("  hint: run `capability init` to create and seed")
        return 0
    print(f"[mcbot] capability db")
    print(f"  path          : {st.get('path', '?')}")
    print(f"  schema_version: {st['schema_version']}")
    print(f"  tables:")
    for table, count in st["tables"].items():
        print(f"    {table:<20} {count} rows")
    return 0


def cmd_cap_backfill(args) -> int:
    result = backfill_receipts()
    for family, label in (("engine_runs", "engine runs (gametest)"), ("boundary_d", "boundary-d wire runs")):
        r = result[family]
        print(f"[mcbot] {label}: {r['files']} receipts on disk")
        print(f"  inserted : {r['inserted']}  skipped: {r['skipped']}")
        print(f"  case rows: {r['case_rows']}", end="")
        if family == "engine_runs" and r.get("recovered"):
            print(f"  recovered: {r['recovered']} (failed names re-parsed from surviving logs)", end="")
        print()
        if r["unreadable"]:
            print(f"  WARN: {r['unreadable']} unreadable files", file=sys.stderr)
    return 0


def cmd_cap_diff(args) -> int:
    since = args.since or (_dt.date.today() - _dt.timedelta(days=7)).isoformat()
    d = diff_since(since)
    print(f"[mcbot] capability diff since {since}")
    runs = d["runs"]
    growth = ""
    if runs["count"]:
        growth = f", scenarios {runs['first_total']} -> {runs['last_total']}"
    print(f"engine runs: {runs['count']} ({runs['green']} GREEN, {runs['red']} RED){growth}")
    if d["red_details"]:
        shown = d["red_details"][:10]
        print(f"RED runs ({len(d['red_details'])}, showing first 10):")
        for run in shown:
            print(f"  {run['finished_at']} @{run['git_rev'] or '?'} "
                  f"{run['total']} scenarios, {run['failed']} failed  [{run['run_id']}]")
            for s in run["scenarios"]:
                print(f"      - {s['test_case_id'] or '(unlinked scenario)'}")
    if d["faces_added"]:
        print(f"faces added: {len(d['faces_added'])}")
        for f in d["faces_added"]:
            print(f"  {f['created_at']}  {f['id']}  ({f['category']})")
    cur = d["current"]
    bs = cur["by_status"]
    print(f"current: {cur['total']} faces, {bs.get('shipped', 0)} shipped, "
          f"{bs.get('partial', 0)} partial, {bs.get('gap', 0)} gap, "
          f"{bs.get('deferred', 0)} deferred")
    return 0


def _face_table_row(f: dict, integrity: dict) -> str:
    """One domain-table row: status, evidence, staleness, counts, flags."""
    flags = []
    if f["no_spec"]:
        flags.append("NO-SPEC")
    if f["no_impl"]:
        flags.append("NO-IMPL")
    if f["has_deviation"]:
        flags.append("DEVIATION")
    ev = f["evidence"]["state"].upper()
    if f["evidence"]["state"] == "green" and f["evidence"]["red_runs"]:
        ev += f"(x{f['evidence']['red_runs']})"
    st = integrity.get(f["id"], {})
    stale_str = st.get("stale_state", "-").upper()
    if st.get("stale_state") == "stale" and st.get("days_stale") is not None:
        stale_str += f"({st['days_stale']:.0f}d)"
    return (f"{f['id']:<28} {f['implementation_status'] + '*':<10} {ev:<9} {stale_str:<8} "
            f"{f['verified_at'] or '-':<12} "
            f"{f['spec_count']:>5} {f['impl_count']:>5}  {' '.join(flags)}")


def cmd_cap_domain(args) -> int:
    rep = domain_report(args.category)
    if rep is None:
        print(f"[mcbot] no capability category named: {args.category}", file=sys.stderr)
        print("  hint: `capability overview` lists the categories", file=sys.stderr)
        return 1
    lr = rep["last_red_in_domain"]
    streak = (
        f"last RED {lr['finished_at']} [{lr['run_id']}], {rep['green_streak_since']} green runs since"
        if lr else "no red run ever failed a scenario in this domain (in DB history)"
    )
    ev_counts = {"green": 0, "red": 0, "untested": 0}
    for f in rep["faces"]:
        ev_counts[f["evidence"]["state"]] += 1
    print(f"=== domain: {rep['category']} ===")
    print(f"  {streak}")
    print(f"  evidence: {ev_counts['green']} green, {ev_counts['red']} red, "
          f"{ev_counts['untested']} untested (from the newest engine run)")
    print()

    # --- coverage analysis (player-behavior reference baseline) ---
    inv = rep.get("inventory")
    if inv:
        print(f"--- vanilla action inventory: {inv['mapped']}/{inv['total']} "
              f"engine actions mapped to faces, {inv['unmapped']} unmapped "
              f"(see `capability ref-coverage`) ---")
        print()
    if rep.get("faces_shipped_untested"):
        print(f"  WARN: shipped but UNTESTED (no impl anchor): "
              f"{', '.join(rep['faces_shipped_untested'])}")
        print()

    integrity = integrity_for_faces()
    stale_count = sum(1 for s in integrity.values() if s["stale_state"] == "stale")
    if stale_count:
        print(f"  staleness: {stale_count} face(s) changed code since last green test")
    print(f"{'FACE':<28} {'STATUS':<10} {'EVID':<9} {'STALE':<8} {'VERIFIED':<12} {'SPECS':>5} {'IMPLS':>5}  FLAGS")
    print("-" * 115)
    faces_by_axis = rep.get("faces_by_axis", {})
    if faces_by_axis:
        for ax, faces in faces_by_axis.items():
            label = ax if ax != "_unclassified" else "(unclassified)"
            print(f"  [{label}]")
            for f in faces:
                print(_face_table_row(f, integrity))
    else:
        for f in rep["faces"]:
            print(_face_table_row(f, integrity))
    if rep["faces_no_spec"]:
        print(f"\n  no specs (no declared testing intent): {', '.join(rep['faces_no_spec'])}")
    if rep["faces_no_impl"]:
        print(f"  no impls (no automated anchor): {', '.join(rep['faces_no_impl'])}")
    overlaps = [(f["id"], f["shares_impl_with"]) for f in rep["faces"] if f["shares_impl_with"]]
    if overlaps:
        print("\n  impl overlap (shared non-plumbing source files across categories; "
              "no dependency direction implied):")
        for fid, entries in overlaps:
            for e in entries:
                print(f"    {fid} <-> {e['face']} [{e['category']}]: {', '.join(e['shared'])}")
    print("  * statuses are DECLARED (human rulings), not yet derived from receipts")
    failures_shown = 0
    for f in rep["faces"]:
        for fail in f["failures"][:2]:
            if failures_shown == 0:
                print("\n  recent failures (from mirrored receipts):")
            print(f"    {f['id']}: {fail['finished_at']} @{fail['git_rev'] or '?'} "
                  f"[{fail['run_id']}] {fail['test_case_id']}")
            failures_shown += 1
    return 0


def cmd_cap_restore(args) -> int:
    try:
        result = restore_state()
    except ValueError as e:
        print(f"[mcbot] {e}", file=sys.stderr)
        return 1
    print("[mcbot] capability state restored from overlay")
    print(f"  statuses : {result['statuses_applied']} applied, "
          f"{result['statuses_unchanged']} already matching")
    print(f"  links    : {result['links_applied']} applied, "
          f"{result['links_unchanged']} already matching")
    if result["missing_faces"] or result["missing_cases"]:
        print(f"  WARN: overlay references {result['missing_faces']} unknown faces, "
              f"{result['missing_cases']} unknown cases (skipped)", file=sys.stderr)
    return 0


def cmd_cap_ref_generate(args) -> int:
    from pathlib import Path
    from mcbot.capability.ref_inventory import write_inventory
    root = Path(args.decompiled_root) if args.decompiled_root else None
    path = write_inventory(root)
    from mcbot.capability.ref_inventory import generate_inventory
    inv = generate_inventory(root)
    print(f"[mcbot] vanilla action inventory generated: {path}")
    print(f"  scope    : {inv['scope']}")
    print(f"  classes  : {len(inv['classes'])} (machine-enumerated, file-anchored)")
    print("  commit it; `capability ref-import` folds it into the DB")
    return 0


def cmd_cap_ref_import(args) -> int:
    from mcbot.capability.ref_inventory import import_inventory
    result = import_inventory()
    print("[mcbot] vanilla action inventory imported (inventory owns the table lifecycle)")
    print(f"  entries : {result['inventory_entries']}")
    print(f"  inserted: {result['inserted']}  updated: {result['updated']}  "
          f"pruned: {result['pruned']}")
    print(f"  mapped  : {result['mapped']} (via face-map.json - the curated layer)")
    return 0


def cmd_cap_ref_coverage(args) -> int:
    from mcbot.capability.ref_inventory import inventory_coverage
    cov = inventory_coverage()
    if cov is None:
        print("[mcbot] no inventory imported yet - run `capability ref-import`")
        return 0
    print(f"[mcbot] vanilla action coverage (scope: {cov['scope']})")
    print(f"  {cov['mapped']}/{cov['total']} mapped, {cov['unmapped']} unmapped")
    by_cat = "  ".join(f"{c}={n}" for c, n in cov["by_category"].items())
    print(f"  mapped by face category: {by_cat or '-'}")
    if cov["broken_mappings"]:
        print(f"  WARN face-map keys pointing at missing faces: "
              f"{', '.join(cov['broken_mappings'])}", file=sys.stderr)
    unmapped = [e for e in cov["entries"] if not e["mapped_face"]]
    if unmapped:
        print(f"  unmapped engine actions ({len(unmapped)}) - each is a real gap "
              f"with a real anchor:")
        for e in unmapped:
            print(f"    {e['class_name']:<22} {'+'.join(json.loads(e['methods'])):<40} {e['file']}")
    return 0


def cmd_cap_paths(args) -> int:
    import json as _json
    axis = harness_axis()
    print(f"[mcbot] harness axis: {axis['mapped_faces']}/{axis['total_faces']} faces "
          f"mapped to boundary-D paths")
    print()
    print(f"{'PATH':<28} FACES")
    print("-" * 95)
    for path, faces in axis["by_path"].items():
        print(f"{path:<28} {', '.join(faces)} ({len(faces)})")
    print()
    print(f"pathless ({axis['total_faces'] - axis['mapped_faces']} faces) - internal "
          f"reflex/sense faces are pathless by design; the rest are review candidates:")
    for category, faces in axis["pathless_by_category"].items():
        print(f"  {category:<14} {', '.join(faces)}")
    # source path axis coverage (staleness prerequisite)
    from mcbot.capability.db import get_connection
    with get_connection() as conn:
        rows = conn.execute("SELECT id, source_paths FROM capabilities").fetchall()
    src_filled = sum(1 for r in rows if _json.loads(r["source_paths"] or "[]"))
    src_total = sum(len(_json.loads(r["source_paths"] or "[]")) for r in rows)
    print(f"\nsource axis: {src_filled}/{len(rows)} faces mapped to implementation files "
          f"({src_total} path references) — prerequisite for per-face staleness")
    wire = axis["wire"]
    if wire and wire["runs"]:
        verdicts = ", ".join(f"{v} {c}" for v, c in sorted(axis["wire_verdicts"].items()))
        print(f"\nwire evidence: {wire['runs']} boundary-d runs "
              f"({wire['green_runs']} green), last {wire['last_run']}")
        print(f"  BD case verdicts: {verdicts}")
    return 0


def cmd_cap_qa_import(args) -> int:
    from pathlib import Path
    csv_path = Path(args.csv_file)
    if not csv_path.exists():
        print(f"[mcbot] CSV file not found: {csv_path}", file=sys.stderr)
        return 1
    try:
        result = import_csv(csv_path)
    except ValueError as e:
        print(f"[mcbot] {e}", file=sys.stderr)
        return 2
    print("[mcbot] QA import complete (spec rows; CSV is authoritative)")
    print(f"  inserted : {result['inserted']}")
    print(f"  updated  : {result['updated']}")
    print(f"  linked   : {result['linked']} (capability_id declared in CSV, link_source=csv)")
    print(f"  unlinked : {result['unlinked']} (blank/unknown capability_id - see `capability unlinked`)")
    if result["skipped"]:
        print(f"  skipped  : {result['skipped']} (no case id)")
    if result["invalid_caps"]:
        print(f"  WARN unknown capability ids: {', '.join(result['invalid_caps'])}", file=sys.stderr)
    if result["bad_notes"]:
        print(f"  WARN non-JSON notes preserved under _raw: {', '.join(result['bad_notes'])}", file=sys.stderr)
    print(f"  total cases: {result['total']}")
    return 0


def cmd_cap_scan_gametest(args) -> int:
    strict = getattr(args, "strict", False)
    result = scan_gametests(strict=strict)
    if "error" in result:
        print(f"[mcbot] scan failed: {result['error']}", file=sys.stderr)
        return 1
    print(f"[mcbot] gametest scan complete")
    print(f"  scanned files : {result['scanned_files']}")
    print(f"  skipped classes: {result['skipped_classes']} (framework/integration)")
    print(f"  test methods  : {result['total_methods']}")
    if result["total_methods"] == 0:
        print("  WARN: zero @GameTest methods found - wrong source root or broken "
              "pattern? Nothing pruned.", file=sys.stderr)
    print(f"  inserted      : {result['inserted']}")
    print(f"  updated       : {result['updated']}")
    print(f"  auto_linked   : {result['auto_linked']}")
    print(f"  unlinked      : {result['unlinked']}")
    if result["pruned"]:
        print(f"  pruned        : {result['pruned']} (impl rows whose method left the source)")
    print(f"  total cases   : {result['total_cases']}")
    # Link-source confidence breakdown
    lsc = result.get("link_source_counts", {})
    if lsc:
        parts = [f"{k}={v}" for k, v in sorted(lsc.items())]
        print(f"  link sources  : {', '.join(parts)}")
    # Strict mode: detailed diagnostics + non-zero exit on failures
    if strict:
        failures = result.get("strict_failures", 0)
        if result.get("unlinked_methods"):
            print(f"\n  UNLINKED METHODS ({len(result['unlinked_methods'])}):")
            for m in result["unlinked_methods"][:20]:
                print(f"    {m['file']}:{m['class']}.{m['method']}")
            if len(result["unlinked_methods"]) > 20:
                print(f"    ... and {len(result['unlinked_methods']) - 20} more")
        if result.get("invalid_annotations"):
            print(f"\n  INVALID ANNOTATIONS ({len(result['invalid_annotations'])}):")
            for a in result["invalid_annotations"]:
                print(f"    {a['file']}:{a['class']}.{a['method']} -> "
                      f"declared '{a['declared']}' (not in capabilities table)")
        if failures:
            print(f"\n  STRICT: {failures} failure(s) — unlinked methods or invalid "
                  f"capability annotations. Fix before merge.", file=sys.stderr)
            return 1
        else:
            print(f"\n  STRICT: all {result['total_methods']} methods linked, all "
                  f"annotations valid.")
    return 0


def cmd_cap_link(args) -> int:
    try:
        ok = link_case(args.case_id, args.capability_id)
    except ValueError as e:
        print(f"[mcbot] {e}", file=sys.stderr)
        return 1
    if not ok:
        print(f"[mcbot] case not found: {args.case_id}", file=sys.stderr)
        return 1
    print(f"[mcbot] linked {args.case_id} -> {args.capability_id} (link_source=manual)")
    from mcbot.capability.db import get_connection
    with get_connection() as conn:
        row = conn.execute(
            "SELECT kind FROM qa_test_cases WHERE id = ?", (args.case_id,)
        ).fetchone()
    if row and row["kind"] == "spec":
        print("  note: spec links are CSV-owned - the next qa-import overwrites "
              "this; edit the CSV instead", file=sys.stderr)
    write_state_through()
    print("  state file  : qa-results/capability-state.json regenerated - commit it with your change")
    return 0


def cmd_cap_unlinked(args) -> int:
    cases = list_unlinked()
    if not cases:
        print("[mcbot] every case is linked to a capability")
        return 0
    specs = [c for c in cases if c.kind == "spec"]
    impls = [c for c in cases if c.kind != "spec"]
    if specs:
        print("spec (test specifications) — declare capability_id in the CSV, re-import:")
        for c in specs:
            print(f"  {c.id:<45} {c.title}")
    if impls:
        print("impl (gametest methods the auto-anchor missed) — "
              "`capability link <case> <cap>` or accept as uncovered:")
        for c in impls:
            print(f"  {c.id:<45} {c.title}")
    print(f"\n{len(specs)} unlinked specs, {len(impls)} unlinked impls")
    return 0


def cmd_cap_validate(args) -> int:
    """Unified validation: schema/vocabulary/FK checks on all test
    artifacts in the DB.

    Exit 0 if all valid; exit 1 if any errors (CI-gateable). Pure data
    hygiene — coverage questions (spec-only, impl-only, untested) are
    audit-queue material, not a second report here.
    """
    errors = validate_db()
    print(f"[mcbot] validation: {len(errors)} artifact(s) with errors")
    if errors:
        print()
        print(f"{'ARTIFACT':<50} {'KIND':<6} ERRORS")
        print("-" * 110)
        for artifact, errs in errors[:30]:
            print(f"{artifact.id:<50} {artifact.kind:<6} {'; '.join(errs)}")
        if len(errors) > 30:
            print(f"... and {len(errors) - 30} more")
        return 1
    return 0


def cmd_cap_audit(args) -> int:
    """Priority-sorted action queue: everything that needs a human, in order.

    The single command that answers 'what do I do next' without reading
    four other commands. P0 = shipped-without-evidence or shipped-red.
    P1 = coverage gaps (unmapped items / pathless faces / gap-deferred).
    P2 = hygiene (source drift / promote candidates / missing specs).
    Every row carries a copy-pasteable action string.
    """
    q = action_queue()
    items = q["items"]
    if not items:
        print("[mcbot] audit clean — no action items")
        return 0
    print(f"=== ACTION QUEUE ({len(items)} items) ===")
    print()
    print(f"{'PRI':<4} {'TARGET':<30} {'GAP':<24} ACTION")
    print("-" * 115)
    for item in items:
        print(f"{item['priority']:<4} {item['target']:<30} {item['gap']:<24} {item['action']}")
        if item.get("detail"):
            print(f"     {'':30} {'':24}   {item['detail']}")
    print()
    s = q["summary"]
    print(f"summary: P0={s.get('P0', 0)}  P1={s.get('P1', 0)}  P2={s.get('P2', 0)}")
    print(q["legend"])
    return 0


def cmd_cap_scan_features(args) -> int:
    """Scan Java source for @Feature annotations and upsert the features table."""
    result = scan_features(strict=args.strict)
    if "error" in result:
        print(f"[mcbot] {result['error']}", file=sys.stderr)
        return 1
    print(f"[mcbot] feature scan complete")
    print(f"  scanned files  : {result['scanned_files']}")
    print(f"  annotations    : {result['total_annotations']}")
    print(f"  inserted       : {result['inserted']}")
    print(f"  updated        : {result['updated']}")
    print(f"  pruned         : {result['pruned']}")
    print(f"  total features : {result['total_features']}")
    if args.strict and result.get("strict_failures", 0) > 0:
        print(f"\n[mcbot] STRICT FAILURES: {result['strict_failures']}")
        for inv in result.get("invalid_annotations", []):
            print(f"  {inv['file']}:{inv['line']} — {inv['reason']}"
                  f" (feature={inv.get('feature_id', '?')}, face={inv.get('declared_face', '?')})")
        return 1
    return 0


def _feature_test_count(feature_id: str) -> int:
    """Number of test cases anchored to a feature (raw count, no status
    math — face-level truth lives on the receipt evidence axis)."""
    from mcbot.capability.db import get_connection
    with get_connection() as conn:
        row = conn.execute(
            "SELECT COUNT(*) as c FROM qa_test_cases WHERE feature_id = ?",
            (feature_id,),
        ).fetchone()
    return row["c"] if row else 0


def cmd_cap_features(args) -> int:
    """Annotation inventory: what @Feature declares, where it lives,
    and how many tests anchor to it. Deliberately status-free."""
    feat_repo = FeatureRepository()
    if args.face:
        cap = CapabilityRepository().get(args.face)
        feats = feat_repo.list(face=args.face)
        if not feats:
            print(f"[mcbot] face '{args.face}' has no @Feature annotations"
                  + ("" if cap else " (face itself is not in the seed)"))
            print(f"  hint: add @Feature(id=..., face='{args.face}', ...) to the implementing methods")
            return 0
        print(f"=== FEATURES: {args.face} ({cap.name if cap else 'unknown face'}) ===")
        print(f"{'FEATURE ID':<40} {'TESTS':>5}  SOURCE")
        print("-" * 100)
        for f in feats:
            src = f"{f.source_method} ({f.source_file.split('/')[-1]}:{f.source_line})"
            print(f"{f.id:<40} {_feature_test_count(f.id):>5}  {src}")
        return 0

    all_feats = feat_repo.list()
    by_face: dict[str, int] = {}
    for f in all_feats:
        by_face[f.face] = by_face.get(f.face, 0) + 1
    print("=== FEATURE INVENTORY ===")
    print(f"  total features : {len(all_feats)} across {len(by_face)} faces")
    print()
    print(f"{'FACE':<32} {'FEATS':>5}")
    print("-" * 40)
    for face_id in sorted(by_face):
        print(f"{face_id:<32} {by_face[face_id]:>5}")
    without = [c.id for c in CapabilityRepository().list() if c.id not in by_face]
    if without:
        print()
        print(f"faces without @Feature annotations ({len(without)}):")
        for fid in without:
            print(f"  {fid}")
    return 0


def cmd_cap_feature(args) -> int:
    """One annotation in detail: what it declares, where it lives, and
    the raw test rows anchored to it."""
    feat = FeatureRepository().get(args.feature_id)
    if not feat:
        print(f"[mcbot] feature not found: {args.feature_id}", file=sys.stderr)
        print(f"  hint: run `capability scan-features` to discover @Feature annotations", file=sys.stderr)
        return 1
    print(f"=== FEATURE: {feat.id} ===")
    print(f"  face          : {feat.face}")
    print(f"  description   : {feat.description}")
    if feat.vanilla_ref:
        print(f"  vanilla ref   : {feat.vanilla_ref}")
    if feat.deviation:
        print(f"  deviation     : {feat.deviation}")
    print(f"  source        : {feat.source_file}:{feat.source_line}")
    if feat.source_method:
        print(f"  method        : {feat.source_method}")
    from mcbot.capability.db import get_connection
    with get_connection() as conn:
        cases = [dict(r) for r in conn.execute(
            "SELECT id, status, link_source, title FROM qa_test_cases "
            "WHERE feature_id = ? ORDER BY id", (feat.id,)).fetchall()]
    if cases:
        print()
        print("  linked tests:")
        for c in cases:
            print(f"    {c['id']:<45} {c['status'] or '?':<14} {(c['title'] or '')[:36]}")
    else:
        print()
        print("  no linked tests — add `// feature: <id>` above the @GameTest method")
    return 0
