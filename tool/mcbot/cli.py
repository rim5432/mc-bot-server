"""CLI command handlers and argparse wiring.

Every ``cmd_*`` function is a thin rendering layer: it calls into
the core modules (``mcbot.gradle``, ``mcbot.lock``, ``mcbot.docs``,
...) and formats their return values for the terminal. The entry
point ``main()`` is invoked by ``tool/mcbot_tool.py``.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from mcbot.config import (
    BUILD_TASK,
    CLEAN_TASK,
    COMPILE_JAVA,
    DEPS_TASK,
    JAR_TASK,
    LINT_TASKS,
    NEEDS_NO_DAEMON,
    RUN_CLIENT,
    RUN_DATA,
    RUN_GAMETEST,
    RUN_SERVER,
    SYNC_TASK,
    TASKS_ALL,
    TEST_TASK,
    _read_gradle_props,
)
from mcbot.docs import (
    DOC_CATEGORIES,
    DOC_DIR,
    DOC_INDEX,
    DEFAULT_STALE_DAYS,
    _doc_status_word,
    _fuzzy_find_docs,
    run_doc_checks,
)
from mcbot.engine import engine_currency_line
from mcbot.gradle import _resolve_gradle, run_gradle
from mcbot.lock import (
    BuildLock,
    _force_clear_lock,
    _print_busy,
    all_locks,
)
from mcbot.capability import db as cap_db
from mcbot.capability import queries as cap_queries
from mcbot.capability.backfill import backfill_receipts
from mcbot.capability.feature_scan import scan_features
from mcbot.capability.feature_repository import FeatureRepository
from mcbot.capability.gametest_scan import scan_gametests
from mcbot.capability.report import (
    action_queue,
    diff_since,
    domain_report,
    evidence_for_faces,
    evidence_rollup,
    harness_axis,
    is_internal_face,
    source_drift,
    spec_impl_gap,
    staleness_for_faces,
    status_suggestions,
)
from mcbot.capability.validation import validate_db
from mcbot.capability.state_export import restore_state, write_state_through
from mcbot.capability.qa_import import import_csv, link_case, list_unlinked
from mcbot.capability.receipt import latest_receipt_summary
from mcbot.capability.repository import CapabilityRepository, VALID_STATUSES
from mcbot.capability.seed import seed_database
from mcbot.paths import LAST_LOG, PROJECT_ROOT, RUNTIME_DIR
from mcbot.proc import list_gradle_processes

# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------
def cmd_build(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    sub = args.subcommand
    gradle_args_map = {
        "compile": COMPILE_JAVA,
        "jar": JAR_TASK,
        "build": BUILD_TASK,
        "runClient": RUN_CLIENT,
        "runServer": RUN_SERVER,
        "runData": RUN_DATA,
        "runGameTest": RUN_GAMETEST,
        "clean": CLEAN_TASK,
        "sync": SYNC_TASK,
    }
    base = gradle_args_map.get(sub)
    if base is None:
        print(f"[mcbot] unknown build subcommand: {sub}", file=sys.stderr)
        return 2
    gradle_args = list(base) + list(args.passthrough or [])
    no_daemon = args.no_daemon or tuple(gradle_args[: len(base)]) in NEEDS_NO_DAEMON
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    command = f"build {sub}"
    # Long-running game tasks only READ build outputs, so each gets
    # its own lock namespace: a dedicated server and a dev client can
    # be alive at the same time. Anything that writes build/ still
    # serializes on the global "build" lock.
    if sub in {"runClient", "runServer", "runGameTest", "runData"}:
        lock_name = f"run.{sub}"
    else:
        lock_name = "build"
    with BuildLock(lock_name) as lock:
        if not lock.acquire(command):
            return _print_busy(lock_name)
        return run_gradle(g, gradle_args, no_daemon=no_daemon, with_cc=with_cc, log_name=sub)


def cmd_test(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    gradle_args = TEST_TASK + list(args.passthrough or [])
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    with BuildLock() as lock:
        if not lock.acquire("test"):
            return _print_busy()
        return run_gradle(g, gradle_args, no_daemon=True, with_cc=with_cc, log_name="test")


def cmd_gradle(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    if not args.gradle_args:
        print("[mcbot] gradle passthrough: provide at least one arg", file=sys.stderr)
        return 2
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    command = f"gradle {args.gradle_args[0]}"
    with BuildLock() as lock:
        if not lock.acquire(command):
            return _print_busy()
        return run_gradle(g, args.gradle_args, no_daemon=args.no_daemon, with_cc=with_cc)


def cmd_lint(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    gradle_args = LINT_TASKS + list(args.passthrough or [])
    with_cc = args.cc or os.environ.get("MCBOT_CC") == "1"
    with BuildLock() as lock:
        if not lock.acquire("lint"):
            return _print_busy()
        return run_gradle(g, gradle_args, no_daemon=args.no_daemon, with_cc=with_cc, log_name="lint")


def cmd_passthrough_no_lock(args) -> int:
    g = _resolve_gradle()
    if not g:
        return 2
    return run_gradle(g, args.gradle_args, no_daemon=args.no_daemon, with_cc=False)


def cmd_status(args) -> int:
    for s in all_locks():
        if s.get("locked"):
            print(
                f"lock[{s['name']}]: BUSY  pid={s.get('pid')} alive={s.get('alive')} "
                f"cmd={s.get('command')} since={s.get('start_iso')}"
            )
        else:
            print(f"lock[{s['name']}]: free")
    if LAST_LOG.exists():
        try:
            p = LAST_LOG.read_text(encoding="utf-8").strip()
            print(f"last log: {p}")
        except OSError:
            pass
    procs = list_gradle_processes()
    print(f"java/gradle processes: {len(procs)}")
    for p in procs[:30]:
        print(f"  pid={p['pid']:<8} ws_mb={p['ws_mb']:<8.0f} {p['desc'][:200]}")
    line = engine_currency_line()
    if line:
        print(line)
    # doc health (cheap early-warning; full audit is `doc check`)
    if DOC_DIR.exists():
        try:
            results = run_doc_checks()
            n_err = sum(1 for r in results if _doc_status_word(r["issues"]) == "ERR")
            n_warn = sum(1 for r in results if _doc_status_word(r["issues"]) == "warn")
            hint = "" if (n_err or n_warn) == 0 else "  <- run: python tool/mcbot_tool.py doc check"
            print(f"docs: {len(results)} files, rot-errors={n_err}, warnings={n_warn}{hint}")
        except Exception:
            pass
    # capability matrix summary (data-driven QA/test convergence)
    try:
        ov = cap_queries.overview()
        if ov["total"] > 0:
            bs = ov["by_status"]
            shipped = bs.get("shipped", 0)
            shipped_pct = shipped / ov["total"] * 100
            # QA case counts
            from mcbot.capability.db import get_connection
            with get_connection() as conn:
                total_cases = conn.execute(
                    "SELECT COUNT(*) c FROM qa_test_cases WHERE kind != 'wire'"
                ).fetchone()["c"]
                linked_cases = conn.execute(
                    "SELECT COUNT(*) c FROM qa_test_cases "
                    "WHERE capability_id IS NOT NULL AND kind != 'wire'"
                ).fetchone()["c"]
            unlinked = total_cases - linked_cases
            # latest test receipt
            receipt = latest_receipt_summary()
            receipt_str = ""
            if receipt:
                verdict = "GREEN" if receipt["green"] else "RED"
                receipt_str = (
                    f"  last-run: {receipt['scenarios_total']} scenarios, "
                    f"{receipt['failed_count']} failed ({verdict}) @{receipt.get('git_rev') or '?'}"
                )
            try:
                ev = evidence_rollup()
                ev_str = (f", {ev['shipped_green']}/{ev['shipped']} shipped w/ green evidence"
                          if ev["shipped"] else "")
            except Exception:
                ev_str = ""
            print(
                f"capabilities: {ov['total']} faces, {shipped} shipped* ({shipped_pct:.0f}%)"
                f"{ev_str}, "
                f"{total_cases} cases ({linked_cases} linked, {unlinked} unlinked){receipt_str}"
            )
    except Exception:
        pass  # capability DB not initialized yet — silent
    return 0


def cmd_log(args) -> int:
    if args.action == "tail":
        if not LAST_LOG.exists():
            print("[mcbot] no build-last.log pointer", file=sys.stderr)
            return 1
        try:
            p = LAST_LOG.read_text(encoding="utf-8").strip()
        except OSError as e:
            print(f"[mcbot] read last-log pointer failed: {e}", file=sys.stderr)
            return 1
        if not p or not Path(p).exists():
            print(f"[mcbot] last log not found: {p}", file=sys.stderr)
            return 1
        n = args.lines
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        except OSError as e:
            print(f"[mcbot] read failed: {e}", file=sys.stderr)
            return 1
        for line in lines[-n:]:
            print(line, end="")
        if lines and not lines[-1].endswith("\n"):
            print()
        return 0

    if args.action == "list":
        if not RUNTIME_DIR.exists():
            print("[mcbot] no .runtime/ yet")
            return 0
        logs = sorted(
            RUNTIME_DIR.glob("build-*.log"),
            key=lambda q: q.stat().st_mtime,
            reverse=True,
        )
        for p in logs[:50]:
            sz = p.stat().st_size
            mt = _dt.datetime.fromtimestamp(p.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")
            print(f"{mt}  {sz:>10}  {p.name}")
        return 0

    if args.action == "cat":
        if not args.task:
            print("[mcbot] need task name (e.g. `log cat compile`)", file=sys.stderr)
            return 2
        if not RUNTIME_DIR.exists():
            print(f"[mcbot] no log for task {args.task}", file=sys.stderr)
            return 1
        # Fuzzy: `log cat compile` should match both `build-compile-*.log`
        # and `build-compileJava-*.log`. The most recent wins.
        matches = sorted(
            RUNTIME_DIR.glob(f"build-*{args.task}*.log"),
            key=lambda q: q.stat().st_mtime,
            reverse=True,
        )
        if not matches:
            print(f"[mcbot] no log matching task {args.task}", file=sys.stderr)
            return 1
        path = matches[0]
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                sys.stdout.write(f.read())
        except OSError as e:
            print(f"[mcbot] read failed: {e}", file=sys.stderr)
            return 1
        return 0

    print(f"[mcbot] unknown log action: {args.action}", file=sys.stderr)
    return 2


def cmd_lock(args) -> int:
    if args.action == "status":
        for s in all_locks():
            if not s.get("locked"):
                print(f"lock[{s['name']}]: free")
                continue
            pid = s.get("pid")
            alive = s.get("alive", False)
            print(
                f"lock[{s['name']}]: BUSY  pid={pid} alive={alive} "
                f"cmd={s.get('command')} since={s.get('start_iso')}"
            )
            if not alive:
                print(f"  (holder is dead — run `lock clear` to remove {s['name']})")
        return 0
    if args.action == "clear":
        refused = False
        for s in all_locks():
            if not s.get("locked"):
                continue
            if s.get("alive"):
                print(
                    f"[mcbot] refusing to clear '{s['name']}': holder pid {s.get('pid')} is still alive",
                    file=sys.stderr,
                )
                refused = True
                continue
            _force_clear_lock(s["name"])
            print(f"lock[{s['name']}] cleared")
        return 1 if refused else 0
    if args.action == "takeover":
        refused = False
        for s in all_locks():
            if not s.get("locked"):
                continue
            if s.get("alive"):
                print(
                    f"[mcbot] refusing to takeover '{s['name']}': holder pid {s.get('pid')} is still alive",
                    file=sys.stderr,
                )
                refused = True
                continue
            _force_clear_lock(s["name"])
            print(f"lock[{s['name']}] taken over (previous holder was dead)")
        if not any(s.get("locked") for s in all_locks()) and not refused:
            print("no locks present, nothing to take over")
        return 1 if refused else 0
    print(f"[mcbot] unknown lock action: {args.action}", file=sys.stderr)
    return 2


def cmd_proc(args) -> int:
    if args.action == "list":
        procs = list_gradle_processes()
        for p in procs:
            print(f"pid={p['pid']}  ws_mb={p['ws_mb']:.0f}  {p['desc'][:200]}")
        return 0
    if args.action == "killdaemon":
        if not args.yes:
            print(
                "[mcbot] this will kill gradle daemons. re-run with --yes to confirm.",
                file=sys.stderr,
            )
            return 1
        procs = list_gradle_processes()
        targets = []
        for p in procs:
            d = p["desc"]
            if "GradleDaemon" in d or "gradle-launcher" in d or "GradleMain" in d:
                targets.append(p["pid"])
        if not targets:
            print("[mcbot] no gradle daemon found")
            return 0
        for pid in targets:
            print(f"  killing pid {pid}")
            try:
                if os.name == "nt":
                    subprocess.run(
                        ["taskkill", "/F", "/PID", str(pid)],
                        check=True,
                        capture_output=True,
                    )
                else:
                    os.kill(pid, 15)
            except Exception as e:
                print(f"  fail: {e}")
        return 0
    print(f"[mcbot] unknown proc action: {args.action}", file=sys.stderr)
    return 2


# ---------------------------------------------------------------------------
# capability matrix commands (SQLite-backed)
# ---------------------------------------------------------------------------
def cmd_capability(args) -> int:
    dispatch = {
        "init": cmd_cap_init,
        "overview": cmd_cap_overview,
        "list": cmd_cap_list,
        "status": cmd_cap_status,
        "set": cmd_cap_set,
        "gaps": cmd_cap_gaps,
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
    st = staleness_for_faces().get(cap.id)
    if st:
        stale_label = st["state"].upper()
        if st["state"] == "stale" and st["days_stale"] is not None:
            stale_label += f" ({st['days_stale']:.1f}d since last green after code change)"
        print(f"\n  staleness   : {stale_label}")
        if st["last_code_change"]:
            print(f"    code change: {st['last_code_change']}")
        if st["last_green_at"]:
            print(f"    last green : {st['last_green_at']}")

    # --- record integrity panel: six axes, one glance ---
    drift = source_drift().get(cap.id, {})
    ev = ev or {"state": "untested", "red_runs": 0}
    print()
    print("  RECORD INTEGRITY")
    # source paths
    if cap.source_paths:
        missing = drift.get("missing", [])
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
        print(f"    harness_paths : \u2717 PATHLESS — add boundary-D path in seed.py HARNESS_PATHS")
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
    if drift.get("state") == "drift":
        print(f"    code drift    : \u2717 code changed {drift.get('days_drift', 0):.1f}d after record update")
    elif drift.get("state") == "missing":
        print(f"    code drift    : \u2717 source path missing (see above)")
    elif drift.get("state") == "fresh":
        print(f"    code drift    : \u2713 fresh")
    # NEXT action — pick the single highest-priority thing to do
    next_action = _next_action_for(cap, ev, drift)
    print()
    print(f"  NEXT: {next_action}")
    return 0


def _next_action_for(cap, ev: dict, drift: dict) -> str:
    """Pick the single highest-priority next action for a face.

    Order mirrors the audit P0/P1/P2 bands: missing paths first, then
    evidence problems, then coverage gaps, then drift. Returns a
    copy-pasteable command or edit instruction.
    """
    if drift.get("state") == "missing":
        return ("update seed.py SOURCE_PATHS for this face (file moved/renamed), "
                "then `capability init`")
    if cap.implementation_status == "shipped" and ev.get("state") == "untested":
        return "add a @GameTest method, then `capability scan-gametest`"
    if cap.implementation_status == "shipped" and ev.get("state") == "red":
        return "investigate newest runGameTest failure, then re-run `build runGameTest`"
    if not cap.harness_paths and not is_internal_face(cap.id) and cap.implementation_status != "deferred":
        return "add harness_paths in seed.py HARNESS_PATHS, then `capability init`"
    if cap.implementation_status in ("gap", "deferred"):
        return (f"implement + test, then `capability set {cap.id} --status partial --verify`")
    if drift.get("state") == "drift":
        return "re-verify the record matches current code, then `capability set --verify`"
    if cap.implementation_status in ("partial", "gap") and ev.get("state") == "green":
        return f"evidence is green — review and `capability set {cap.id} --status shipped --verify`"
    if not cap.vanilla_ref:
        return "add vanilla_ref citing the decompiled source (see player-behavior-RE.md)"
    return "record is clean — no action needed"


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
    print(f"status transitions: {len(d['transitions'])}  [{d['transitions_note']}]")
    for t in d["transitions"]:
        name = f"  {t['name']}" if t.get("name") else ""
        print(f"  {t['changed_at']}  {t['capability_id']}{name}: "
              f"{t['old_status']} -> {t['new_status']}  ({t['source']})")
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

    staleness = staleness_for_faces()
    stale_count = sum(1 for s in staleness.values() if s["state"] == "stale")
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
                st = staleness.get(f["id"], {})
                stale_str = st.get("state", "-").upper()
                if st.get("state") == "stale" and st.get("days_stale") is not None:
                    stale_str += f"({st['days_stale']:.0f}d)"
                print(f"{f['id']:<28} {f['implementation_status'] + '*':<10} {ev:<9} {stale_str:<8} "
                      f"{f['verified_at'] or '-':<12} "
                      f"{f['spec_count']:>5} {f['impl_count']:>5}  {' '.join(flags)}")
    else:
        for f in rep["faces"]:
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
            st = staleness.get(f["id"], {})
            stale_str = st.get("state", "-").upper()
            if st.get("state") == "stale" and st.get("days_stale") is not None:
                stale_str += f"({st['days_stale']:.0f}d)"
            print(f"{f['id']:<28} {f['implementation_status'] + '*':<10} {ev:<9} {stale_str:<8} "
                  f"{f['verified_at'] or '-':<12} "
                  f"{f['spec_count']:>5} {f['impl_count']:>5}  {' '.join(flags)}")
    if rep["faces_no_spec"]:
        print(f"\n  no specs (no declared testing intent): {', '.join(rep['faces_no_spec'])}")
    if rep["faces_no_impl"]:
        print(f"  no impls (no automated anchor): {', '.join(rep['faces_no_impl'])}")
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
    artifacts in the DB, plus spec-vs-impl coverage gap analysis.

    Exit 0 if all valid; exit 1 if any errors (CI-gateable). This is
    the single command that answers 'is our test data clean?' without
    running the game - pure read-model over the DB.
    """
    errors = validate_db()
    gap = spec_impl_gap()
    print(f"[mcbot] validation: {len(errors)} artifact(s) with errors, "
          f"{gap['summary']['both']} covered, {gap['summary']['spec_only']} spec-only, "
          f"{gap['summary']['impl_only']} impl-only, {gap['summary']['neither']} untested")
    if errors:
        print()
        print(f"{'ARTIFACT':<50} {'KIND':<6} ERRORS")
        print("-" * 110)
        for artifact, errs in errors[:30]:
            print(f"{artifact.id:<50} {artifact.kind:<6} {'; '.join(errs)}")
        if len(errors) > 30:
            print(f"... and {len(errors) - 30} more")
    if gap["spec_only"]:
        print()
        print("spec-only (declared intent, no automated anchor) — add gametest or accept as manual:")
        for e in gap["spec_only"]:
            print(f"  {e['id']:<30} [{e['status']:<8}] spec={e['spec_count']} impl=0")
    if gap["impl_only"]:
        print()
        print("impl-only (automated test, no declared intent) — add CSV spec or accept as exploratory:")
        for e in gap["impl_only"]:
            print(f"  {e['id']:<30} [{e['status']:<8}] spec=0 impl={e['impl_count']}")
    if gap["neither"]:
        print()
        print("untested (no spec, no impl) — internal reflex/sense faces by design, rest are review candidates:")
        for e in gap["neither"]:
            print(f"  {e['id']:<30} [{e['status']:<8}]")
    return 1 if errors else 0


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


