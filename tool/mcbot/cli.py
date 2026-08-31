"""CLI command handlers and argparse wiring.

Every ``cmd_*`` function is a thin rendering layer: it calls into
the core modules (``mcbot.gradle``, ``mcbot.lock``, ``mcbot.docs``,
...) and formats their return values for the terminal. The entry
point ``main()`` is invoked by ``tool/mcbot_tool.py``.
"""
from __future__ import annotations

import argparse
import datetime as _dt
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
from mcbot.capability.gametest_scan import scan_gametests
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
                total_cases = conn.execute("SELECT COUNT(*) c FROM qa_test_cases").fetchone()["c"]
                linked_cases = conn.execute(
                    "SELECT COUNT(*) c FROM qa_test_cases WHERE capability_id IS NOT NULL"
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
            print(
                f"capabilities: {ov['total']} faces, {shipped} shipped ({shipped_pct:.0f}%), "
                f"{total_cases} QA cases ({linked_cases} linked, {unlinked} unlinked){receipt_str}"
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
        "qa-import": cmd_cap_qa_import,
        "scan-gametest": cmd_cap_scan_gametest,
        "link": cmd_cap_link,
        "unlinked": cmd_cap_unlinked,
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
    print(f"Capabilities: {ov['total']} total")
    bs = ov["by_status"]
    print(f"  shipped={bs.get('shipped', 0)}  partial={bs.get('partial', 0)}  "
          f"gap={bs.get('gap', 0)}  deferred={bs.get('deferred', 0)}")
    shipped_pct = (bs.get('shipped', 0) / ov['total'] * 100) if ov['total'] else 0
    print(f"  shipped rate: {shipped_pct:.1f}%")
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
    print("[mcbot] engine-receipt backfill complete")
    print(f"  files    : {result['files']} gametest-*.json receipts")
    print(f"  inserted : {result['inserted']} (into test_receipts)")
    print(f"  skipped  : {result['skipped']} (already mirrored)")
    print(f"  case rows: {result['case_rows']} (failed scenarios linked where possible)")
    if result["recovered"]:
        print(f"  recovered: {result['recovered']} receipts whose failed names "
              f"were re-parsed from surviving logs")
    if result["unreadable"]:
        print(f"  WARN: {result['unreadable']} unreadable JSON files", file=sys.stderr)
    return 0


def cmd_cap_qa_import(args) -> int:
    from pathlib import Path
    csv_path = Path(args.csv_file)
    if not csv_path.exists():
        print(f"[mcbot] CSV file not found: {csv_path}", file=sys.stderr)
        return 1
    result = import_csv(csv_path)
    print(f"[mcbot] QA import complete")
    print(f"  inserted   : {result['inserted']}")
    print(f"  updated    : {result['updated']}")
    print(f"  auto_linked: {result['auto_linked']}")
    print(f"  unlinked   : {result['unlinked']}")
    if result['skipped']:
        print(f"  skipped    : {result['skipped']} (no case id)")
    print(f"  total cases: {result['total']}")
    return 0


def cmd_cap_scan_gametest(args) -> int:
    result = scan_gametests()
    if "error" in result:
        print(f"[mcbot] scan failed: {result['error']}", file=sys.stderr)
        return 1
    print(f"[mcbot] gametest scan complete")
    print(f"  scanned files : {result['scanned_files']}")
    print(f"  skipped classes: {result['skipped_classes']} (framework/integration)")
    print(f"  test methods  : {result['total_methods']}")
    print(f"  inserted      : {result['inserted']}")
    print(f"  updated       : {result['updated']}")
    print(f"  auto_linked   : {result['auto_linked']}")
    print(f"  unlinked      : {result['unlinked']}")
    print(f"  total cases   : {result['total_cases']}")
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
    print(f"[mcbot] linked {args.case_id} -> {args.capability_id}")
    return 0


def cmd_cap_unlinked(args) -> int:
    cases = list_unlinked()
    if not cases:
        print("[mcbot] all QA cases are linked to a capability")
        return 0
    print(f"{'CASE ID':<45} {'TYPE':<10} {'MODULE':<12} TITLE")
    print("-" * 120)
    for c in cases:
        print(f"{c.id:<45} {c.test_type:<10} {c.module:<12} {c.title}")
    print(f"\n{len(cases)} unlinked cases")
    print("  hint: use `capability link <case_id> <capability_id>` to link manually")
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
        help="mirror committed qa-results/engine-runs JSON receipts into the DB (idempotent)")
    p_cap_qa = p_cap_sub.add_parser("qa-import", help="import QA test cases from a CSV file")
    p_cap_qa.add_argument("csv_file", help="path to QA CSV file (UTF-8 with BOM supported)")
    p_cap_sub.add_parser("scan-gametest", help="scan gametest source for @GameTest methods and auto-link")
    p_cap_link = p_cap_sub.add_parser("link", help="manually link a QA case to a capability")
    p_cap_link.add_argument("case_id", help="QA case id, e.g. TC-COMBAT-001 or GT-BotCombat-xxx")
    p_cap_link.add_argument("capability_id", help="capability id, e.g. combat.bow_draw")
    p_cap_sub.add_parser("unlinked", help="list QA cases not linked to any capability")
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
