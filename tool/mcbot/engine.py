"""GameTest engine receipts.

After every ``runGameTest`` run, ``write_engine_receipt`` parses the
log into a machine-readable JSON file under ``qa-results/engine-runs/``.
The receipt is the currency record the status panel and the H-R5
discipline read: ledger /claims of engine verification cite it
instead of prose.
"""
from __future__ import annotations

import datetime as _dt
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional

from mcbot.paths import PROJECT_ROOT

ENGINE_RUN_DIR = PROJECT_ROOT / "qa-results" / "engine-runs"


def git_head_short() -> Optional[str]:
    """Best-effort current commit id; None outside a repo."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        return out.stdout.strip() if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


_RE_TOTAL = re.compile(r"(\d+) GAME TESTS COMPLETE")
_RE_REQUIRED = re.compile(r"(\d+) required tests failed")
_RE_OPTIONAL = re.compile(r"(\d+) optional tests failed")
# Failed-test names print as a dash list under the verdict lines. Each
# line carries the log prefix "[HH:MM:SS] [thread/LEVEL] [logger]:", so
# anchor on the closing bracket of the logger tag, never the line start.
_RE_FAILED_NAME = re.compile(r"\]:\s+- ([a-z0-9_]+)\s*$", re.M)


def parse_run_log(log_path: Path) -> Optional[dict]:
    """Parse a finished runGameTest log into a verdict dict.

    The single parse behind both the JSON receipt writer and the
    capability-DB mirror, so the two can never disagree. Returns None
    when the log holds no verdict (crash, interrupt, wrong task).
    ``failed`` holds the required failures, ``failed_optional`` the
    optional ones; either list may be partial in a truncated log."""
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    m_total = _RE_TOTAL.search(text)
    if not m_total:
        return None
    total = int(m_total.group(1))
    m_req = _RE_REQUIRED.search(text)
    failed_count = int(m_req.group(1)) if m_req else 0
    m_opt = _RE_OPTIONAL.search(text)
    optional_count = int(m_opt.group(1)) if m_opt else 0
    names = list(_RE_FAILED_NAME.finditer(text))
    req_end = m_opt.start() if m_opt else len(text)
    required = (
        [m.group(1) for m in names if m_req.start() <= m.start() < req_end][:failed_count]
        if m_req else []
    )
    optional = (
        [m.group(1) for m in names if m.start() >= m_opt.start()][:optional_count]
        if m_opt else []
    )
    return {
        "total": total,
        "failed_count": failed_count,
        "failed": required,
        "optional_failed_count": optional_count,
        "failed_optional": optional,
        "green": failed_count == 0,
    }


def write_engine_receipt(log_path: Path) -> Optional[Path]:
    """Parse a finished runGameTest log into a machine-readable
    receipt (scenario totals, failures, git rev) under
    qa-results/engine-runs/. The receipt is the currency record the
    status panel and the H-R5 discipline read: ledger /claims of
    engine verification cite it instead of prose. Returns the
    receipt path, or None when the log holds no verdict (crash,
    interrupt, wrong task)."""
    verdict = parse_run_log(log_path)
    if verdict is None:
        return None
    receipt = {
        "schema": 1,
        "task": "runGameTest",
        "log": str(log_path),
        "git_rev": git_head_short(),
        "finished_at": _dt.datetime.now().isoformat(timespec="seconds"),
        "scenarios_total": verdict["total"],
        "failed_count": verdict["failed_count"],
        "failed": verdict["failed"],
        "optional_failed_count": verdict["optional_failed_count"],
        "failed_optional": verdict["failed_optional"],
        "green": verdict["green"],
    }
    ENGINE_RUN_DIR.mkdir(parents=True, exist_ok=True)
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = ENGINE_RUN_DIR / f"gametest-{stamp}.json"
    try:
        path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"[mcbot] engine receipt: {path} (total={verdict['total']}, failed={verdict['failed_count']})")
    except OSError as e:
        print(f"[mcbot] WARN: cannot write engine receipt: {e}", file=sys.stderr)
        return None
    return path


def engine_currency_line() -> Optional[str]:
    """One status line: how stale the newest engine receipt is
    relative to commits that touched the engine-verified layers
    (gametest + adapter). None when no receipt exists yet."""
    try:
        receipts = sorted(ENGINE_RUN_DIR.glob("gametest-*.json"))
    except OSError:
        return None
    if not receipts:
        return None
    try:
        data = json.loads(receipts[-1].read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    rev = data.get("git_rev")
    if not rev:
        return None
    try:
        out = subprocess.run(
            ["git", "log", "--oneline", f"{rev}..HEAD", "--",
             "src/main/java/com/mcbot/mcbotserver/gametest",
             "src/main/java/com/mcbot/mcbotserver/adapter"],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True, timeout=10,
        )
        stale = [l for l in out.stdout.splitlines() if l.strip()]
    except (OSError, subprocess.SubprocessError):
        stale = []
    base = (
        f"engine: {data.get('scenarios_total')} scenarios, receipt {receipts[-1].name} "
        f"@{rev} ({'GREEN' if data.get('green') else 'RED'}), {len(stale)} later commits touched adapter/gametest"
    )
    return base + ("" if not stale else "  <- H-R5: rerun `build runGameTest` before verified claims")