# ---------------------------------------------------------------------------
# doc commands
# ---------------------------------------------------------------------------
def cmd_doc_list(args) -> int:
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet", file=sys.stderr)
        return 1
    results = run_doc_checks()
    print(f"{'STATUS':<6} {'AGE(d)':>6}  {'VERIFIED':<10}  PATH")
    for r in results:
        print(f"{_doc_status_word(r['issues']):<6} {r['age']:>6}  {r['verified']:<10}  {r['path']}")
    errs = sum(1 for r in results if _doc_status_word(r["issues"]) == "ERR")
    warns = sum(1 for r in results if _doc_status_word(r["issues"]) == "warn")
    print(f"\n{len(results)} docs | ok={len(results)-errs-warns} warn={warns} err={errs}")
    return 0


def cmd_doc_check(args) -> int:
    props = _read_gradle_props()
    print(f"[mcbot] current: MC {props.get('minecraft_version', '?')} / "
          f"Forge {props.get('forge_version', '?')} / mod {props.get('mod_id', '?')} "
          f"{props.get('mod_version', '?')}")
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet — nothing to check", file=sys.stderr)
        return 1
    results = run_doc_checks(args.stale_days)
    failed = 0
    for r in results:
        errs = [i for i in r["issues"] if i[0] == "ERR"]
        warns = [i for i in r["issues"] if i[0] == "WARN"]
        status = _doc_status_word(r["issues"])
        marker = {"ok": "OK  ", "warn": "WARN", "ERR": "ERR!"}[status]
        print(f"\n[{marker}] doc/{r['path']}  ({r['title'] or '?'}, verified {r['verified'] or '?'})")
        for _, code, msg in errs + warns:
            print(f"  - {code}: {msg}")
        if errs or (args.strict and warns):
            failed += 1
    n_err = sum(len([i for i in r["issues"] if i[0] == "ERR"]) for r in results)
    n_warn = sum(len([i for i in r["issues"] if i[0] == "WARN"]) for r in results)
    print(f"\n[mcbot] docs: {len(results)} checked | errors={n_err} | warnings={n_warn}")
    if failed:
        print(f"[mcbot] FAIL: {failed} doc(s) rotten — fix issues above, then "
              f"`doc touch <name>` after re-verifying content.", file=sys.stderr)
        return 1
    print("[mcbot] docs healthy")
    return 0


def cmd_doc_touch(args) -> int:
    hits = _fuzzy_find_docs(args.name)
    if not hits:
        print(f"[mcbot] no doc matching fragment: {args.name}", file=sys.stderr)
        return 1
    if len(hits) > 1:
        print("[mcbot] ambiguous match:", file=sys.stderr)
        for h in hits:
            print(f"  {h.relative_to(PROJECT_ROOT).as_posix()}", file=sys.stderr)
        return 2
    path = hits[0]
    today = _dt.date.today().isoformat()
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"[mcbot] read failed: {e}", file=sys.stderr)
        return 1
    new_text, n = re.subn(r"(?m)^last_verified:.*$", f"last_verified: {today}", text)
    if n == 0:
        lines = new_text.splitlines()
        try:
            close = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
            lines.insert(close, f"last_verified: {today}")
            new_text = "\n".join(lines) + "\n"
        except StopIteration:
            print(f"[mcbot] cannot touch: no closed front-matter in {path}", file=sys.stderr)
            return 1
    try:
        path.write_text(new_text, encoding="utf-8", newline="\n")
    except OSError as e:
        print(f"[mcbot] write failed: {e}", file=sys.stderr)
        return 1
    print(f"[mcbot] touched: {path.relative_to(PROJECT_ROOT).as_posix()} -> last_verified: {today}")
    return 0


def cmd_doc_new(args) -> int:
    slug = re.sub(r"[^a-z0-9\-_]+", "-", args.slug.lower()).strip("-")
    if not slug:
        print("[mcbot] invalid slug", file=sys.stderr)
        return 2
    if args.category == "decisions" and not re.match(r"\d{4}-", slug):
        existing = sorted(int(p.name[:4]) for p in (DOC_DIR / "decisions").glob("*.md")
                          if p.name[:4].isdigit()) if (DOC_DIR / "decisions").exists() else []
        nxt = (existing[-1] + 1) if existing else 1
        slug = f"{nxt:04d}-{slug}"
    cat_dir = DOC_DIR / args.category
    cat_dir.mkdir(parents=True, exist_ok=True)
    path = cat_dir / f"{slug}.md"
    if path.exists():
        print(f"[mcbot] refusing to overwrite existing doc: {path}", file=sys.stderr)
        return 1
    title = args.title or slug.replace("-", " ").title()
    today = _dt.date.today().isoformat()
    template = (
        "---\n"
        f"title: {title}\n"
        f"last_verified: {today}\n"
        "covers: []\n"
        "---\n"
        f"# {title}\n\n"
        "Write content here.\n\n"
        "`covers:` lists repo paths this document describes. When those paths change\n"
        "in git after `last_verified`, `doc check` flags the doc as DRIFT (rot).\n"
        "After updating the content, run `python tool/mcbot_tool.py doc touch {slug}`.\n"
    ).format(slug=slug)
    path.write_text(template, encoding="utf-8", newline="\n")
    print(f"[mcbot] created {path.relative_to(PROJECT_ROOT).as_posix()}")
    print(f"[mcbot] hint: regenerate the index with `doc index`")
    return 0


def cmd_doc_index(args) -> int:
    if not DOC_DIR.exists():
        print("[mcbot] no doc/ directory yet", file=sys.stderr)
        return 1
    results = run_doc_checks()
    by_cat: dict = {}
    for r in results:
        parts = r["path"].split("/", 1)
        cat = parts[0] if len(parts) > 1 else "(root)"
        by_cat.setdefault(cat, []).append(r)

    today = _dt.date.today().isoformat()
    out = [
        "<!-- AUTO-GENERATED by `python tool/mcbot_tool.py doc index` -- DO NOT EDIT -->",
        "# MC Bot Server - Documentation Index",
        "",
        f"Generated: {today}. Validate anytime with `python tool/mcbot_tool.py doc check`.",
        "",
    ]
    for cat in DOC_CATEGORIES:
        rows = by_cat.pop(cat, [])
        if not rows:
            continue
        out.append(f"## {cat}/")
        out.append("")
        out.append("| Document | Title | Verified | Status |")
        out.append("|---|---|---|---|")
        for r in rows:
            name = r["path"].split("/", 1)[1]
            status = {"ok": "ok", "warn": "stale?", "ERR": "**ROT**"}[_doc_status_word(r["issues"])]
            out.append(f"| [{name}]({cat}/{name}) | {r['title'] or '?'} | "
                       f"{r['verified'] or '?'} | {status} |")
        out.append("")
    for cat, rows in sorted(by_cat.items()):
        out.append(f"## {cat}/")
        out.append("")
        for r in rows:
            out.append(f"- [{r['path']}]({r['path']}) — {r['title'] or '?'}")
        out.append("")

    out += [
        "## Maintaining these documents",
        "",
        "- Every doc carries front-matter: `title`, `last_verified`, `covers:`.",
        "- `covers:` lists repo paths the doc describes. When git history shows those",
        "  paths changed *after* `last_verified`, `doc check` flags the doc as rotten.",
        "- Workflow after editing covered code: update the affected docs, run",
        "  `doc check`, then `doc touch <name>` on what you re-verified.",
        "- Superseded docs move to `archive/` with a `superseded_by:` pointer.",
        "- Hard constraints: root `AGENTS.md`; full tool table: tool/README.md.",
        "",
    ]
    DOC_INDEX.write_text("\n".join(out), encoding="utf-8", newline="\n")
    print(f"[mcbot] regenerated {DOC_INDEX.relative_to(PROJECT_ROOT).as_posix()} "
          f"({len(results)} docs)")
    return 0


def cmd_doc(args) -> int:
    dispatch = {
        "list": cmd_doc_list,
        "check": cmd_doc_check,
        "touch": cmd_doc_touch,
        "new": cmd_doc_new,
        "index": cmd_doc_index,
    }
    fn = dispatch.get(args.action)
    if fn is None:
        print(f"[mcbot] unknown doc action: {args.action}", file=sys.stderr)
        return 2
    return fn(args)


# ---------------------------------------------------------------------------
# argparse wiring
# ---------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(
        prog="mcbot_tool",
        description=(
            "mc-bot-server dev workflow CLI. Use this instead of raw ./gradlew.\n"
            "All build/test/gradle subcommands share a cross-process file lock so "
            "multiple agents can run concurrently without stepping on each other."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = ap.add_subparsers(dest="cmd", required=True)

    # build
    p_build = sub.add_parser("build", help="build / jar / runXxx (with concurrency gate)")
    p_build.add_argument(
        "subcommand",
        choices=["compile", "jar", "build", "runClient", "runServer",
                 "runData", "runGameTest", "clean", "sync"],
    )
    p_build.add_argument("--no-daemon", action="store_true", help="force --no-daemon")
    p_build.add_argument("--cc", action="store_true", help="enable --configuration-cache")
    p_build.add_argument(
        "passthrough", nargs=argparse.REMAINDER,
        help="extra gradle args after `--`, e.g. `build compile -- --info --stacktrace`",
    )
    p_build.set_defaults(func=cmd_build)

    # test
    p_test = sub.add_parser("test", help="run tests (with concurrency gate)")
    p_test.add_argument("--cc", action="store_true")
    p_test.add_argument("passthrough", nargs=argparse.REMAINDER)
    p_test.set_defaults(func=cmd_test)

    # gradle passthrough
    p_gradle = sub.add_parser(
        "gradle", help="passthrough any gradle command (with concurrency gate)",
    )
    p_gradle.add_argument("--no-daemon", action="store_true")
    p_gradle.add_argument("--cc", action="store_true")
    p_gradle.add_argument("gradle_args", nargs=argparse.REMAINDER)
    p_gradle.set_defaults(func=cmd_gradle)

    # lint: canonical -Plint dashboard round (see build-and-run.md)
    p_lint = sub.add_parser(
        "lint",
        help="full static-analysis round: pmd/cpd/spotbugs dashboards + EP compiles (-Plint)",
    )
    p_lint.add_argument("--no-daemon", action="store_true")
    p_lint.add_argument("--cc", action="store_true")
    p_lint.add_argument("passthrough", nargs=argparse.REMAINDER)
    p_lint.set_defaults(func=cmd_lint)

    # status / log / lock / proc
    p_status = sub.add_parser("status", help="one-page status: lock, last log, processes")
    p_status.set_defaults(func=cmd_status)

    p_log = sub.add_parser("log", help="tail / list / cat build logs")
    p_log_sub = p_log.add_subparsers(dest="action", required=True)
    p_log_tail = p_log_sub.add_parser("tail", help="tail last build's log (default 50 lines)")
    p_log_tail.add_argument("-n", "--lines", type=int, default=50)
    p_log_sub.add_parser("list", help="list all build-*.log files with size/mtime")
    p_log_cat = p_log_sub.add_parser("cat", help="cat full log of a task (most recent)")
    p_log_cat.add_argument("task", help="task name fragment, e.g. compile, runClient")
    p_log.set_defaults(func=cmd_log)

    p_lock = sub.add_parser("lock", help="lock status / clear / takeover")
    p_lock_sub = p_lock.add_subparsers(dest="action", required=True)
    p_lock_sub.add_parser("status")
    p_lock_sub.add_parser("clear", help="force-clear lock (only if holder is dead)")
    p_lock_sub.add_parser("takeover", help="alias for clear; emphasizes dead-holder recovery")
    p_lock.set_defaults(func=cmd_lock)

    p_proc = sub.add_parser("proc", help="list / kill gradle daemon")
    p_proc_sub = p_proc.add_subparsers(dest="action", required=True)
    p_proc_sub.add_parser("list")
    p_proc_killd = p_proc_sub.add_parser("killdaemon", help="kill all gradle daemons (needs --yes)")
    p_proc_killd.add_argument("--yes", action="store_true")
    p_proc.set_defaults(func=cmd_proc)

    # capability matrix (SQLite-backed; multi-agent safe via WAL)
    p_cap = sub.add_parser("capability", help="capability matrix: init/overview/list/status/set/gaps")
    p_cap_sub = p_cap.add_subparsers(dest="action", required=True)
    p_cap_sub.add_parser("init", help="initialize db and seed from player-behavior-RE.md")
    p_cap_sub.add_parser("overview", help="macro overview: per-category status breakdown")
    p_cap_list = p_cap_sub.add_parser("list", help="list capabilities (filter by category/status)")
    p_cap_list.add_argument("category", nargs="?", help="filter by category (e.g. digging, combat)")
    p_cap_list.add_argument("--status", choices=sorted(VALID_STATUSES), help="filter by implementation status")
    p_cap_status = p_cap_sub.add_parser("status", help="show full details for one capability")
    p_cap_status.add_argument("capability_id", help="capability id, e.g. dig.pacing")
    p_cap_set = p_cap_sub.add_parser("set", help="update status / deviation for a capability")
    p_cap_set.add_argument("capability_id", help="capability id")
    p_cap_set.add_argument("--status", choices=sorted(VALID_STATUSES), help="new implementation status")
    p_cap_set.add_argument("--deviation", help="update deviation note (pass empty string to clear)")
    p_cap_set.add_argument("--verify", action="store_true", help="mark verified_at = today")
    p_cap_sub.add_parser("gaps", help="list all gap/deferred capabilities")
    p_cap_sub.add_parser("db-status", help="show database health and row counts")
    p_cap_sub.add_parser(
        "backfill",
        help="mirror committed receipts into the DB (engine-runs + boundary-d; idempotent)")
    p_cap_diff = p_cap_sub.add_parser(
        "diff", help="what changed since a date: transitions, runs, reds, new faces")
    p_cap_diff.add_argument("--since", help="YYYY-MM-DD (default: 7 days ago)")
    p_cap_domain = p_cap_sub.add_parser(
        "domain", help="per-face evidence + deficiency report for one category")
    p_cap_domain.add_argument("category", help="capability category, e.g. combat, digging")
    p_cap_sub.add_parser(
        "restore",
        help="apply the committed overlay after a rebuild (init/import/scan/backfill); "
             "set/link maintain it write-through")
    p_cap_sub.add_parser(
        "paths",
        help="face -> boundary-D path axis + wire-run evidence + pathless review list")
    p_cap_ref_gen = p_cap_sub.add_parser(
        "ref-generate",
        help="enumerate the vanilla item-action inventory from the decompiled tree -> JSON")
    p_cap_ref_gen.add_argument("--decompiled-root", help="override the decompiled tree path")
    p_cap_sub.add_parser(
        "ref-import",
        help="fold the generated inventory + face-map into the DB (idempotent, prunes)")
    p_cap_sub.add_parser(
        "ref-coverage",
        help="action surface x face map: mapped/unmapped with anchors (falsifiable denominator)")
    p_cap_qa = p_cap_sub.add_parser("qa-import", help="import QA test cases from a CSV file")
    p_cap_qa.add_argument("csv_file", help="path to QA CSV file (UTF-8 with BOM supported)")
    p_cap_scan = p_cap_sub.add_parser("scan-gametest", help="scan gametest source for @GameTest methods and auto-link")
    p_cap_scan.add_argument("--strict", action="store_true",
                             help="fail on unlinked methods or invalid capability annotations (CI gate)")
    p_cap_link = p_cap_sub.add_parser("link", help="manually link a QA case to a capability")
    p_cap_link.add_argument("case_id", help="QA case id, e.g. TC-COMBAT-001 or GT-BotCombat-xxx")
    p_cap_link.add_argument("capability_id", help="capability id, e.g. combat.bow_draw")
    p_cap_sub.add_parser("unlinked", help="list QA cases not linked to any capability")
    p_cap_sub.add_parser(
        "validate",
        help="unified validation: schema/vocabulary/FK checks on all test artifacts + spec-vs-impl gap analysis (exit 1 on errors)")
    p_cap_sub.add_parser(
        "audit",
        help="priority-sorted action queue: everything needing a human (P0 shipped-without-evidence, P1 coverage gaps, P2 hygiene)")
    p_cap_features = p_cap_sub.add_parser(
        "features",
        help="list annotation-declared features (atomic units within faces), filter by face")
    p_cap_features.add_argument("face", nargs="?", help="filter by face id, e.g. combat.melee")
    p_cap_feature = p_cap_sub.add_parser(
        "feature",
        help="show one feature: description, source location, linked tests")
    p_cap_feature.add_argument("feature_id", help="feature id, e.g. combat.melee.crit_hit")
    p_cap_scan_feat = p_cap_sub.add_parser(
        "scan-features",
        help="scan Java source for @Feature annotations and upsert the features table")
    p_cap_scan_feat.add_argument("--strict", action="store_true",
                                 help="fail on annotations whose face is not in the seed table (CI gate)")
    p_cap.set_defaults(func=cmd_capability)

    # doc management (rot control; read-only except touch/new/index)
    p_doc = sub.add_parser("doc", help="documentation management: list/check/touch/new/index")
    p_doc_sub = p_doc.add_subparsers(dest="action", required=True)
    p_doc_sub.add_parser("list", help="list docs with health status")
    p_doc_check = p_doc_sub.add_parser(
        "check", help="audit docs for rot (exit 1 on errors — gate builds/PRs on this)")
    p_doc_check.add_argument("--strict", action="store_true",
                             help="treat warnings as failures too")
    p_doc_check.add_argument("--stale-days", type=int, default=DEFAULT_STALE_DAYS,
                             help=f"days before last_verified counts as stale "
                                  f"(default {DEFAULT_STALE_DAYS})")
    p_doc_touch = p_doc_sub.add_parser(
        "touch", help="mark a doc as re-verified today (fuzzy name match)")
    p_doc_touch.add_argument("name", help="name fragment, e.g. toolchain, 0001")
    p_doc_new = p_doc_sub.add_parser(
        "new", help="scaffold a new doc with front-matter template")
    p_doc_new.add_argument("category", choices=DOC_CATEGORIES)
    p_doc_new.add_argument("slug", help="file slug, e.g. event-bus-map or 0002-my-decision")
    p_doc_new.add_argument("--title", default="", help="human title (defaults from slug)")
    p_doc_sub.add_parser("index", help="regenerate doc/README.md index")
    p_doc.set_defaults(func=cmd_doc)

    # inspect-only (no lock). Always run with the canonical args; ignore
    # any user-supplied positional (REMAINDER doesn't play with default=).
    p_tasks = sub.add_parser("tasks", help="gradle tasks --all (no lock; read-only)")
    p_tasks.set_defaults(gradle_args=TASKS_ALL, no_daemon=False, func=cmd_passthrough_no_lock)

    p_deps = sub.add_parser("deps", help="gradle dependencies (no lock; read-only)")
    p_deps.set_defaults(gradle_args=DEPS_TASK, no_daemon=False, func=cmd_passthrough_no_lock)

    args = ap.parse_args()

    # REMAINDER usually includes the leading `--`; strip it if present
    if hasattr(args, "passthrough") and args.passthrough:
        if args.passthrough[0] == "--":
            args.passthrough = args.passthrough[1:]
    if hasattr(args, "gradle_args") and args.gradle_args:
        if isinstance(args.gradle_args, list) and args.gradle_args and args.gradle_args[0] == "--":
            args.gradle_args = args.gradle_args[1:]

    return args.func(args)
